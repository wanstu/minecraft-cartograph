package com.wanstu.cartographv3;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Budgeted live updater for map tiles.
 *
 * A player only has watchers while the map screen is open. Watched keys are
 * capped and refreshed round-robin at a small budget instead of rescanning
 * every watched loaded chunk every server tick.
 */
final class LiveSurfaceUpdates {
    private static final Logger LOG = LoggerFactory.getLogger("cartograph.v3.live");

    private static final int MAX_WATCHED_PER_PLAYER = 512;
    private static final int REFRESH_TARGET_PER_INTERVAL = 8;
    private static final int MAX_REFRESH_PER_PLAYER_PER_TICK = 2;
    private static final int MAX_SCAN_ATTEMPTS_PER_PLAYER_PER_TICK = 24;
    private static final double MAX_REFRESH_CREDIT = 2.0;

    private static final class WatchState {
        final LinkedHashMap<ChunkKey, Long> watched = new LinkedHashMap<>();
        final Set<ChunkKey> refreshing = new HashSet<>();
        int cursor;
        int intervalTicks = 10;
        double refreshCredit = 1.0;
    }

    private static final Map<UUID, WatchState> WATCHERS = new HashMap<>();
    private static long tickCounter;
    private static long changedPushes;

    private LiveSurfaceUpdates() {}

    static void open(ServerPlayer player, int intervalTicks) {
        WatchState state = WATCHERS.computeIfAbsent(player.getUUID(), ignored -> new WatchState());
        state.intervalTicks = Math.max(5, Math.min(100, intervalTicks));
        state.refreshCredit = Math.max(1.0, Math.min(
                MAX_REFRESH_CREDIT, state.refreshCredit));
    }

    static void close(ServerPlayer player) {
        WATCHERS.remove(player.getUUID());
    }

    static void watch(ServerPlayer player, ChunkKey key) {
        WatchState state = WATCHERS.get(player.getUUID());
        if (state == null) return;

        // Refresh recency without keeping an unbounded history.
        state.watched.remove(key);
        state.watched.put(key, MapGameAccess.level(player).getGameTime());

        while (state.watched.size() > MAX_WATCHED_PER_PLAYER) {
            Iterator<ChunkKey> it = state.watched.keySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
            if (state.cursor > 0) state.cursor--;
        }
    }

    static void tick(MinecraftServer server) {
        tickCounter++;
        WATCHERS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

        for (var watcher : WATCHERS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(watcher.getKey());
            if (player == null) continue;

            WatchState state = watcher.getValue();
            if (state.watched.isEmpty()) continue;

            // Preserve the old average target (8 refreshes per configured
            // interval) but spread the work over server ticks. This removes
            // the 8-chunk burst that could visibly stall TNT/entity AI.
            state.refreshCredit = Math.min(
                    MAX_REFRESH_CREDIT,
                    state.refreshCredit
                            + REFRESH_TARGET_PER_INTERVAL
                                    / (double) state.intervalTicks);
            int refreshBudget = Math.min(
                    MAX_REFRESH_PER_PLAYER_PER_TICK,
                    (int) Math.floor(state.refreshCredit));
            if (refreshBudget <= 0) continue;

            ServerLevel level = MapGameAccess.level(player);
            String dimension = MapGameAccess.dimension(level);
            List<ChunkKey> keys = new ArrayList<>(state.watched.keySet());

            int refreshed = 0;
            int attempts = 0;
            while (refreshed < refreshBudget
                    && state.refreshing.size() < MAX_REFRESH_PER_PLAYER_PER_TICK
                    && attempts < Math.min(
                            MAX_SCAN_ATTEMPTS_PER_PLAYER_PER_TICK,
                            keys.size())) {
                if (state.cursor >= keys.size()) state.cursor = 0;
                ChunkKey key = keys.get(state.cursor++);
                attempts++;

                if (!key.dimension().equals(dimension)) continue;
                if (state.refreshing.contains(key)) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                if (chunk == null) continue;

                refreshed++;
                state.refreshCredit = Math.max(0.0, state.refreshCredit - 1.0);
                state.refreshing.add(key);
                SurfaceService.refreshLoaded(level, key.x(), key.z()).whenComplete((change, error) -> {
                    state.refreshing.remove(key);
                    if (error != null || WATCHERS.get(player.getUUID()) != state
                            || !state.watched.containsKey(key)
                            || MapGameAccess.level(player) != level) return;
                    change.ifPresent(changed -> {
                        MapProtocol.pushChanged(player, changed);
                        changedPushes++;
                        if (changedPushes == 1 || changedPushes % 256 == 0) {
                            LOG.debug(
                                    "V3_LIVE_PUSH count={} dim={} chunk={},{} fingerprint={}",
                                    changedPushes, key.dimension(), key.x(), key.z(),
                                    Long.toUnsignedString(changed.fingerprint64()));
                        }
                    });
                });
            }
        }
    }

    static void clear() {
        WATCHERS.clear();
        tickCounter = 0L;
        changedPushes = 0L;
    }
}
