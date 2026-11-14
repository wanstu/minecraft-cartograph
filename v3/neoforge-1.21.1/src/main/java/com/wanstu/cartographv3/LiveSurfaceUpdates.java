package com.wanstu.cartographv3;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Correctness-first live map updater.
 *
 * Only chunks already requested by a map client are watched. Every watched
 * chunk that is currently loaded by Minecraft is resampled directly each
 * server tick. SurfaceService compares the new 16x16 raster with the previous
 * fingerprint; unchanged chunks produce no network payload.
 *
 * This is intentionally correctness-first. Performance throttling belongs to
 * a later phase after map behavior is accepted.
 */
final class LiveSurfaceUpdates {
    private static final Logger LOG = LoggerFactory.getLogger("cartograph.v3.live");
    private static final Map<UUID, Set<ChunkKey>> WATCHERS = new HashMap<>();
    private LiveSurfaceUpdates() {}

    static void watch(ServerPlayer player, ChunkKey key) {
        WATCHERS.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>()).add(key);
    }

    static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        WATCHERS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            Map<ChunkKey, List<ServerPlayer>> active = new HashMap<>();

            for (var watched : WATCHERS.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(watched.getKey());
                if (player == null || player.serverLevel() != level) continue;
                for (ChunkKey key : watched.getValue()) {
                    if (key.dimension().equals(dimension))
                        active.computeIfAbsent(key, ignored -> new ArrayList<>()).add(player);
                }
            }

            for (var entry : active.entrySet()) {
                ChunkKey key = entry.getKey();
                LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                if (chunk == null) continue;

                // Correctness first: do not depend on Minecraft's dirty/unsaved flag.
                // Every watched loaded chunk is fingerprinted each tick. SurfaceService
                // suppresses all work when the 16x16 rendered surface is unchanged.
                SurfaceService.refreshLoaded(level, key.x(), key.z()).ifPresent(changed -> {
                    int viewers = 0;
                    for (ServerPlayer player : entry.getValue()) {
                        MapProtocol.pushChanged(player, changed);
                        viewers++;
                    }
                    LOG.info("V3_LIVE_PUSH dim={} chunk={},{} viewers={} fingerprint={}",
                            key.dimension(), key.x(), key.z(), viewers,
                            Long.toUnsignedString(changed.fingerprint64()));
                });
            }
        }
    }

    static void clear() {
        WATCHERS.clear();
    }
}
