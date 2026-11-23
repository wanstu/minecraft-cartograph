package com.wanstu.cartographv3;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class StructureDiscovery {
    private record Key(String worldRoot, String dimension, int x, int z) {}

    private static final java.util.Map<Key, List<MapProtocol.StructureHit>> CACHE =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<Key, List<MapProtocol.StructureHit>>(256, .75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<Key, List<MapProtocol.StructureHit>> eldest) {
                            if (size() <= 8192) return false;
                            STALE.remove(eldest.getKey());
                            return true;
                        }
                    });
    private static final Set<Key> STALE = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<
            Key, CompletableFuture<List<MapProtocol.StructureHit>>> PENDING =
            new ConcurrentHashMap<>();

    private StructureDiscovery() {}

    static CompletableFuture<List<MapProtocol.StructureHit>> request(
            ServerLevel level, int chunkX, int chunkZ,
            boolean watchLive, SavedChunkRead saved) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Structure request must start on server thread");

        Key key = key(level, chunkX, chunkZ);
        List<MapProtocol.StructureHit> cached = CACHE.get(key);
        if (cached != null && !STALE.contains(key)) {
            return CompletableFuture.completedFuture(cached);
        }

        if (watchLive) {
            LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (loaded != null) {
                List<MapProtocol.StructureHit> result = fromLoaded(level, loaded);
                CACHE.put(key, result);
                STALE.remove(key);
                return CompletableFuture.completedFuture(result);
            }
        }

        CompletableFuture<List<MapProtocol.StructureHit>> existing = PENDING.get(key);
        if (existing != null) return existing;

        CompletableFuture<List<MapProtocol.StructureHit>> future = new CompletableFuture<>();
        CompletableFuture<List<MapProtocol.StructureHit>> raced =
                PENDING.putIfAbsent(key, future);
        if (raced != null) return raced;

        saved.whenRead((nbt, error) -> {
            try {
                if (error instanceof IOException) {
                    future.complete(List.of());
                    return;
                }
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }
                List<MapProtocol.StructureHit> result = nbt
                        .map(tag -> fromSaved(tag, chunkX, chunkZ)).orElseGet(List::of);
                CACHE.put(key, result);
                STALE.remove(key);
                future.complete(result);
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                PENDING.remove(key, future);
            }
        });
        return future;
    }

    static void invalidate(ServerLevel level, ChunkPos pos) {
        Key key = key(level, MapGameAccess.chunkX(pos), MapGameAccess.chunkZ(pos));
        if (CACHE.containsKey(key) || PENDING.containsKey(key)) STALE.add(key);
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
        var registry = MapGameAccess.registry(level.registryAccess(), Registries.STRUCTURE);
        List<MapProtocol.StructureHit> out = new ArrayList<>();

        chunk.getAllStarts().forEach((structure, start) -> {
            if (start == null || !start.isValid()) return;
            var key = registry.getKey(structure);
            if (key == null) return;

            var center = start.getBoundingBox().getCenter();
            var startChunk = start.getChunkPos();
            out.add(new MapProtocol.StructureHit(
                    key.toString(),
                    MapGameAccess.chunkX(startChunk), MapGameAccess.chunkZ(startChunk),
                    center.getX(), center.getZ()));
        });
        return dedupe(out);
    }

    static List<MapProtocol.StructureHit> fromSaved(CompoundTag nbt, int chunkX, int chunkZ) {
        CompoundTag structures = nbt.get("structures") instanceof CompoundTag
                ? MapNbt.compound(nbt, "structures")
                : MapNbt.compound(nbt, "Structures");
        CompoundTag starts = structures.get("starts") instanceof CompoundTag
                ? MapNbt.compound(structures, "starts")
                : MapNbt.compound(structures, "Starts");
        if (starts.isEmpty()) return List.of();

        List<MapProtocol.StructureHit> out = new ArrayList<>();
        for (String key : MapNbt.keys(starts)) {
            CompoundTag start = MapNbt.compound(starts, key);
            String id = MapNbt.string(start, "id");
            if (id == null || id.isBlank()
                    || "INVALID".equalsIgnoreCase(id)
                    || "minecraft:empty".equals(id)) {
                continue;
            }

            int startChunkX = start.get("ChunkX") instanceof IntTag
                    ? MapNbt.intValue(start, "ChunkX") : chunkX;
            int startChunkZ = start.get("ChunkZ") instanceof IntTag
                    ? MapNbt.intValue(start, "ChunkZ") : chunkZ;

            int x = startChunkX * 16 + 8;
            int z = startChunkZ * 16 + 8;
            int[] bb = MapNbt.ints(start, "BB");
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
                root, MapGameAccess.dimension(level), chunkX, chunkZ);
    }
}
