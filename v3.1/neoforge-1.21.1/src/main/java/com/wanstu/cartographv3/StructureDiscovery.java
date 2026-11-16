package com.wanstu.cartographv3;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class StructureDiscovery {
    private record Key(String worldRoot, String dimension, int x, int z) {}

    private static final ConcurrentHashMap<Key, List<MapProtocol.StructureHit>> CACHE =
            new ConcurrentHashMap<>();
    private static final Set<Key> STALE = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<
            Key, CompletableFuture<List<MapProtocol.StructureHit>>> PENDING =
            new ConcurrentHashMap<>();

    private static final ExecutorService DISK = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "cartograph-v3-structure-scan");
        thread.setDaemon(true);
        return thread;
    });

    private StructureDiscovery() {}

    static CompletableFuture<List<MapProtocol.StructureHit>> request(
            ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Structure request must start on server thread");

        Key key = key(level, chunkX, chunkZ);
        List<MapProtocol.StructureHit> cached = CACHE.get(key);
        if (cached != null && !STALE.contains(key)) {
            return CompletableFuture.completedFuture(cached);
        }

        LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (loaded != null) {
            List<MapProtocol.StructureHit> result = fromLoaded(level, loaded);
            CACHE.put(key, result);
            STALE.remove(key);
            return CompletableFuture.completedFuture(result);
        }

        CompletableFuture<List<MapProtocol.StructureHit>> existing = PENDING.get(key);
        if (existing != null) return existing;

        Path region = regionDirectory(level);
        CompletableFuture<List<MapProtocol.StructureHit>> future = new CompletableFuture<>();
        CompletableFuture<List<MapProtocol.StructureHit>> raced =
                PENDING.putIfAbsent(key, future);
        if (raced != null) return raced;

        DISK.execute(() -> {
            try {
                List<MapProtocol.StructureHit> result =
                        fromSaved(region, chunkX, chunkZ);
                CACHE.put(key, result);
                STALE.remove(key);
                future.complete(result);
            } catch (IOException failure) {
                future.complete(List.of());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                PENDING.remove(key, future);
            }
        });
        return future;
    }

    static CompletableFuture<List<MapProtocol.StructureHit>> requestSaved(
            ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Saved structure request must start on server thread");

        Key key = key(level, chunkX, chunkZ);
        List<MapProtocol.StructureHit> cached = CACHE.get(key);
        if (cached != null && !STALE.contains(key)) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<List<MapProtocol.StructureHit>> existing = PENDING.get(key);
        if (existing != null) return existing;

        Path region = regionDirectory(level);
        CompletableFuture<List<MapProtocol.StructureHit>> future = new CompletableFuture<>();
        CompletableFuture<List<MapProtocol.StructureHit>> raced =
                PENDING.putIfAbsent(key, future);
        if (raced != null) return raced;

        DISK.execute(() -> {
            try {
                List<MapProtocol.StructureHit> result =
                        fromSaved(region, chunkX, chunkZ);
                CACHE.put(key, result);
                STALE.remove(key);
                future.complete(result);
            } catch (IOException failure) {
                future.complete(List.of());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                PENDING.remove(key, future);
            }
        });
        return future;
    }

    static void invalidate(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        STALE.add(key(level, pos.x, pos.z));
    }

    static void clear(MinecraftServer server) {
        String root = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize().toString();
        CACHE.keySet().removeIf(key -> key.worldRoot().equals(root));
        STALE.removeIf(key -> key.worldRoot().equals(root));
        PENDING.keySet().removeIf(key -> key.worldRoot().equals(root));
    }

    private static List<MapProtocol.StructureHit> fromLoaded(
            ServerLevel level, LevelChunk chunk) {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<MapProtocol.StructureHit> out = new ArrayList<>();

        chunk.getAllStarts().forEach((structure, start) -> {
            if (start == null || !start.isValid()) return;
            var key = registry.getKey(structure);
            if (key == null) return;

            var center = start.getBoundingBox().getCenter();
            var startChunk = start.getChunkPos();
            out.add(new MapProtocol.StructureHit(
                    key.toString(),
                    startChunk.x, startChunk.z,
                    center.getX(), center.getZ()));
        });
        return dedupe(out);
    }

    private static List<MapProtocol.StructureHit> fromSaved(
            Path regionDirectory, int chunkX, int chunkZ) throws IOException {
        Optional<DataInputStream> opened =
                RegionNbtReader.openChunk(regionDirectory, chunkX, chunkZ);
        if (opened.isEmpty()) return List.of();

        try (DataInputStream input = opened.get()) {
            CompoundTag nbt = NbtIo.read(input, NbtAccounter.create(16 * 1024 * 1024));
            String status = nbt.getString("Status");
            if (!("full".equals(status) || "minecraft:full".equals(status))) return List.of();

            CompoundTag structures = nbt.contains("structures", Tag.TAG_COMPOUND)
                    ? nbt.getCompound("structures")
                    : nbt.getCompound("Structures");
            CompoundTag starts = structures.contains("starts", Tag.TAG_COMPOUND)
                    ? structures.getCompound("starts")
                    : structures.getCompound("Starts");
            if (starts.isEmpty()) return List.of();

            List<MapProtocol.StructureHit> out = new ArrayList<>();
            for (String key : starts.getAllKeys()) {
                CompoundTag start = starts.getCompound(key);
                String id = start.getString("id");
                if (id == null || id.isBlank()
                        || "INVALID".equalsIgnoreCase(id)
                        || "minecraft:empty".equals(id)) {
                    continue;
                }

                int startChunkX = start.contains("ChunkX", Tag.TAG_INT)
                        ? start.getInt("ChunkX") : chunkX;
                int startChunkZ = start.contains("ChunkZ", Tag.TAG_INT)
                        ? start.getInt("ChunkZ") : chunkZ;

                int x = startChunkX * 16 + 8;
                int z = startChunkZ * 16 + 8;
                int[] bb = start.getIntArray("BB");
                if (bb.length >= 6) {
                    x = bb[0] + (bb[3] - bb[0]) / 2;
                    z = bb[2] + (bb[5] - bb[2]) / 2;
                }

                String structureId = key.contains(":") ? key : id;
                if (structureId == null || structureId.isBlank()
                        || "INVALID".equalsIgnoreCase(structureId)) {
                    continue;
                }
                out.add(new MapProtocol.StructureHit(
                        structureId, startChunkX, startChunkZ, x, z));
            }
            return dedupe(out);
        }
    }

    private static List<MapProtocol.StructureHit> dedupe(
            List<MapProtocol.StructureHit> input) {
        LinkedHashMap<String, MapProtocol.StructureHit> out = new LinkedHashMap<>();
        for (MapProtocol.StructureHit hit : input) {
            String key = hit.structureId() + "|" + hit.startChunkX() + "|" + hit.startChunkZ();
            out.putIfAbsent(key, hit);
        }
        return List.copyOf(out.values());
    }

    private static Key key(ServerLevel level, int chunkX, int chunkZ) {
        String root = level.getServer().getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize().toString();
        return new Key(
                root, level.dimension().location().toString(), chunkX, chunkZ);
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
