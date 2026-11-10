package com.wanstu.cartograph;

import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.SurveyLedger;
import com.wanstu.cartograph.core.TerrainRelief;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import java.util.List;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Up to six sampled chunks per server tick, with a 2.5 ms time budget.
 * Does not load or generate any chunks. A viewer may keep one pending page;
 * requests cannot flood a server with scans, and only covered pages are
 * eligible for a response.
 */
public final class AtlasMapServer {
    private static final Logger LOG = LoggerFactory.getLogger("cartograph.survey");
    private static final int MAX_WORK_PER_TICK = 6;
    private static final long MAX_SURVEY_NANOS_PER_TICK = 2_500_000L;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final ArrayDeque<UUID> ORDER = new ArrayDeque<>();
    private static final Map<UUID, Long> LAST_DIAGNOSTIC = new HashMap<>();
    private static int ticks;

    private AtlasMapServer() {}

    public static ItemStack findAtlas(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(CartographMod.ATLAS.get())) return main;
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(CartographMod.ATLAS.get())) return offhand;
        for (ItemStack stack : player.getInventory().items)
            if (stack.is(CartographMod.ATLAS.get())) return stack;
        return ItemStack.EMPTY;
    }

    public static ItemStack findAtlas(ServerPlayer player, String atlasId) {
        if (atlasId == null || atlasId.isBlank()) {
            for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
                if (stack.is(CartographMod.ATLAS.get())
                        && stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                        AtlasState.blank()).id().isBlank()) return stack;
            }
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(CartographMod.ATLAS.get())
                        && stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                        AtlasState.blank()).id().isBlank()) return stack;
            }
            return ItemStack.EMPTY;
        }
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            if (stack.is(CartographMod.ATLAS.get())
                    && atlasId.equals(stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                    AtlasState.blank()).id())) return stack;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(CartographMod.ATLAS.get())
                    && atlasId.equals(stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                    AtlasState.blank()).id())) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static void enqueue(ServerPlayer player, MapNetwork.Request request) {
        if (Math.abs((long) request.pageX()) > 234375 ||
                Math.abs((long) request.pageZ()) > 234375) return;
        ItemStack stack = findAtlas(player, request.atlasId());
        if (stack.isEmpty()) return;
        AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
        AtlasWorldData.Entry entry = data.access(stack);
        PacketDistributor.sendToPlayer(player, AtlasActions.settings(player));
        String dim = player.serverLevel().dimension().location().toString();
        if (!entry.anchored()) {
            var origin = AtlasPage.atBlock(dim, player.getBlockX(), player.getBlockZ());
            data.anchor(entry, dim, origin.pageX(), origin.pageZ());
        }
        if (!entry.isCovered(dim, request.pageX(), request.pageZ())) {
            PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                    entry.state().id(), dim, request.pageX(), request.pageZ(),
                    new byte[MapNetwork.Page.PIXELS], new byte[MapNetwork.Page.PIXELS],
                    new short[0], new byte[0], 0L, 0L,
                    entry.state().inkUnits(), entry.state().pigmentUnits(),
                    entry.state().glowUnits(), entry.state().compassInstalled(),
                    false, true, request.cachedOnly(), 0, 0, 0,
                    entry.anchorDimension(), entry.anchorX(), entry.anchorZ(), entry.state().pages(),
                    entry.sheetSideBlocks(),
                    entry.surveyedCount(), entry.coloredCount(),
                    List.of(), List.of()));
            return;
        }
        Pending old = PENDING.get(player.getUUID());
        if (old != null) {
            // Never reset an incomplete page when a new request arrives; doing
            // so can starve scans indefinitely on busy/unresponsive clients.
            if (!old.dimension.equals(dim) || !old.atlasId.equals(entry.state().id())
                    || old.pageX != request.pageX() || old.pageZ != request.pageZ()) {
                player.displayClientMessage(
                        Component.literal("绘图师：上一页正在测绘，请稍候"), true);
            }
            return;
        }
        Pending next = new Pending(entry.state().id(),
                request.pageX(), request.pageZ(), dim, request.cachedOnly());
        PENDING.put(player.getUUID(), next);
        ORDER.addLast(player.getUUID());
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (++ticks % 600 == 0)
            LAST_DIAGNOSTIC.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        // A handful of completed snapshots per tick makes initial 512x512
        // sheets practical, while a wall-clock budget protects game TPS.
        // Never hold region FileChannels on the main thread.
        long tickStart = System.nanoTime();
        for (int step = 0; step < MAX_WORK_PER_TICK && !ORDER.isEmpty(); step++) {
            if (step >= 2 && System.nanoTime() - tickStart >= MAX_SURVEY_NANOS_PER_TICK) break;
            UUID viewer = ORDER.removeFirst();
            Pending task = PENDING.get(viewer);
            if (task == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            if (player == null || !player.serverLevel().dimension()
                    .location().toString().equals(task.dimension)) {
                PENDING.remove(viewer);
                continue;
            }
            ItemStack atlas = findAtlas(player, task.atlasId);
            if (atlas.isEmpty()) {
                PENDING.remove(viewer);
                continue;
            }
            AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
            AtlasWorldData.Entry entry = data.access(atlas);
            if (!entry.isCovered(task.dimension, task.pageX, task.pageZ)) {
                PENDING.remove(viewer);
                continue;
            }

            int index = task.next;
            int chunkX = task.pageX * AtlasPage.CHUNKS_PER_SIDE + index % 8;
            int chunkZ = task.pageZ * AtlasPage.CHUNKS_PER_SIDE + index / 8;
            WorldSurfaceIndex indexer = WorldSurfaceIndex.of(player.serverLevel());
            WorldSurfaceIndex.Lookup lookup =
                    task.cachedOnly ? indexer.cached(player.serverLevel(), chunkX, chunkZ)
                            : indexer.inspect(player.serverLevel(), chunkX, chunkZ);
            // Keep a few asynchronous full-chunk NBT reads pipelined rather
            // than waiting for one disk I/O completion before submitting the
            // next. Snapshot/cost debits remain sequential and main-thread.
            if (!task.cachedOnly) {
                for (int ahead = index + 1; ahead < Math.min(64, index + 5); ahead++) {
                    indexer.prefetchSaved(player.serverLevel(),
                            task.pageX * AtlasPage.CHUNKS_PER_SIDE + ahead % 8,
                            task.pageZ * AtlasPage.CHUNKS_PER_SIDE + ahead / 8);
                }
            }
            if (lookup.state() == WorldSurfaceIndex.State.PENDING) {
                // Never pay for a pending chunk or load/generate unseen land.
                ORDER.addLast(viewer);
                continue;
            }
            task.next++;
            var key = new SurveyLedger.ChunkKey(task.dimension, chunkX, chunkZ);
            if (lookup.state() == WorldSurfaceIndex.State.READY) {
                task.generated++;
                SurfaceSampler.SurfaceTile tile = lookup.snapshot().tile();
                data.reveal(entry, key, atlas);
                boolean color = entry.isColored(key);
                if (color) task.coloredChunks |= 1L << index;
                if (entry.paid(key)) {
                    task.surveyedChunks |= 1L << index;
                    task.painted++;
                    int x = index % 8, z = index / 8;
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int source = localZ * 16;
                        int destination = (z * 16 + localZ) * AtlasPage.SIDE_BLOCKS + x * 16;
                        if (color) System.arraycopy(tile.colors(), source, task.pixels, destination, 16);
                        else for (int lx = 0; lx < 16; lx++)
                            task.pixels[destination + lx] =
                                    SurfacePalette.graySample(tile.colors()[source + lx], tile.waterDepths()[source + lx]);
                        System.arraycopy(tile.heights(), source, task.heights, destination, 16);
                        System.arraycopy(tile.waterDepths(), source, task.waterDepths, destination, 16);
                        System.arraycopy(tile.features(), source, task.features, destination, 16);
                    }
                    for (SurfaceStructure structure : tile.structures()) {
                        if (task.structures.size() >= 64) break;
                        task.structures.add(structure);
                    }
                }
            }

            boolean done = task.next >= 64;
            // A partial snapshot forces GPU re-uploads and re-shades all
            // adjacent tiles every time (8/32/64). Send one complete tile,
            // keeping world sampling incremental and bounded on the server.
            if (done) {
                PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                        task.atlasId, task.dimension, task.pageX, task.pageZ,
                        task.pixels.clone(), task.features.clone(),
                        task.painted == 0 ? new short[0] : task.heights.clone(),
                        task.painted == 0 ? new byte[0] : task.waterDepths.clone(), task.surveyedChunks, task.coloredChunks,
                        entry.state().inkUnits(), entry.state().pigmentUnits(),
                        entry.state().glowUnits(), entry.state().compassInstalled(),
                        true, done, task.cachedOnly, task.next, task.generated, task.painted,
                        entry.anchorDimension(), entry.anchorX(), entry.anchorZ(), entry.state().pages(),
                        entry.sheetSideBlocks(), entry.surveyedCount(), entry.coloredCount(),
                        entry.markersOnPage(task.dimension, task.pageX, task.pageZ),
                        List.copyOf(task.structures)));
            }
            if (done) {
                LOG.debug("Cartograph real surface survey completed: dimension={} page=({}, {}) " +
                                "generated_FULL={}/64 painted={}/64 ink_units={} capacity={}",
                        task.dimension, task.pageX, task.pageZ,
                        task.generated, task.painted, entry.state().inkUnits(),
                        entry.state().pages());
                if (task.generated > 0 && task.painted == 0) {
                    long now = player.serverLevel().getGameTime();
                    Long prior = LAST_DIAGNOSTIC.get(viewer);
                    if (prior == null || now - prior >= 1200) {
                        LOG.info("Cartograph did not paint generated blocks: dimension={} " +
                                        "page=({}, {}) full_chunks={} ink_unlocked={} ink_units={} " +
                                        "required_per_chunk={} bought_pages={}",
                                task.dimension, task.pageX, task.pageZ, task.generated,
                                entry.state().inkEnabled(), entry.state().inkUnits(),
                                CartographConfig.cost(0), entry.state().pages());
                        LAST_DIAGNOSTIC.put(viewer, now);
                    }
                }
                PENDING.remove(viewer);
            } else ORDER.addLast(viewer);
        }
    }

    private static final class Pending {
        final String atlasId;
        final int pageX, pageZ;
        final String dimension;
        final byte[] pixels = new byte[MapNetwork.Page.PIXELS];
        final byte[] features = new byte[MapNetwork.Page.PIXELS];
        final short[] heights = new short[MapNetwork.Page.PIXELS];
        final byte[] waterDepths = new byte[MapNetwork.Page.PIXELS];
        final java.util.List<SurfaceStructure> structures = new java.util.ArrayList<>();
        int next, generated, painted;
        long surveyedChunks, coloredChunks;
        final boolean cachedOnly;
        Pending(String atlasId, int pageX, int pageZ, String dimension, boolean cachedOnly) {
            this.atlasId = atlasId; this.pageX = pageX; this.pageZ = pageZ; this.dimension = dimension;
            this.cachedOnly = cachedOnly;
            Arrays.fill(heights, TerrainRelief.UNKNOWN_HEIGHT);
        }
    }
}
