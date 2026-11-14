package com.wanstu.cartographv3;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class StructureDiscovery {
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

        LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (loaded != null) {
            return CompletableFuture.completedFuture(fromLoaded(level, loaded));
        }

        Path region = regionDirectory(level);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fromSaved(region, chunkX, chunkZ);
            } catch (IOException ignored) {
                return List.of();
            }
        }, DISK);
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
