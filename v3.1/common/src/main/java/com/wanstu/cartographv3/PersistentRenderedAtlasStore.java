package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/**
 * Persistent cache for fully rendered native-map atlases.
 *
 * World facts remain authoritative. This cache only stores presentation pixels
 * plus the source tile fingerprints that produced them, so a restored atlas can
 * be shown immediately and then incrementally validated as facts are restored.
 */
final class PersistentRenderedAtlasStore {
    private static final int MAGIC = 0x43544131; // CTA1
    private static final int VERSION = 1;
    private static final String ROOT = "cartograph-v31-rendered-atlas-v1";

    record Snapshot(long[] fingerprints, int[] pixels) {}
    private record IndexKey(UUID worldId, String dimension, long signature) {}

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v31-rendered-atlas-writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentHashMap<Path, PendingWrite> PENDING = new ConcurrentHashMap<>();
    private static final Set<Path> SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<IndexKey, Set<String>> REGION_INDEX =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IndexKey, CompletableFuture<Void>> INDEX_LOADS =
            new ConcurrentHashMap<>();
    private static final ExecutorService INDEX_READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v31-rendered-atlas-index");
        thread.setDaemon(true);
        return thread;
    });
    record RegionPosition(int x, int z) {}

    private record PendingWrite(
            IndexKey indexKey,
            long signature,
            int regionX,
            int regionZ,
            int stride,
            int size,
            long[] fingerprints,
            int[] pixels) {}

    private PersistentRenderedAtlasStore() {}

    static Optional<Snapshot> load(
            UUID worldId,
            String dimension,
            long signature,
            int regionX,
            int regionZ,
            int stride,
            int expectedSize,
            int expectedTiles) {
        if (worldId == null || dimension == null || dimension.isBlank()) return Optional.empty();
        Path file = file(worldId, dimension, signature, regionX, regionZ, stride);
        if (!Files.isRegularFile(file)) return Optional.empty();

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return Optional.empty();
            if (input.readLong() != signature) return Optional.empty();
            if (input.readInt() != regionX || input.readInt() != regionZ) return Optional.empty();
            if (input.readInt() != stride) return Optional.empty();
            int size = input.readInt();
            int tiles = input.readInt();
            if (size != expectedSize || tiles != expectedTiles) return Optional.empty();

            long[] fingerprints = new long[tiles];
            for (int i = 0; i < tiles; i++) fingerprints[i] = input.readLong();

            int[] pixels = new int[size * size];
            for (int i = 0; i < pixels.length; i++) pixels[i] = input.readInt();
            if (input.read() != -1) return Optional.empty();
            return Optional.of(new Snapshot(fingerprints, pixels));
        } catch (IOException | RuntimeException corrupt) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            refreshIndex(worldId, dimension, signature, regionX, regionZ);
            return Optional.empty();
        }
    }

    static boolean existsAnyStride(
            UUID worldId, String dimension, long signature, int regionX, int regionZ) {
        if (worldId == null || dimension == null || dimension.isBlank()) return false;
        IndexKey key = new IndexKey(worldId, dimension, signature);
        Set<String> regions = regionIndex(key);
        return regions.contains(regionName(regionX, regionZ));
    }

    static java.util.List<RegionPosition> regionsInBounds(
            UUID worldId, String dimension, long signature, int minX, int maxX, int minZ, int maxZ) {
        if (worldId == null || dimension == null || dimension.isBlank()) return java.util.List.of();
        java.util.List<RegionPosition> result = new java.util.ArrayList<>();
        for (String name : regionIndex(new IndexKey(worldId, dimension, signature))) {
            int split = name.indexOf('_');
            if (split <= 0 || split == name.length() - 1) continue;
            try {
                int x = Integer.parseInt(name.substring(0, split));
                int z = Integer.parseInt(name.substring(split + 1));
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ)
                    result.add(new RegionPosition(x, z));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static Set<String> regionIndex(IndexKey key) {
        Set<String> regions = REGION_INDEX.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet());
        INDEX_LOADS.computeIfAbsent(key, ignored -> CompletableFuture.runAsync(
                () -> regions.addAll(buildIndex(key)), INDEX_READER));
        return regions;
    }

    static void save(
            UUID worldId,
            String dimension,
            long signature,
            int regionX,
            int regionZ,
            int stride,
            int size,
            long[] fingerprints,
            int[] pixels) {
        if (worldId == null || dimension == null || dimension.isBlank()
                || fingerprints == null || pixels == null) return;

        IndexKey indexKey = new IndexKey(worldId, dimension, signature);
        Path target = file(worldId, dimension, signature, regionX, regionZ, stride);
        PENDING.put(target, new PendingWrite(
                indexKey, signature, regionX, regionZ, stride, size,
                fingerprints.clone(), pixels.clone()));
        regionIndex(indexKey)
                .add(regionName(regionX, regionZ));
        schedule(target);
    }

    static void flush() {
        try {
            WRITER.submit(() -> {}).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static void schedule(Path target) {
        if (!SCHEDULED.add(target)) return;
        WRITER.execute(() -> drain(target));
    }

    private static void drain(Path target) {
        try {
            while (true) {
                PendingWrite write = PENDING.remove(target);
                if (write == null) break;
                write(target, write);
            }
        } finally {
            SCHEDULED.remove(target);
            if (PENDING.containsKey(target)) schedule(target);
        }
    }

    private static void write(Path target, PendingWrite write) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(
                            tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeLong(write.signature());
                output.writeInt(write.regionX());
                output.writeInt(write.regionZ());
                output.writeInt(write.stride());
                output.writeInt(write.size());
                output.writeInt(write.fingerprints().length);
                for (long fingerprint : write.fingerprints()) output.writeLong(fingerprint);
                for (int pixel : write.pixels()) output.writeInt(pixel);
            }
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            refreshIndex(
                    write.indexKey().worldId(),
                    write.indexKey().dimension(),
                    write.indexKey().signature(),
                    write.regionX(),
                    write.regionZ());
        }
    }

    private static Set<String> buildIndex(IndexKey key) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        Path base = signatureDirectory(key.worldId(), key.dimension(), key.signature());
        for (int stride = 1; stride <= InGameMapTiles.CHUNK; stride *= 2) {
            Path directory = base.resolve("s" + stride);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.cta1")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.length() > 5) result.add(name.substring(0, name.length() - 5));
                }
            } catch (IOException ignored) {}
        }
        return result;
    }

    private static void refreshIndex(
            UUID worldId, String dimension, long signature, int regionX, int regionZ) {
        IndexKey key = new IndexKey(worldId, dimension, signature);
        Set<String> current = REGION_INDEX.get(key);
        if (current == null) return;
        String region = regionName(regionX, regionZ);
        boolean exists = false;
        for (int stride = 1; stride <= InGameMapTiles.CHUNK; stride *= 2) {
            if (Files.isRegularFile(file(
                    worldId, dimension, signature, regionX, regionZ, stride))) {
                exists = true;
                break;
            }
        }
        if (exists) current.add(region);
        else current.remove(region);
    }

    private static String regionName(int regionX, int regionZ) {
        return regionX + "_" + regionZ;
    }

    private static Path file(
            UUID worldId,
            String dimension,
            long signature,
            int regionX,
            int regionZ,
            int stride) {
        return signatureDirectory(worldId, dimension, signature)
                .resolve("s" + stride)
                .resolve(regionName(regionX, regionZ) + ".cta1");
    }

    private static Path signatureDirectory(UUID worldId, String dimension, long signature) {
        String encodedDimension = Base64.getUrlEncoder().withoutPadding().encodeToString(
                dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve(ROOT)
                .resolve(worldId.toString())
                .resolve(encodedDimension)
                .resolve(Long.toUnsignedString(signature));
    }
}
