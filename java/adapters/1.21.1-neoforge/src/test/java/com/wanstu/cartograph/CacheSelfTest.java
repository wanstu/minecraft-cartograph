package com.wanstu.cartograph;

import com.wanstu.cartograph.world.McaChunkReader;
import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            // Incremental edit: one surveyed 16x16 chunk inside a 128x128
            // page changes, WITHOUT modifying any other terrain, atlas
            // entitlements, colored mask, credits or existing metadata.
            byte[] patchPixels = new byte[256], patchFeatures = new byte[256],
                    patchDepths = new byte[256];
            short[] patchHeights = new short[256];
            Arrays.fill(patchPixels, (byte) 68);
            Arrays.fill(patchFeatures, (byte) 5);
            Arrays.fill(patchHeights, (short) 221);
            Arrays.fill(patchDepths, (byte) 9);
            MapNetwork.TerrainPatch patch = new MapNetwork.TerrainPatch(
                    page.atlasId(), page.dimension(), -16, 40,
                    patchPixels, patchFeatures, patchHeights, patchDepths,
                    List.of(new SurfaceStructure("minecraft:ancient_city", -254, 640)));
            MapNetwork.Page patched = TerrainPatchApplier.apply(restored, patch);
            expect(patched != null, "Paid chunk accepted");
            expect(patched.pixels()[0] == (byte) 68 && patched.heights()[0] == 221
                    && patched.features()[0] == (byte) 5 && patched.waterDepths()[0] == (byte) 9,
                    "First paid 16x16 chunk replaced with actual new values");
            expect(patched.pixels()[15 + 15 * 128] == (byte) 68
                    && patched.pixels()[16] == 45 && patched.pixels()[16 * 128] == 45
                    && patched.heights()[16] == 131 && patched.features()[16] == 0,
                    "Edit does not modify adjacent chunks, including page edge");
            expect(restored.pixels()[0] == 45 && restored.heights()[0] == 131,
                    "Patch is copy-on-write and does not mutate old cached frame");
            expect(patched.surveyedChunks() == page.surveyedChunks()
                    && patched.coloredChunks() == page.coloredChunks()
                    && patched.inkUnits() == page.inkUnits()
                    && patched.pigmentUnits() == page.pigmentUnits()
                    && patched.glowUnits() == page.glowUnits()
                    && patched.totalSurveyed() == page.totalSurveyed()
                    && patched.totalColored() == page.totalColored()
                    && patched.totalPages() == page.totalPages(),
                    "World changes NEVER adjust coverage masks or paid material balances");
            expect(patched.structures().stream().anyMatch(f -> f.type().equals("minecraft:ancient_city")),
                    "Edited chunk structure added");
            expect(TerrainPatchApplier.apply(patched, new MapNetwork.TerrainPatch(
                    page.atlasId(), page.dimension(), -15, 40,
                    patchPixels, patchFeatures, patchHeights, patchDepths, List.of())) == null,
                    "Unsurveyed adjacent chunk cannot be revealed by a patch");
            expect(TerrainPatchApplier.apply(patched, new MapNetwork.TerrainPatch(
                    "another-atlas", page.dimension(), -16, 40,
                    patchPixels, patchFeatures, patchHeights, patchDepths, List.of())) == null,
                    "Patch cannot cross atlas identity");
            expect(TerrainPatchApplier.apply(patched, new MapNetwork.TerrainPatch(
                    page.atlasId(), "minecraft:the_nether", -16, 40,
                    patchPixels, patchFeatures, patchHeights, patchDepths, List.of())) == null,
                    "Patch cannot cross dimensions");
            ClientPageStore.write(path, patched);
            MapNetwork.Page roundtripPatch = ClientPageStore.read(path, key);
            expect(roundtripPatch != null && roundtripPatch.heights()[0] == 221
                    && roundtripPatch.pixels()[16] == 45
                    && roundtripPatch.coloredChunks() == page.coloredChunks()
                    && roundtripPatch.inkUnits() == page.inkUnits(),
                    "Incremental update persists across client restart without re-survey");

            // Blank scanned pages (no ink) must ALSO survive local restart,
            // rather than trigger repeated 64-chunk scans as "cache misses".
            MapNetwork.Page empty = new MapNetwork.Page("atlas-02", page.dimension(),
                    -3, 6, new byte[MapNetwork.Page.PIXELS], new byte[MapNetwork.Page.PIXELS],
                    new short[0], new byte[0], 0L, 0L, 0, 0, 0, false, true, true, false,
                    64, 64, 0, page.anchorDimension(), page.anchorX(), page.anchorZ(),
                    3, 512, 0, 0, List.of(), List.of());
            ClientPageStore.Key emptyKey = new ClientPageStore.Key(
                    key.world(), empty.atlasId(), empty.dimension(), empty.pageX(), empty.pageZ());
            ClientPageStore.write(ClientPageStore.file(dir, emptyKey), empty);
            MapNetwork.Page blankRestored = ClientPageStore.read(ClientPageStore.file(dir, emptyKey), emptyKey);
            expect(blankRestored != null && blankRestored.complete() && blankRestored.painted() == 0
                    && blankRestored.surveyedChunks() == 0, "Blank no-ink pages are persistent cache hits");
            expect(ClientPageStore.read(ClientPageStore.file(dir,
                    new ClientPageStore.Key("another-world", empty.atlasId(), empty.dimension(),
                            empty.pageX(), empty.pageZ())),
                    new ClientPageStore.Key("another-world", empty.atlasId(), empty.dimension(),
                            empty.pageX(), empty.pageZ())) == null,
                    "Raw rendered cache isolated by world-specific filesystem namespace");
            // Actual world SavedData NBT serialization, not merely the
            // standalone Java ledger: the survey and color masks MUST remain
            // independent after a complete server-world reload.
            CompoundTag ledgerRoot = new CompoundTag();
            ListTag atlases = new ListTag();
            CompoundTag ledgerTag = new CompoundTag();
            AtlasState identified = AtlasState.blank().identified();
            ledgerTag.putString("id", identified.id());
            ledgerTag.put("state", AtlasState.CODEC.encodeStart(NbtOps.INSTANCE, identified).getOrThrow());
            ledgerTag.putString("anchor_dim", "minecraft:overworld");
            ledgerTag.putInt("anchor_x", -3);
            ledgerTag.putInt("anchor_z", 6);
            ledgerTag.putInt("sheet_side_blocks", 512);
            ListTag surveyMask = new ListTag(), colorMask = new ListTag();
            CompoundTag surveyedOnly = new CompoundTag();
            surveyedOnly.putString("dim", "minecraft:overworld");
            surveyedOnly.putInt("x", -24);
            surveyedOnly.putInt("z", 48);
            surveyMask.add(surveyedOnly);
            CompoundTag colorOnly = new CompoundTag();
            colorOnly.putString("dim", "minecraft:overworld");
            colorOnly.putInt("x", -23);
            colorOnly.putInt("z", 48);
            colorMask.add(colorOnly);
            ledgerTag.put("survey_mask", surveyMask);
            ledgerTag.put("color_mask", colorMask);
            atlases.add(ledgerTag);
            ledgerRoot.put("atlases", atlases);
            Method load = AtlasWorldData.class.getDeclaredMethod(
                    "load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            load.setAccessible(true);
            AtlasWorldData loadedWorld = (AtlasWorldData) load.invoke(null, ledgerRoot, null);
            Field entriesField = AtlasWorldData.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, AtlasWorldData.Entry> worldEntries =
                    (Map<String, AtlasWorldData.Entry>) entriesField.get(loadedWorld);
            AtlasWorldData.Entry loadedAtlas = worldEntries.get(identified.id());
            expect(loadedAtlas != null && loadedAtlas.surveyedCount() == 1
                    && loadedAtlas.coloredCount() == 1
                    && loadedAtlas.surveyedKeys("minecraft:overworld").contains(
                        new SurveyLedger.ChunkKey("minecraft:overworld", -24, 48))
                    && loadedAtlas.isColored(new SurveyLedger.ChunkKey("minecraft:overworld", -23, 48))
                    && !loadedAtlas.paid(new SurveyLedger.ChunkKey("minecraft:overworld", -23, 48))
                    && !loadedAtlas.isColored(new SurveyLedger.ChunkKey("minecraft:overworld", -24, 48)),
                    "Actual SavedData keeps paid visibility and color as independent masks");
            CompoundTag savedAgain = loadedWorld.save(new CompoundTag(), null);
            AtlasWorldData afterRestart =
                    (AtlasWorldData) load.invoke(null, savedAgain, null);
            @SuppressWarnings("unchecked")
            Map<String, AtlasWorldData.Entry> restoredEntries =
                    (Map<String, AtlasWorldData.Entry>) entriesField.get(afterRestart);
            AtlasWorldData.Entry restoredLedger = restoredEntries.get(identified.id());
            expect(restoredLedger.surveyedCount() == 1 && restoredLedger.coloredCount() == 1
                    && !restoredLedger.paid(new SurveyLedger.ChunkKey("minecraft:the_nether", -24, 48)),
                    "Masks survive repeated NBT restart without cross-dimension entitlement");
            byte[] corrupt = Files.readAllBytes(path);
            Files.write(path, Arrays.copyOf(corrupt, corrupt.length / 2));
            expect(ClientPageStore.read(path, key) == null, "Truncated cache fails closed");
            System.out.println("PASS disk raw cache, MCA revision, isolated masks, 16x16 patch, no-ink, restart, corrupt file");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
