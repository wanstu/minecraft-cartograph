package com.wanstu.cartograph;

import com.wanstu.cartograph.world.McaChunkReader;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;

/** Standalone deterministic disk format and map page roundtrip tests. */
public final class CacheSelfTest {
    private static void expect(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("cartograph-map-cache-test-");
        try {
            Path region = dir.resolve("world/region");
            Files.createDirectories(region);
            Path regionFile = region.resolve("r.-1.0.mca");
            int chunkX = -3, chunkZ = 6, ix = Math.floorMod(chunkX, 32),
                    iz = Math.floorMod(chunkZ, 32), offset = (iz * 32 + ix) * 4;
            try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "rw")) {
                file.setLength(12_288);
                file.seek(offset); file.writeInt((2 << 8) | 1);
                file.seek(4_096 + offset); file.writeInt(12345678);
            }
            SurfaceSampler.SavedRequest req =
                    new SurfaceSampler.SavedRequest(region, chunkX, chunkZ, -64, 384);
            expect(McaChunkReader.chunkTimestamp(region, chunkX, chunkZ) == 12345678,
                    "Negative chunk MCA timestamp");
            byte[] colors = new byte[256], features = new byte[256], depths = new byte[256];
            short[] heights = new short[256];
            Arrays.fill(colors, (byte) 39);
            Arrays.fill(features, (byte) 1);
            Arrays.fill(heights, (short) 153);
            Arrays.fill(depths, (byte) 4);
            SurfaceSampler.SurfaceTile real = new SurfaceSampler.SurfaceTile(
                    colors, features, heights, depths, List.of(
                            new SurfaceStructure("minecraft:village_plains", -35, 100)));
            SurfaceDiskStore.write(req, real);
            expect(SurfaceDiskStore.equal(real, SurfaceDiskStore.read(req)),
                    "Cached raw tile roundtrip");
            // Verify deterministic compressed bytes and checksum validation.
            try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "rw")) {
                file.seek(4_096 + offset); file.writeInt(12345679);
            }
            expect(SurfaceDiskStore.read(req) == null, "Changed MCA revision invalidates raw tile");
            SurfaceDiskStore.write(req, real);
            expect(SurfaceDiskStore.equal(real, SurfaceDiskStore.read(req)),
                    "Updated region timestamp rewrites tile");
            try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "rw")) {
                file.seek(offset); file.writeInt(0);
            }
            expect(SurfaceDiskStore.read(req) == null, "Deleted region entry invalidates raw tile");

            byte[] pixels = new byte[MapNetwork.Page.PIXELS];
            byte[] overlays = new byte[MapNetwork.Page.PIXELS];
            short[] elevation = new short[MapNetwork.Page.PIXELS];
            byte[] water = new byte[MapNetwork.Page.PIXELS];
            Arrays.fill(pixels, (byte) 45);
            Arrays.fill(elevation, (short) 131);
            AtlasMarker marker = new AtlasMarker("mark-01", "minecraft:overworld",
                    -101, 301, "Home", 0, 0xff66aa88, false, false);
            MapNetwork.Page page = new MapNetwork.Page("atlas-01", "minecraft:overworld",
                    -2, 5, pixels, overlays, elevation, water,
                    1L, 1L, 1200, 900, 100, true, true, true, false,
                    64, 64, 64, "minecraft:overworld", -2, 5,
                    3, 512, 64, 64, List.of(marker), List.of(
                            new SurfaceStructure("minecraft:shipwreck", -100, 350)));
            ClientPageStore.Key key = new ClientPageStore.Key(
                    ClientPageStore.namespace("local:test-world"),
                    page.atlasId(), page.dimension(), page.pageX(), page.pageZ());
            Path path = ClientPageStore.file(dir, key);
            ClientPageStore.write(path, page);
            MapNetwork.Page restored = ClientPageStore.read(path, key);
            expect(restored != null && Arrays.equals(page.pixels(), restored.pixels())
                    && Arrays.equals(page.heights(), restored.heights())
                    && restored.pageX() == -2 && restored.pageZ() == 5
                    && restored.markers().equals(page.markers())
                    && restored.structures().equals(page.structures())
                    && restored.totalPages() == 3, "Full client raster and metadata roundtrip");
            expect(ClientPageStore.read(path, new ClientPageStore.Key(
                    key.world(), "some-other-atlas", key.dimension(), -2, 5)) == null,
                    "Atlas isolation");
            byte[] corrupt = Files.readAllBytes(path);
            Files.write(path, Arrays.copyOf(corrupt, corrupt.length / 2));
            expect(ClientPageStore.read(path, key) == null, "Truncated cache fails closed");
            System.out.println("PASS disk raw cache, MCA revision invalidation, client pages, atlas isolation, corrupt file");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
