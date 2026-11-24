package com.wanstu.cartographv3;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Real mutations enqueue bounded work; an unchanged watched chunk is never polled. */
final class LiveSurfaceUpdates {
    private static final int MAX_WATCHED_PER_PLAYER = 512;
    private static final int MAX_SCAN_ATTEMPTS_PER_PLAYER_PER_TICK = 24;
    private static final int MAX_REFRESH_PER_PLAYER_PER_TICK = 2;
    private static final int MUTATION_DEBOUNCE_TICKS = 3;
    private static final class WatchState {
        final ServerLevel level;
        final LinkedHashMap<ChunkKey, Long> watched = new LinkedHashMap<>();
        final LinkedHashMap<ChunkKey, Long> dirty = new LinkedHashMap<>();
        final LinkedHashMap<ChunkKey, Long> loading = new LinkedHashMap<>();
        final Set<ChunkKey> refreshing = new HashSet<>();
        int intervalTicks = 10;
        double refreshCredit = 1;
        WatchState(ServerLevel level) { this.level = level; }
    }
    private static final Map<UUID, WatchState> WATCHERS = new HashMap<>();
    private static volatile boolean active;
    private static long tickCounter;
    private static long refreshStarts;
    private static long mutationEnqueues;

    private LiveSurfaceUpdates() {}
    static boolean active() { return active; }

    static void open(ServerPlayer player, int intervalTicks) {
        ServerLevel level = MapGameAccess.level(player);
        WatchState state = WATCHERS.get(player.getUUID());
        if (state == null || state.level != level) {
            state = new WatchState(level);
            WATCHERS.put(player.getUUID(), state);
        }
        state.intervalTicks = Math.max(5, Math.min(100, intervalTicks));
        active = true;
    }

    static void close(ServerPlayer player) {
        WATCHERS.remove(player.getUUID());
        active = !WATCHERS.isEmpty();
    }

    static void watch(ServerPlayer player, ChunkKey key) {
        WatchState state = WATCHERS.get(player.getUUID());
        if (state == null || state.level.getChunkSource().getChunkNow(key.x(), key.z()) == null) return;
        boolean first = state.watched.remove(key) == null;
        state.watched.put(key, tickCounter);
        // One verification catches edits made while the frontend was closed.
        if (first) state.dirty.putIfAbsent(key, tickCounter + MUTATION_DEBOUNCE_TICKS);
        while (state.watched.size() > MAX_WATCHED_PER_PLAYER) {
            Iterator<ChunkKey> it = state.watched.keySet().iterator();
            ChunkKey evicted = it.next();
            it.remove();
            state.dirty.remove(evicted);
        }
    }

    static void changed(ServerLevel level, ChunkPos pos) {
        if (!active) return;
        ChunkKey key = new ChunkKey(MapGameAccess.dimension(level),
                MapGameAccess.chunkX(pos), MapGameAccess.chunkZ(pos));
        for (var watcher : WATCHERS.entrySet()) {
            WatchState state = watcher.getValue();
            if (state.level == level) {
                // An actual load/edit/save can introduce a loaded chunk after the viewport request.
                if (!state.watched.containsKey(key)) {
                    ServerPlayer player = level.getServer().getPlayerList().getPlayer(watcher.getKey());
                    if (player == null) continue;
                    watch(player, key);
                    if (!state.watched.containsKey(key)) {
                        // Load events can precede FULL registration. Retry only these explicit candidates.
                        if (state.loading.size() < MAX_WATCHED_PER_PLAYER)
                            state.loading.putIfAbsent(key, tickCounter + 200);
                        continue;
                    }
                }
                // Never postpone indefinitely when a fluid or machine keeps changing.
                if (state.dirty.putIfAbsent(key, tickCounter + MUTATION_DEBOUNCE_TICKS) == null)
                    mutationEnqueues++;
            }
        }
    }

    static void tick(MinecraftServer server) {
        tickCounter++;
        WATCHERS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        active = !WATCHERS.isEmpty();
        for (var watcher : WATCHERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(watcher.getKey());
            WatchState state = watcher.getValue();
            if (player == null || state.level != MapGameAccess.level(player)) continue;
            int loadingAttempts = 0;
            var loads = state.loading.entrySet().iterator();
            while (loads.hasNext() && loadingAttempts++ < MAX_SCAN_ATTEMPTS_PER_PLAYER_PER_TICK) {
                var candidate = loads.next();
                ChunkKey key = candidate.getKey();
                if (candidate.getValue() <= tickCounter) { loads.remove(); continue; }
                if (state.level.getChunkSource().getChunkNow(key.x(), key.z()) != null) {
                    loads.remove();
                    watch(player, key);
                }
            }
            if (state.dirty.isEmpty()) continue;
            state.refreshCredit = Math.min(2, state.refreshCredit + 8.0 / state.intervalTicks);
            int budget = Math.min(MAX_REFRESH_PER_PLAYER_PER_TICK, (int) state.refreshCredit);
            int attempted = 0, refreshed = 0;
            var iterator = state.dirty.entrySet().iterator();
            while (iterator.hasNext() && refreshed < budget
                    && state.refreshing.size() < MAX_REFRESH_PER_PLAYER_PER_TICK
                    && attempted++ < MAX_SCAN_ATTEMPTS_PER_PLAYER_PER_TICK) {
                var entry = iterator.next();
                ChunkKey key = entry.getKey();
                if (entry.getValue() > tickCounter || state.refreshing.contains(key)) continue;
                iterator.remove();
                // A later chunk-load event will enqueue it again. Never force a load.
                if (state.level.getChunkSource().getChunkNow(key.x(), key.z()) == null) continue;
                refreshed++;
                refreshStarts++;
                state.refreshCredit--;
                state.refreshing.add(key);
                SurfaceService.refreshLoaded(state.level, key.x(), key.z()).whenComplete((change, error) -> {
                    state.refreshing.remove(key);
                    if (error != null) {
                        if (WATCHERS.get(player.getUUID()) == state && state.watched.containsKey(key))
                            state.dirty.putIfAbsent(key, tickCounter + 20);
                    } else {
                        // Coalesced captures may serve several viewers. Broadcast the one changed result.
                        change.ifPresent(changed -> {
                            for (var recipient : WATCHERS.entrySet()) {
                                WatchState watching = recipient.getValue();
                                if (watching.level != state.level || !watching.watched.containsKey(key)) continue;
                                ServerPlayer target = server.getPlayerList().getPlayer(recipient.getKey());
                                if (target != null && MapGameAccess.level(target) == state.level)
                                    MapProtocol.pushChanged(target, changed);
                            }
                        });
                    }
                });
            }
        }
    }

    static void clear() {
        WATCHERS.clear();
        active = false;
        tickCounter = refreshStarts = mutationEnqueues = 0;
    }
}
