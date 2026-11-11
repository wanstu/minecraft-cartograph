package com.wanstu.cartograph;

import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.AtlasLayers;
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
 * Bounded incremental multi-page service: up to four independent pages
 * per player can advance concurrently, under a shared main-thread budget.
 * Does not load or generate any chunks;
 * requests cannot flood a server with scans, and only covered pages are
 * eligible for a response.
 */
public final class AtlasMapServer {
    private static final Logger LOG = LoggerFactory.getLogger("cartograph.survey");
    // Keep main-thread terrain work bounded, but do not force 4-5 seconds
    // of minimum waiting for each map at ordinary map zoom.
    private static final int MAX_WORK_PER_TICK = 16;
    private static final long MAX_SURVEY_NANOS_PER_TICK = 4_000_000L;
    private static final int MAX_PAGES_PER_VIEWER = 4;
    private record PageKey(String dimension, int x, int z) {}
    private static final Map<UUID, Map<PageKey, Pending>> PENDING = new HashMap<>();
    private static final ArrayDeque<Pending> ORDER = new ArrayDeque<>();
    // Coalesce individual CHUNKS (16x16), never invalidate 64 neighboring
    // chunks merely because a player placed a single block.
    private record Changed(String dimension, int cx, int cz) {}
    private record ChangedPage(String dimension, int pageX, int pageZ) {}
    private static final Map<Changed, Integer> CHANGED = new HashMap<>();
    // Manual "refresh all surveyed" retains its full-page reconciliation,
    // because it may have updated many chunks on the same page.
    private static final Map<ChangedPage, Integer> WHOLE_PAGES = new HashMap<>();
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
        PacketDistributor.sendToPlayer(player, AtlasActions.performance(player));
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
        Map<PageKey, Pending> slots =
                PENDING.computeIfAbsent(player.getUUID(), ignore -> new HashMap<>());
        PageKey page = new PageKey(dim, request.pageX(), request.pageZ());
        if (slots.containsKey(page)) return;
        if (slots.size() >= MAX_PAGES_PER_VIEWER) return;
        Pending next = new Pending(player.getUUID(), entry.state().id(),
                request.pageX(), request.pageZ(), dim, request.cachedOnly());
        slots.put(page, next);
        ORDER.addLast(next);
    }

    static void markSurfaceChanged(net.minecraft.server.level.ServerLevel level, int cx, int cz) {
        CHANGED.put(new Changed(level.dimension().location().toString(), cx, cz), ticks + 4);
    }

    static void markWholePageChanged(net.minecraft.server.level.ServerLevel level, int pageX, int pageZ) {
        ChangedPage page = new ChangedPage(level.dimension().location().toString(), pageX, pageZ);
        WHOLE_PAGES.put(page, ticks + 4);
    }

    /** No resource debit. A 16x16 terrain patch is sent only for a chunk
     * already surveyed by that atlas, after its refreshed surface is ready.
     * All snapshotting happens on the logical server thread with hard budget.
     */
    private static void sendChangedPages(MinecraftServer server) {
        Iterator<Map.Entry<ChangedPage, Integer>> whole = WHOLE_PAGES.entrySet().iterator();
        int sent = 0;
        while (whole.hasNext() && sent < 8) {
            var e = whole.next();
            if (ticks < e.getValue()) continue;
            ChangedPage changed = e.getKey();
            whole.remove();
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (!viewer.serverLevel().dimension().location().toString().equals(changed.dimension())) continue;
                ItemStack atlas = findAtlas(viewer);
                if (atlas.isEmpty()) continue;
                AtlasWorldData.Entry entry = AtlasWorldData.get(viewer.serverLevel()).access(atlas);
                if (entry.isCovered(changed.dimension(), changed.pageX(), changed.pageZ()))
                    PacketDistributor.sendToPlayer(viewer,
                            new MapNetwork.InvalidatePage(entry.state().id(), changed.dimension(),
                                    changed.pageX(), changed.pageZ()));
            }
            sent++;
        }
        Iterator<Map.Entry<Changed, Integer>> it = CHANGED.entrySet().iterator();
        long deadline = System.nanoTime() + 2_000_000L;
        while (it.hasNext() && sent < 12 && System.nanoTime() < deadline) {
            var e = it.next();
            if (ticks < e.getValue()) continue;
            Changed changed = e.getKey();
            int px = Math.floorDiv(changed.cx(), 8), pz = Math.floorDiv(changed.cz(), 8);
            // A manual bulk refresh already queues the complete page.
            if (WHOLE_PAGES.containsKey(new ChangedPage(changed.dimension(), px, pz))) {
                it.remove();
                continue;
            }
            // Only inspect a changed tile while an atlas owner is present.
            // No chunk loads, no generated world; leaves queued disk parsing
            // under WorldSurfaceIndex's existing bounded async worker pool.
            net.minecraft.server.level.ServerLevel level = null;
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (!viewer.serverLevel().dimension().location().toString().equals(changed.dimension())) continue;
                ItemStack atlas = findAtlas(viewer);
                if (atlas.isEmpty()) continue;
                AtlasWorldData.Entry atlasData = AtlasWorldData.get(viewer.serverLevel()).access(atlas);
                if (atlasData.isCovered(changed.dimension(), px, pz)
                        && atlasData.paid(new SurveyLedger.ChunkKey(changed.dimension(), changed.cx(), changed.cz()))) {
                    level = viewer.serverLevel();
                    break;
                }
            }
            if (level == null) { it.remove(); continue; }
            WorldSurfaceIndex.Lookup updated =
                    WorldSurfaceIndex.of(level).inspect(level, changed.cx(), changed.cz());
            if (updated.state() == WorldSurfaceIndex.State.PENDING) continue;
            it.remove();
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (viewer.serverLevel() != level) continue;
                ItemStack atlas = findAtlas(viewer);
                if (atlas.isEmpty()) continue;
                AtlasWorldData.Entry atlasData = AtlasWorldData.get(level).access(atlas);
                if (!atlasData.isCovered(changed.dimension(), px, pz)) continue;
                var key = new SurveyLedger.ChunkKey(changed.dimension(), changed.cx(), changed.cz());
                AtlasLayers.Visibility visible = AtlasLayers.compose(
                        true, atlasData.paid(key), atlasData.isColored(key));
                if (visible != AtlasLayers.Visibility.GRAYSCALE
                        && visible != AtlasLayers.Visibility.COLOR) continue;
                if (updated.state() != WorldSurfaceIndex.State.READY) {
                    // Previously surveyed terrain was removed / is no longer
                    // a valid FULL chunk. Only this exceptional case falls
                    // back to complete page verification.
                    PacketDistributor.sendToPlayer(viewer,
                            new MapNetwork.InvalidatePage(atlasData.state().id(),
                                    changed.dimension(), px, pz));
                    continue;
                }
                SurfaceSampler.SurfaceTile tile = updated.snapshot().tile();
                byte[] pixels = tile.colors().clone();
                if (visible == AtlasLayers.Visibility.GRAYSCALE)
                    for (int i = 0; i < pixels.length; i++)
                        pixels[i] = SurfacePalette.graySample(tile.colors()[i], tile.waterDepths()[i]);
                PacketDistributor.sendToPlayer(viewer, new MapNetwork.TerrainPatch(
                        atlasData.state().id(), changed.dimension(), changed.cx(), changed.cz(),
                        pixels, tile.features().clone(), tile.heights().clone(),
                        tile.waterDepths().clone(),
                        List.copyOf(tile.structures().subList(0, Math.min(tile.structures().size(), 16)))));
            }
            sent++;
        }
    }

    private static void finish(Pending task) {
        Map<PageKey, Pending> perViewer = PENDING.get(task.viewer);
        if (perViewer != null) {
            perViewer.remove(new PageKey(task.dimension, task.pageX, task.pageZ));
            if (perViewer.isEmpty()) PENDING.remove(task.viewer);
        }
    }

    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        PENDING.clear();
        ORDER.clear();
        CHANGED.clear();
        WHOLE_PAGES.clear();
        LAST_DIAGNOSTIC.clear();
        ticks = 0;
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ticks++;
        sendChangedPages(server);
        if (ticks % 600 == 0)
            LAST_DIAGNOSTIC.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        // A handful of completed snapshots per tick makes initial 512x512
        // sheets practical, while a wall-clock budget protects game TPS.
        // Never hold region FileChannels on the main thread.
        long tickStart = System.nanoTime();
        for (int step = 0; step < MAX_WORK_PER_TICK && !ORDER.isEmpty(); step++) {
            if (step >= 2 && System.nanoTime() - tickStart >=
                    CartographConfig.mainThreadBudgetMs() * 1_000_000L) break;
            Pending task = ORDER.removeFirst();
            UUID viewer = task.viewer;
            Map<PageKey, Pending> existing = PENDING.get(viewer);
            if (existing == null || existing.get(
                    new PageKey(task.dimension, task.pageX, task.pageZ)) != task) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            if (player == null || !player.serverLevel().dimension()
                    .location().toString().equals(task.dimension)) {
                finish(task);
                continue;
            }
            ItemStack atlas = findAtlas(player, task.atlasId);
            if (atlas.isEmpty()) {
                finish(task);
                continue;
            }
            AtlasWorldData data = AtlasWorldData.get(player.serverLevel());
            AtlasWorldData.Entry entry = data.access(atlas);
            if (!entry.isCovered(task.dimension, task.pageX, task.pageZ)) {
                finish(task);
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
                for (int ahead = index + 1; ahead < Math.min(64, index + 13); ahead++) {
                    indexer.prefetchSaved(player.serverLevel(),
                            task.pageX * AtlasPage.CHUNKS_PER_SIDE + ahead % 8,
                            task.pageZ * AtlasPage.CHUNKS_PER_SIDE + ahead / 8);
                }
            }
            if (lookup.state() == WorldSurfaceIndex.State.PENDING) {
                // No mysterious cooldown: if disk parsing really is slow
                // the next log records the exact blocker and queue length.
                if (ticks - task.startedAtTick > 200 && ticks - task.lastDiagnosticTick >= 200) {
                    task.lastDiagnosticTick = ticks;
                    LOG.warn("Map tile still awaiting saved chunk: player={} tile=({}, {}) "
                                    + "scanned={}/64 disk_pending={} disk_queue={} surface_done={}",
                            viewer, task.pageX, task.pageZ, task.next,
                            indexer.pendingChunks(), indexer.diskQueueSize(), indexer.completedScans());
                }
                ORDER.addLast(task);
                continue;
            }
            task.next++;
            var key = new SurveyLedger.ChunkKey(task.dimension, chunkX, chunkZ);
            if (lookup.state() == WorldSurfaceIndex.State.READY) {
                task.generated++;
                SurfaceSampler.SurfaceTile tile = lookup.snapshot().tile();
                data.reveal(entry, key, atlas);
                // Paper permission was checked in enqueue/tick. Survey
                // and pigment remain independent world-aligned masks.
                boolean colorUnlocked = entry.isColored(key);
                if (colorUnlocked) task.coloredChunks |= 1L << index;
                AtlasLayers.Visibility visibility = AtlasLayers.compose(
                        true, entry.paid(key), colorUnlocked);
                if (visibility == AtlasLayers.Visibility.COLOR
                        || visibility == AtlasLayers.Visibility.GRAYSCALE) {
                    task.surveyedChunks |= 1L << index;
                    task.painted++;
                    int x = index % 8, z = index / 8;
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int source = localZ * 16;
                        int destination = (z * 16 + localZ) * AtlasPage.SIDE_BLOCKS + x * 16;
                        if (visibility == AtlasLayers.Visibility.COLOR)
                            System.arraycopy(tile.colors(), source, task.pixels, destination, 16);
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
            // Only publish a FINISHED page. 8/32-chunk updates caused
            // moving pale stripes when the camera pans into a newly scanned
            // area, even though the actual world and paint state were stable.
            // Server still scans in the background; old pages stay visible.
            boolean milestone = done;
            if (milestone) {
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
                finish(task);
            } else ORDER.addLast(task);
        }
    }

    private static final class Pending {
        final UUID viewer;
        final String atlasId;
        final int pageX, pageZ;
        final String dimension;
        final byte[] pixels = new byte[MapNetwork.Page.PIXELS];
        final byte[] features = new byte[MapNetwork.Page.PIXELS];
        final short[] heights = new short[MapNetwork.Page.PIXELS];
        final byte[] waterDepths = new byte[MapNetwork.Page.PIXELS];
        final java.util.List<SurfaceStructure> structures = new java.util.ArrayList<>();
        int next, generated, painted;
        int startedAtTick = ticks, lastDiagnosticTick = ticks;
        long surveyedChunks, coloredChunks;
        final boolean cachedOnly;
        Pending(UUID viewer, String atlasId, int pageX, int pageZ, String dimension, boolean cachedOnly) {
            this.viewer = viewer;
            this.atlasId = atlasId; this.pageX = pageX; this.pageZ = pageZ; this.dimension = dimension;
            this.cachedOnly = cachedOnly;
            Arrays.fill(heights, TerrainRelief.UNKNOWN_HEIGHT);
        }
    }
}
