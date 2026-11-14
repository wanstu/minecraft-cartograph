package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PersistentTileStore {
    private static final int MAGIC = 0x43545633; // CTV3
    private static final int VERSION_RGB_ONLY = 1;
    private static final int VERSION_SURFACE_FACTS = 2;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v3-client-cache");
        thread.setDaemon(true);
        return thread;
    });

    private PersistentTileStore() {}

    static Optional<SurfaceTile> load(UUID worldId, ChunkKey key) {
        Path file = file(worldId, key);
        if (!Files.isRegularFile(file)) return Optional.empty();

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) return Optional.empty();
            int version = input.readInt();

            int x = input.readInt();
            int z = input.readInt();
            if (x != key.x() || z != key.z()) return Optional.empty();

            SurfaceTile tile;
            if (version == VERSION_RGB_ONLY) {
                int[] rgb = new int[SurfaceTile.PIXELS];
                for (int i = 0; i < rgb.length; i++) rgb[i] = input.readInt();
                tile = new SurfaceTile(key, rgb);
            } else if (version == VERSION_SURFACE_FACTS) {
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
                tile = SatelliteSurfaceRenderer.render(
                        SurfaceTile.fromFacts(key, states, biomes, surface, relief, water));
            } else {
                return Optional.empty();
            }

            if (input.read() != -1) return Optional.empty();
            return Optional.of(tile);
        } catch (IOException | RuntimeException corrupt) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            return Optional.empty();
        }
    }

    static void delete(UUID worldId, ChunkKey key) {
        try { Files.deleteIfExists(file(worldId, key)); }
        catch (IOException ignored) {}
    }

    static void save(UUID worldId, SurfaceTile tile) {
        if (!tile.hasSurfaceFacts()) return;

        int[] states = tile.stateIds();
        int[] biomes = tile.biomeIds();
        short[] surface = tile.surfaceHeights();
        short[] relief = tile.reliefHeights();
        byte[] water = tile.waterDepths();

        WRITER.execute(() -> {
            Path target = file(worldId, tile.key());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.createDirectories(target.getParent());
                try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(tmp,
                                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
                    output.writeInt(MAGIC);
                    output.writeInt(VERSION_SURFACE_FACTS);
                    output.writeInt(tile.key().x());
                    output.writeInt(tile.key().z());

                    for (int i = 0; i < SurfaceTile.PIXELS; i++) {
                        output.writeInt(states[i]);
                        output.writeInt(biomes[i]);
                        output.writeShort(surface[i]);
                        output.writeShort(relief[i]);
                        output.writeByte(water[i]);
                    }
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    private static Path file(UUID worldId, ChunkKey key) {
        String dimension = Base64.getUrlEncoder().withoutPadding().encodeToString(
                key.dimension().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("cartograph_v3_map_cache")
                .resolve(worldId.toString())
                .resolve(dimension)
                .resolve(key.x() + "_" + key.z() + ".ctv3");
    }
}
