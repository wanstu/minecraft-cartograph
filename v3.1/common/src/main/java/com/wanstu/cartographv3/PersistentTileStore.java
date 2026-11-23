package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * V3.1 authoritative client cache.
 *
 * Only raw surface facts are persisted. Any satellite/pixel/hand-drawn/ancient
 * pixels are frontend-derived data and are intentionally excluded.
 *
 * V3.1 deliberately does not read older Cartograph cache formats.
 */
final class PersistentTileStore {
    private static final int MAGIC = 0x43543331; // CT31
    private static final int VERSION = 1;
    private static final int FILE_BYTES = 4 * Integer.BYTES + SurfaceTile.PIXELS
            * (2 * Integer.BYTES + 2 * Short.BYTES + Byte.BYTES);
    private static final int REGION_EDGE = CartographBackendApi.REGION_CHUNKS;
    private static final int MAX_INDEXED_DIRECTORIES = 4;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v31.cache");
    private static final LinkedHashMap<Path, RegionIndex> INDEXES =
            new LinkedHashMap<>(8, .75f, true);

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v31-fact-cache-writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v31-fact-cache-reader");
        thread.setDaemon(true);
        return thread;
    });

    private PersistentTileStore() {}

    static Optional<SurfaceTile> load(UUID worldId, ChunkKey key) {
        Path directory = directory(worldId, key.dimension());
        RegionIndex index = existingIndex(directory);
        if (index != null && !index.mayContain(key)) return Optional.empty();
        return loadRaw(file(directory, key), key);
    }

    private static Optional<SurfaceTile> loadRaw(Path file, ChunkKey key) {
        if (!Files.isRegularFile(file)) return Optional.empty();

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) return Optional.empty();
            if (input.readInt() != VERSION) return Optional.empty();

            int x = input.readInt();
            int z = input.readInt();
            if (x != key.x() || z != key.z()) return Optional.empty();

            int[] states = new int[SurfaceTile.PIXELS];
            int[] biomes = new int[SurfaceTile.PIXELS];
            short[] surface = new short[SurfaceTile.PIXELS];
            short[] relief = new short[SurfaceTile.PIXELS];
            byte[] water = new byte[SurfaceTile.PIXELS];

            for (int i = 0; i < SurfaceTile.PIXELS; i++) {
                states[i] = input.readInt();
                biomes[i] = input.readInt();
                surface[i] = input.readShort();
                relief[i] = input.readShort();
                water[i] = input.readByte();
            }

            if (input.read() != -1) return Optional.empty();
            return Optional.of(SurfaceTile.fromFacts(
                    key, states, biomes, surface, relief, water));
        } catch (IOException | RuntimeException corrupt) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            refreshIndex(file, key);
            return Optional.empty();
        }
    }

    static void loadVisibleAsync(
            UUID worldId,
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ,
            Set<ChunkKey> scheduled,
            BiConsumer<UUID, SurfaceTile> consumer) {
        if (worldId == null || dimension == null || dimension.isBlank()
                || scheduled == null || consumer == null) return;

        Path directory = directory(worldId, dimension);
        READER.execute(() -> {
            RegionIndex index = indexFor(directory);
            if (!index.prepare(directory, dimension)) return;
            List<ChunkKey> candidates = index.inBounds(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
            candidates.removeIf(key -> !scheduled.add(key));

            long centerX = ((long) minChunkX + (long) maxChunkX) / 2L;
            long centerZ = ((long) minChunkZ + (long) maxChunkZ) / 2L;
            candidates.sort(Comparator.comparingLong(key -> {
                long dx = Math.abs((long) key.x() - centerX);
                long dz = Math.abs((long) key.z() - centerZ);
                return Math.max(dx, dz);
            }));

            for (ChunkKey key : candidates) {
                Path file = file(directory, key);
                Optional<SurfaceTile> tile = loadRaw(file, key);
                if (tile.isPresent()) {
                    consumer.accept(worldId, tile.get());
                } else {
                    index.refresh(key, file);
                    scheduled.remove(key);
                }
            }
        });
    }

    private static ChunkKey keyFromFile(String dimension, String filename) {
        if (filename == null || !filename.endsWith(".ct31")) return null;
        String stem = filename.substring(0, filename.length() - 5);
        int split = stem.indexOf('_');
        if (split <= 0 || split >= stem.length() - 1) return null;
        try {
            return new ChunkKey(
                    dimension,
                    Integer.parseInt(stem.substring(0, split)),
                    Integer.parseInt(stem.substring(split + 1)));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static long backgroundCheckpoint(UUID worldId, String dimensionId) {
        if (worldId == null || dimensionId == null || dimensionId.isBlank()) return 0L;
        Path file = checkpointFile(worldId, dimensionId);
        if (!Files.isRegularFile(file)) return 0L;
        try {
            return Math.max(0L, Long.parseLong(Files.readString(file).trim()));
        } catch (IOException | NumberFormatException invalid) {
            return 0L;
        }
    }

    static void saveBackgroundCheckpoint(UUID worldId, String dimensionId, long checkpointMillis) {
        if (worldId == null || dimensionId == null || dimensionId.isBlank()
                || checkpointMillis <= 0L) return;

        WRITER.execute(() -> {
            Path target = checkpointFile(worldId, dimensionId);
            Path tmp = target.resolveSibling(
                    target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(
                        tmp, Long.toString(checkpointMillis),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(
                            tmp, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    static void delete(UUID worldId, ChunkKey key) {
        Path target = file(directory(worldId, key.dimension()), key);
        try {
            Files.deleteIfExists(target);
            refreshIndex(target, key);
        } catch (IOException ignored) {}
    }

    static void save(UUID worldId, SurfaceTile tile) {
        if (worldId == null || tile == null || !tile.hasSurfaceFacts()) return;

        WRITER.execute(() -> {
            Path target = file(directory(worldId, tile.key().dimension()), tile.key());
            Path tmp = target.resolveSibling(
                    target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                byte[] encoded = encode(tile);
                // Server packets can arrive before the client has applied the restored tile.
                if (matchesExisting(target, encoded)) {
                    refreshIndex(target, tile.key());
                    return;
                }
                Files.createDirectories(target.getParent());
                Files.write(tmp, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(
                            tmp, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(
                            tmp, target,
                            StandardCopyOption.REPLACE_EXISTING);
                }
                refreshIndex(target, tile.key());
            } catch (IOException failure) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    private static byte[] encode(SurfaceTile tile) {
        ByteBuffer output = ByteBuffer.allocate(FILE_BYTES);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putInt(tile.key().x());
        output.putInt(tile.key().z());
        for (int i = 0; i < SurfaceTile.PIXELS; i++) {
            output.putInt(tile.stateIdAt(i));
            output.putInt(tile.biomeIdAt(i));
            output.putShort((short) tile.surfaceHeightAt(i));
            output.putShort((short) tile.reliefHeightAt(i));
            output.put((byte) tile.waterDepthAt(i));
        }
        return output.array();
    }

    private static boolean matchesExisting(Path target, byte[] encoded) {
        try (InputStream input = Files.newInputStream(target)) {
            byte[] existing = new byte[encoded.length];
            return input.readNBytes(existing, 0, existing.length) == existing.length
                    && input.read() == -1
                    && Arrays.equals(existing, encoded);
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static RegionIndex indexFor(Path directory) {
        synchronized (INDEXES) {
            RegionIndex index = INDEXES.computeIfAbsent(directory, ignored -> new RegionIndex());
            if (INDEXES.size() > MAX_INDEXED_DIRECTORIES) INDEXES.pollFirstEntry();
            return index;
        }
    }

    private static RegionIndex existingIndex(Path directory) {
        synchronized (INDEXES) {
            return INDEXES.get(directory);
        }
    }

    private static void refreshIndex(Path file, ChunkKey key) {
        RegionIndex index = existingIndex(file.getParent());
        if (index != null) index.refresh(key, file);
    }

    private record Region(int x, int z) {
        static Region containing(ChunkKey key) {
            return new Region(Math.floorDiv(key.x(), REGION_EDGE), Math.floorDiv(key.z(), REGION_EDGE));
        }
    }

    private static final class RegionIndex {
        private final Map<ChunkKey, Boolean> pending = new HashMap<>();
        private volatile Map<Region, Set<ChunkKey>> regions;

        // The reader builds a snapshot while successful saves and deletions record their changes.
        boolean prepare(Path directory, String dimension) {
            if (regions != null) return true;
            long started = System.nanoTime();
            Map<Region, Set<ChunkKey>> scanned = new ConcurrentHashMap<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.ct31")) {
                for (Path candidate : files) {
                    ChunkKey key = keyFromFile(dimension, candidate.getFileName().toString());
                    if (key != null) setPresent(scanned, key, true);
                }
            } catch (NoSuchFileException missing) {
                // A new world gets its first entries from the writer.
            } catch (IOException | DirectoryIteratorException unavailable) {
                return false;
            }
            synchronized (this) {
                pending.forEach((key, present) -> setPresent(scanned, key, present));
                pending.clear();
                regions = scanned;
            }
            int chunks = scanned.values().stream().mapToInt(Set::size).sum();
            LOG.info("V31_DISK_INDEX dimension={} regions={} chunks={} elapsed_ms={}",
                    dimension, scanned.size(), chunks, (System.nanoTime() - started) / 1_000_000L);
            return true;
        }

        synchronized void refresh(ChunkKey key, Path file) {
            boolean present = Files.isRegularFile(file);
            if (regions == null) pending.put(key, present);
            else setPresent(regions, key, present);
        }

        boolean mayContain(ChunkKey key) {
            var current = regions;
            if (current == null) return true;
            Set<ChunkKey> bucket = current.get(Region.containing(key));
            return bucket != null && bucket.contains(key);
        }

        List<ChunkKey> inBounds(int minX, int maxX, int minZ, int maxZ) {
            List<ChunkKey> matches = new ArrayList<>();
            int west = Math.floorDiv(minX, REGION_EDGE);
            int east = Math.floorDiv(maxX, REGION_EDGE);
            int north = Math.floorDiv(minZ, REGION_EDGE);
            int south = Math.floorDiv(maxZ, REGION_EDGE);
            long area = (east - west + 1L) * (south - north + 1L);
            var current = regions;
            if (area <= current.size()) {
                for (int z = north; z <= south; z++) {
                    for (int x = west; x <= east; x++) {
                        append(current.get(new Region(x, z)), matches, minX, maxX, minZ, maxZ);
                    }
                }
            } else {
                current.forEach((region, keys) -> {
                    if (region.x >= west && region.x <= east && region.z >= north && region.z <= south) {
                        append(keys, matches, minX, maxX, minZ, maxZ);
                    }
                });
            }
            return matches;
        }

        private static void append(Set<ChunkKey> keys, List<ChunkKey> matches,
                                   int minX, int maxX, int minZ, int maxZ) {
            if (keys == null) return;
            for (ChunkKey key : keys) {
                if (key.x() >= minX && key.x() <= maxX && key.z() >= minZ && key.z() <= maxZ) {
                    matches.add(key);
                }
            }
        }

        private static void setPresent(Map<Region, Set<ChunkKey>> map, ChunkKey key, boolean present) {
            Region region = Region.containing(key);
            if (present) {
                map.computeIfAbsent(region, ignored -> ConcurrentHashMap.newKeySet()).add(key);
            } else {
                map.computeIfPresent(region, (ignored, keys) -> {
                    keys.remove(key);
                    return keys.isEmpty() ? null : keys;
                });
            }
        }
    }

    private static Path file(Path directory, ChunkKey key) {
        return directory.resolve(key.x() + "_" + key.z() + ".ct31");
    }

    private static Path checkpointFile(UUID worldId, String dimensionId) {
        return directory(worldId, dimensionId).resolve(".background-checkpoint");
    }

    private static Path directory(UUID worldId, String dimensionId) {
        String dimension = Base64.getUrlEncoder().withoutPadding().encodeToString(
                dimensionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve(MapGameAccess.FACTS_DIRECTORY)
                .resolve(worldId.toString())
                .resolve(dimension);
    }
}
