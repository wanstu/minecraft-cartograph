package com.wanstu.cartographv3;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

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
    private static final ExecutorService DISK = Executors.newFixedThreadPool(8, new ThreadFactory() {
        private int count;
        @Override public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "cartograph-v3-mca-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    });

    private SurfaceService() {}

    static CompletableFuture<Optional<SurfaceTile>> request(
            ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Surface request must start on server thread");

        String dimension = level.dimension().location().toString();
        Optional<SurfaceTile> loaded = LoadedSurfaceReader.capture(level, chunkX, chunkZ);
        if (loaded.isPresent()) {
            Key key = key(level, chunkX, chunkZ);
            CACHE.put(key, loaded.get());
            DISK_STALE.remove(key);
            int count = LOADED_HITS.incrementAndGet();
            if (count == 1 || count % 64 == 0)
                LOG.info("V3_LOADED_DIRECT_READ count={} dim={} example={},{}",
                        count, dimension, chunkX, chunkZ);
            return CompletableFuture.completedFuture(loaded);
        }

        Key key = key(level, chunkX, chunkZ);
        SurfaceTile cached = CACHE.get(key);
        if (cached != null && !DISK_STALE.contains(key))
            return CompletableFuture.completedFuture(Optional.of(cached));

        CompletableFuture<Optional<SurfaceTile>> existing = PENDING.get(key);
        if (existing != null) return existing;

        Path region = regionDirectory(level);
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        CompletableFuture<Optional<SurfaceTile>> future = new CompletableFuture<>();
        CompletableFuture<Optional<SurfaceTile>> raced = PENDING.putIfAbsent(key, future);
        if (raced != null) return raced;

        DISK.execute(() -> {
            try {
                Optional<SurfaceTile> result = SavedSurfaceReader.read(
                        region, dimension, chunkX, chunkZ, minY, height, biomeRegistry);
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

    static void invalidate(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        // Save means the on-disk copy may have changed. Keep the in-memory
        // fingerprint so live comparison remains meaningful; only force the
        // next *unloaded* disk request to bypass CACHE.
        DISK_STALE.add(key(level, pos.x, pos.z));
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
        return new Key(root, level.dimension().location().toString(), chunkX, chunkZ);
    }

    private static Path regionDirectory(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        if (level.dimension() == Level.OVERWORLD) return root.resolve("region");
        if (level.dimension() == Level.NETHER) return root.resolve("DIM-1").resolve("region");
        if (level.dimension() == Level.END) return root.resolve("DIM1").resolve("region");

        var id = level.dimension().location();
        return root.resolve("dimensions").resolve(id.getNamespace())
                .resolve(id.getPath()).resolve("region");
    }
}
