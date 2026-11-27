package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

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

    private static volatile SavedMapCoverage coverage;

    private PersistentTileStore() {}

    static SavedMapCoverage savedCoverage(UUID worldId, String dimension, java.util.concurrent.Executor reader) {
        Path directory = directory(worldId, dimension);
        SavedMapCoverage active = coverage;
        if (active != null && active.uses(directory)) return active;
        if (active != null) active.close();
        active = new SavedMapCoverage(directory, dimension);
        coverage = active;
        active.start(reader);
        return active;
    }

    private static void coverageChanged(Path directory, ChunkKey key, boolean present) {
        SavedMapCoverage active = coverage;
        if (active != null && active.uses(directory)) active.changed(key.x(), key.z(), present);
    }

    static Optional<SurfaceTile> load(UUID worldId, ChunkKey key) {
        return loadRaw(file(directory(worldId, key.dimension()), key), key);
    }

    static void loadAsync(
            UUID worldId, ChunkKey key, BooleanSupplier current,
            BiConsumer<UUID, Optional<SurfaceTile>> consumer) {
        READER.execute(() -> {
            if (current.getAsBoolean()) consumer.accept(worldId, load(worldId, key));
        });
    }

    static Optional<SurfaceTile> loadRaw(Path file, ChunkKey key) {
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
            return Optional.empty();
        }
    }

    static void loadVisibleAsync(
            UUID worldId,
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ,
            Set<ChunkKey> scheduled,
            BooleanSupplier current,
            BiConsumer<UUID, SurfaceTile> consumer) {
        if (worldId == null || dimension == null || dimension.isBlank()
                || scheduled == null || consumer == null
                || minChunkX > maxChunkX || minChunkZ > maxChunkZ) return;

        Path directory = directory(worldId, dimension);
        // File names already identify their chunk. Probe the viewport in widening
        // rings so an old world does not need a complete directory index to open.
        long centerX = ((long) minChunkX + maxChunkX) / 2;
        long centerZ = ((long) minChunkZ + maxChunkZ) / 2;
        long maxDistance = Math.max(Math.max(centerX - minChunkX, maxChunkX - centerX),
                Math.max(centerZ - minChunkZ, maxChunkZ - centerZ));

        class Restore implements Runnable {
            private long distance;

            @Override
            public void run() {
                if (!current.getAsBoolean() || (distance == 0 && !Files.isDirectory(directory))) return;
                long west = centerX - distance;
                long east = centerX + distance;
                long north = centerZ - distance;
                long south = centerZ + distance;
                long firstX = Math.max(west, minChunkX);
                long lastX = Math.min(east, maxChunkX);
                long firstZ = Math.max(north + 1, minChunkZ);
                long lastZ = Math.min(south - 1, maxChunkZ);

                if (north >= minChunkZ && !row(firstX, lastX, (int) north)) return;
                if (south > north && south <= maxChunkZ && !row(firstX, lastX, (int) south)) return;
                if (west >= minChunkX && !column((int) west, firstZ, lastZ)) return;
                if (east <= maxChunkX && !column((int) east, firstZ, lastZ)) return;

                // Give any direct tile reads a turn between rings.
                if (distance < maxDistance && current.getAsBoolean()) {
                    distance++;
                    READER.execute(this);
                }
            }

            private boolean row(long first, long last, int z) {
                for (long x = first; x <= last; x++) {
                    if (!restore((int) x, z)) return false;
                }
                return true;
            }

            private boolean column(int x, long first, long last) {
                for (long z = first; z <= last; z++) {
                    if (!restore(x, (int) z)) return false;
                }
                return true;
            }

            private boolean restore(int x, int z) {
                if (!current.getAsBoolean()) return false;
                ChunkKey key = new ChunkKey(dimension, x, z);
                if (!scheduled.add(key)) return true;
                Optional<SurfaceTile> tile = loadRaw(file(directory, key), key);
                if (tile.isPresent() && current.getAsBoolean()) {
                    consumer.accept(worldId, tile.get());
                } else {
                    scheduled.remove(key);
                }
                return true;
            }
        }

        READER.execute(new Restore());
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
        Path directory = directory(worldId, key.dimension());
        Path target = file(directory, key);
        WRITER.execute(() -> {
            try {
                Files.deleteIfExists(target);
                coverageChanged(directory, key, false);
            } catch (IOException ignored) {}
        });
    }

    static void save(UUID worldId, SurfaceTile tile) {
        if (worldId == null || tile == null || !tile.hasSurfaceFacts()) return;

        WRITER.execute(() -> {
            Path directory = directory(worldId, tile.key().dimension());
            Path target = file(directory, tile.key());
            Path tmp = target.resolveSibling(
                    target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                byte[] encoded = encode(tile);
                // Server packets can arrive before the client has applied the restored tile.
                if (matchesExisting(target, encoded)) return;
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
                coverageChanged(directory, tile.key(), true);
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

    static Path file(Path directory, ChunkKey key) {
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
