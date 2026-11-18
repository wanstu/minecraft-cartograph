package com.wanstu.cartographv3;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class SurfaceService {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.surface");
    private static final java.util.concurrent.atomic.AtomicInteger DISK_HITS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LOADED_HITS =
            new java.util.concurrent.atomic.AtomicInteger();
    private record Key(String worldRoot, String dimension, int x, int z) {}

    private static final ConcurrentHashMap<Key, SurfaceTile> CACHE = new ConcurrentHashMap<>();
    private static final Set<Key> DISK_STALE = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Key, CompletableFuture<Optional<SurfaceTile>>> PENDING =
            new ConcurrentHashMap<>();

    private SurfaceService() {}

    static boolean requiresLoadedCapture(
            ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Surface request cost check must run on server thread");

        Key key = key(level, chunkX, chunkZ);
        SurfaceTile cached = CACHE.get(key);
        if (cached != null && !DISK_STALE.contains(key)) return false;

        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    static CompletableFuture<Optional<SurfaceTile>> request(
            ServerLevel level, int chunkX, int chunkZ,
            boolean watchLive, SavedChunkRead saved) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Surface request must start on server thread");

        String dimension = MapGameAccess.dimension(level);
        Key key = key(level, chunkX, chunkZ);

        // Revalidation should be cheap: if this tile is already in the server
        // cache and the chunk has not been saved since, do not rescan a loaded
        // 16x16 surface on the server thread. LiveSurfaceUpdates keeps active
        // loaded tiles fresh while the map is open.
        SurfaceTile cached = CACHE.get(key);
        if (cached != null && !DISK_STALE.contains(key))
            return CompletableFuture.completedFuture(Optional.of(cached));

        if (watchLive) {
            Optional<SurfaceTile> loaded = LoadedSurfaceReader.capture(level, chunkX, chunkZ);
            if (loaded.isPresent()) {
                CACHE.put(key, loaded.get());
                DISK_STALE.remove(key);
                int count = LOADED_HITS.incrementAndGet();
                if (count == 1 || count % 64 == 0)
                    LOG.info("V3_LOADED_DIRECT_READ count={} dim={} example={},{}",
                            count, dimension, chunkX, chunkZ);
                return CompletableFuture.completedFuture(loaded);
            }
        }

        CompletableFuture<Optional<SurfaceTile>> existing = PENDING.get(key);
        if (existing != null) return existing;

        int minY = MapGameAccess.minY(level);
        int height = level.getHeight();
        var biomeRegistry = MapGameAccess.registry(level.registryAccess(), Registries.BIOME);

        CompletableFuture<Optional<SurfaceTile>> future = new CompletableFuture<>();
        CompletableFuture<Optional<SurfaceTile>> raced = PENDING.putIfAbsent(key, future);
        if (raced != null) return raced;

        saved.whenRead((nbt, error) -> {
            try {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }
                Optional<SurfaceTile> result = nbt.flatMap(tag -> SavedSurfaceReader.fromSaved(
                        tag, dimension, chunkX, chunkZ, minY, height, biomeRegistry));
                result.ifPresent(tile -> {
                    CACHE.put(key, tile);
                    int count = DISK_HITS.incrementAndGet();
                    if (count == 1 || count % 64 == 0)
                        LOG.info("V3_FULL_DIRECT_READ count={} dim={} example={},{}",
                                count, dimension, chunkX, chunkZ);
                });
                DISK_STALE.remove(key);
                future.complete(result);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                PENDING.remove(key, future);
            }
        });
        return future;
    }

    static Optional<SurfaceTile> refreshLoaded(
            ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Live surface refresh must run on server thread");

        Optional<SurfaceTile> current = LoadedSurfaceReader.capture(level, chunkX, chunkZ);
        if (current.isEmpty()) return Optional.empty();

        Key key = key(level, chunkX, chunkZ);
        SurfaceTile next = current.get();
        SurfaceTile previous = CACHE.put(key, next);
        DISK_STALE.remove(key);
        if (previous != null && previous.fingerprint64() == next.fingerprint64())
            return Optional.empty();
        return Optional.of(next);
    }

    static void invalidate(ServerLevel level, ChunkPos pos) {
        // Save means the on-disk copy may have changed. Keep the in-memory
        // fingerprint so live comparison remains meaningful; only force the
        // next *unloaded* disk request to bypass CACHE.
        DISK_STALE.add(key(level, MapGameAccess.chunkX(pos), MapGameAccess.chunkZ(pos)));
    }

    static void clear(MinecraftServer server) {
        String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath()
                .normalize().toString();
        CACHE.keySet().removeIf(key -> key.worldRoot().equals(root));
        DISK_STALE.removeIf(key -> key.worldRoot().equals(root));
        PENDING.keySet().removeIf(key -> key.worldRoot().equals(root));
    }

    private static Key key(ServerLevel level, int chunkX, int chunkZ) {
        String root = level.getServer().getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize().toString();
        return new Key(root, MapGameAccess.dimension(level), chunkX, chunkZ);
    }
}
