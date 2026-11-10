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
 * Two sampled chunks per server tick across all viewers. Does not load or
 * generate any chunks. A viewer may keep only one pending page at a time;
 * requests cannot flood a server with scans, and only covered pages are
 * eligible for a response.
 */
public final class AtlasMapServer {
    private static final Logger LOG = LoggerFactory.getLogger("cartograph.survey");
    private static final int MAX_WORK_PER_TICK = 2;
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
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(CartographMod.ATLAS.get())
                        && stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                        AtlasState.blank()).id().isBlank()) return stack;
            }
            for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
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
                    new short[0], new byte[0], 0L,
                    entry.state().inkUnits(), entry.state().pigmentUnits(),
                    entry.state().glowUnits(), entry.state().compassInstalled(),
                    false, true, 0, 0, 0,
                    entry.anchorX(), entry.anchorZ(), entry.state().pages(),
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
                request.pageX(), request.pageZ(), dim);
        PENDING.put(player.getUUID(), next);
        ORDER.addLast(player.getUUID());
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (++ticks % 600 == 0)
            LAST_DIAGNOSTIC.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        // One slot per chunk, not one per player. The previous implementation
        // scanned only ONE chunk per tick for a sole viewer despite budget=2.
        // Rotate viewers fairly and avoid holding any FileChannel between ticks.
        for (int step = 0; step < MAX_WORK_PER_TICK && !ORDER.isEmpty(); step++) {
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
                    indexer.inspect(player.serverLevel(), chunkX, chunkZ);
            if (lookup.state() == WorldSurfaceIndex.State.PENDING) {
                // Never count or debit a chunk before the background disk
                // scan completes. Polling here is constant-time, no NBT I/O.
                ORDER.addLast(viewer);
                continue;
            }
            // Early prefetch avoids a disk-read stall for each sequential
            // chunk, while at most one extra loaded snapshot is sampled.
            if (index + 1 < 64) {
                int ahead = index + 1;
                indexer.inspect(player.serverLevel(),
                        task.pageX * AtlasPage.CHUNKS_PER_SIDE + ahead % 8,
                        task.pageZ * AtlasPage.CHUNKS_PER_SIDE + ahead / 8);
            }
            task.next++;
            var key = new SurveyLedger.ChunkKey(task.dimension, chunkX, chunkZ);
            if (lookup.state() == WorldSurfaceIndex.State.READY) {
                task.generated++;
                SurfaceSampler.SurfaceTile tile = lookup.snapshot().tile();
                data.sample(entry, key, tile.colors(), tile.features(), atlas);
                byte[] savedColors = entry.getPixels(key);
                if (savedColors != null && entry.paid(key)) {
                    task.painted++;
                    int x = index % 8, z = index / 8;
                    byte[] features = entry.getFeatures(key);
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int source = localZ * 16;
                        int destination = (z * 16 + localZ) * AtlasPage.SIDE_BLOCKS + x * 16;
                        System.arraycopy(savedColors, source, task.pixels, destination, 16);
                        System.arraycopy(tile.heights(), source, task.heights, destination, 16);
                        System.arraycopy(tile.waterDepths(), source, task.waterDepths, destination, 16);
                        if (features != null && features.length == 256)
                            System.arraycopy(features, source, task.features, destination, 16);
                    }
                    for (SurfaceStructure structure : tile.structures()) {
                        if (task.structures.size() >= 64) break;
                        task.structures.add(structure);
                    }
                    if (entry.isColored(key)) task.coloredChunks |= 1L << index;
                }
            }

            boolean done = task.next >= 64;
            if (task.next == 8 || task.next == 32 || done) {
                PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                        task.atlasId, task.dimension, task.pageX, task.pageZ,
                        task.pixels.clone(), task.features.clone(),
                        task.painted == 0 ? new short[0] : task.heights.clone(),
                        task.painted == 0 ? new byte[0] : task.waterDepths.clone(), task.coloredChunks,
                        entry.state().inkUnits(), entry.state().pigmentUnits(),
                        entry.state().glowUnits(), entry.state().compassInstalled(),
                        true, done, task.next, task.generated, task.painted,
                        entry.anchorX(), entry.anchorZ(), entry.state().pages(),
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
                                CartographConfig.inkCostUnits(), entry.state().pages());
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
        long coloredChunks;
        Pending(String atlasId, int pageX, int pageZ, String dimension) {
            this.atlasId = atlasId; this.pageX = pageX; this.pageZ = pageZ; this.dimension = dimension;
            Arrays.fill(heights, TerrainRelief.UNKNOWN_HEIGHT);
        }
    }
}
