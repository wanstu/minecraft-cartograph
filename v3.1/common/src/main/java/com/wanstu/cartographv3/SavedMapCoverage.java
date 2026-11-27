package com.wanstu.cartographv3;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Sparse directory index for the saved surface facts used by the native overview. */
final class SavedMapCoverage {
    static final int REGION_CHUNKS = 2 * CartographBackendApi.REGION_CHUNKS;

    record Region(int x, int z) {
        static Region ofChunk(int x, int z) {
            return new Region(Math.floorDiv(x, REGION_CHUNKS), Math.floorDiv(z, REGION_CHUNKS));
        }
    }

    private record Entry(BitSet tiles, long revision) {}
    private record Chunk(int x, int z) {}
    private final Path directory;
    private final String dimension;
    private final Map<Chunk, Boolean> changesDuringScan = new HashMap<>();
    private volatile Map<Region, Entry> regions = Map.of();
    private volatile boolean closed;
    private boolean indexed;
    private long latestRevision = 1;

    SavedMapCoverage(Path directory, String dimension) {
        this.directory = directory;
        this.dimension = dimension;
    }

    void start(Executor reader) { reader.execute(this::scan); }

    boolean uses(Path directory) { return this.directory.equals(directory); }
    void close() { closed = true; }

    List<Region> regionsInBounds(int minX, int maxX, int minZ, int maxZ) {
        List<Region> result = new ArrayList<>();
        for (var entry : regions.entrySet()) {
            Region region = entry.getKey();
            if (region.x() >= minX && region.x() <= maxX && region.z() >= minZ && region.z() <= maxZ) {
                result.add(region);
            }
        }
        return result;
    }

    long revision(Region region, boolean halo) {
        var current = regions;
        Entry entry = current.get(region);
        long revision = entry == null ? 0 : entry.revision();
        if (!halo) return revision;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Entry neighbor = current.get(new Region(region.x() + dx, region.z() + dz));
                if (neighbor != null) revision = Math.max(revision, neighbor.revision());
            }
        }
        return revision;
    }

    SurfaceTile[] read(Region region, boolean halo, BooleanSupplier current) {
        int span = REGION_CHUNKS + 2;
        SurfaceTile[] tiles = new SurfaceTile[span * span];
        var coverage = regions;
        int originX = region.x() * REGION_CHUNKS;
        int originZ = region.z() * REGION_CHUNKS;
        int margin = halo ? 1 : 0;
        for (int z = -margin; z < REGION_CHUNKS + margin; z++) {
            for (int x = -margin; x < REGION_CHUNKS + margin; x++) {
                if (closed || !current.getAsBoolean()) return tiles;
                int chunkX = originX + x;
                int chunkZ = originZ + z;
                Region source = x >= 0 && z >= 0 && x < REGION_CHUNKS && z < REGION_CHUNKS
                        ? region : Region.ofChunk(chunkX, chunkZ);
                Entry entry = coverage.get(source);
                if (entry == null || !entry.tiles().get(index(chunkX, chunkZ))) continue;
                var key = new ChunkKey(dimension, chunkX, chunkZ);
                tiles[(z + 1) * span + x + 1] = PersistentTileStore
                        .loadRaw(PersistentTileStore.file(directory, key), key).orElse(null);
            }
        }
        return tiles;
    }

    synchronized void changed(int x, int z, boolean present) {
        if (closed) return;
        if (!indexed) {
            changesDuringScan.put(new Chunk(x, z), present);
        } else {
            apply(regions, x, z, present);
        }
    }

    private void scan() {
        Map<Region, BitSet> found = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.ct31")) {
            for (Path file : files) {
                if (closed) return;
                String name = file.getFileName().toString();
                int separator = name.indexOf('_');
                if (separator <= 0 || separator >= name.length() - 6) continue;
                try {
                    int x = Integer.parseInt(name, 0, separator, 10);
                    int z = Integer.parseInt(name, separator + 1, name.length() - 5, 10);
                    found.computeIfAbsent(Region.ofChunk(x, z), ignored -> new BitSet())
                            .set(index(x, z));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException unavailable) {
            // Any writes after the scan still add their actual coordinates.
        }
        Map<Region, Entry> index = new ConcurrentHashMap<>();
        found.forEach((region, tiles) -> index.put(region, new Entry(tiles, 1)));
        synchronized (this) {
            if (closed) return;
            changesDuringScan.forEach((chunk, present) -> apply(index, chunk.x(), chunk.z(), present));
            changesDuringScan.clear();
            regions = index;
            indexed = true;
        }
    }

    private void apply(Map<Region, Entry> index, int x, int z, boolean present) {
        Region region = Region.ofChunk(x, z);
        Entry before = index.get(region);
        if (before == null && !present) return;
        BitSet tiles = before == null ? new BitSet() : (BitSet) before.tiles().clone();
        int position = index(x, z);
        if (!present && !tiles.get(position)) return;
        tiles.set(position, present);
        index.put(region, new Entry(tiles, ++latestRevision));
    }

    private static int index(int x, int z) {
        return Math.floorMod(z, REGION_CHUNKS) * REGION_CHUNKS + Math.floorMod(x, REGION_CHUNKS);
    }
}
