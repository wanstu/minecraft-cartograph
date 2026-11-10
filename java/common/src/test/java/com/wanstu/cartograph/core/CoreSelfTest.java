package com.wanstu.cartograph.core;

import com.wanstu.cartograph.world.ExistingRegionIndex;
import com.wanstu.cartograph.world.McaChunkReader;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Lightweight Java-17-compatible core tests; no Minecraft runtime needed. */
public final class CoreSelfTest {
    private static void yes(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        yes(AtlasPage.atBlock("minecraft:overworld", 0, 0).containsChunk(7, 7), "128-block boundary");
        yes(!AtlasPage.atBlock("minecraft:overworld", 0, 0).containsChunk(8, 0), "next page");
        yes(AtlasPage.atBlock("minecraft:overworld", -1, -1).pageX() == -1, "negative floor");

        // Exhaustive O(1) coverage checks across all directions and negative anchors.
        for (int index = 0; index < 1024; index++) {
            var offset = SpiralCoverage.offsetFor(index);
            yes(SpiralCoverage.covers(-1000, 500, -1000 + offset.x(), 500 + offset.z(), index + 1),
                    "spiral must cover its just-acquired page " + index);
            yes(!SpiralCoverage.covers(-1000, 500, -1000 + offset.x(), 500 + offset.z(), index),
                    "spiral must not grant unacquired page " + index);
        }
        yes(!SpiralCoverage.covers(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 512),
                "huge request must not overflow atlas coverage");

        SurveyLedger ledger = new SurveyLedger();
        var c = new SurveyLedger.ChunkKey("minecraft:overworld", 3, -5);
        var waiting = ledger.survey(c, false, true, true, false, 10, 0, 10, 10);
        yes(!waiting.firstSurvey(), "unpersisted chunk must never be charged");
        waiting = ledger.survey(c, true, false, true, false, 10, 0, 10, 10);
        yes(!waiting.firstSurvey(), "uncovered page must never be charged");
        waiting = ledger.survey(c, true, true, false, false, 10, 0, 0, 10);
        yes(!waiting.firstSurvey(), "zero multiplier must not bypass missing ink");
        var first = ledger.survey(c, true, true, true, false, 10, 0, 10, 10);
        yes(first.firstSurvey() && first.inkCostUnits() == 10, "first survey billed once");
        var again = ledger.survey(c, true, true, true, false, 0, 0, 10, 10);
        yes(!again.firstSurvey() && again.inkCostUnits() == 0, "free updates");
        var color = ledger.survey(c, true, true, true, true, 0, 10, 10, 10);
        yes(!color.firstSurvey() && color.firstColor() && color.pigmentCostUnits() == 10, "color once");
        var recolor = ledger.survey(c, true, true, true, true, 0, 0, 10, 10);
        yes(!recolor.firstColor() && recolor.pigmentCostUnits() == 0, "free recolor");
        yes(new SurveyLedger(ledger.snapshot()).updateMayRender(c), "ledger snapshot restore");
        var another = new SurveyLedger.ChunkKey("minecraft:overworld", 4, -5);
        yes(ledger.survey(another, true, true, true, true, 0, 0, 0, 0).firstSurvey(), "zero-rate but unlocked");
        yes(ledger.survey(new SurveyLedger.ChunkKey("minecraft:the_nether", 3, -5), false, true, true, false, 1, 0, 1, 1).fullySurveyed() == false, "dimensions isolated");

        // No accidental fabricated pixels: unknown and colorized areas remain
        // untouched while isolated binary speckles are suppressed in linear time.
        byte[] tones = new byte[128 * 128];
        for (int z = 5; z < 80; z++) for (int x = 5; x < 80; x++)
            tones[z * 128 + x] = 1;
        tones[20 * 128 + 20] = 2; // isolated dark leaf on a light landmass
        for (int z = 40; z < 55; z++) for (int x = 40; x < 55; x++)
            tones[z * 128 + x] = 2; // real solid dark water/forest patch
        tones[12 * 128 + 12] = 3; // previously colorized square
        byte[] smoothed = AtlasTone.smooth(tones);
        yes(smoothed[20 * 128 + 20] == 1, "isolated map pixel noise removed");
        yes(smoothed[45 * 128 + 45] == 2, "real black terrain region preserved");
        yes(smoothed[12 * 128 + 12] == 3, "color upgrades stay colored");
        yes(smoothed[110 * 128 + 110] == 0, "never invent ungenerated terrain");
        yes(tones[20 * 128 + 20] == 2, "survey source remains immutable");

        int stride = 2 * TerrainRelief.HALO + 1;
        int center = stride * stride / 2;
        short[] elevation = new short[stride * stride];
        Arrays.fill(elevation, (short) -48);
        yes(TerrainRelief.light(elevation, center, stride) == 1, "flat ground is neutral");
        for (int z = 0; z < stride; z++) for (int x = 0; x < stride; x++)
            elevation[z * stride + x] = (short) (x + z - 64);
        double lit = TerrainRelief.light(elevation, center, stride);
        yes(lit > 1, "north-west facing slope is lit");
        for (int i = 0; i < elevation.length; i++) elevation[i] = (short) -elevation[i];
        yes(TerrainRelief.light(elevation, center, stride) < 1, "opposite slope is in shade");

        Arrays.fill(elevation, (short) 64);
        elevation[center - 4 * (stride + 1)] = 85;
        yes(TerrainRelief.light(elevation, center, stride) < 1, "ridge casts a shadow on level ground");
        elevation[center - (stride + 1)] = TerrainRelief.UNKNOWN_HEIGHT;
        yes(TerrainRelief.light(elevation, center, stride) == 1, "no shadow across unknown terrain");
        Arrays.fill(elevation, TerrainRelief.UNKNOWN_HEIGHT);
        elevation[center] = -12;
        elevation[center + 2] = -10;
        yes(TerrainRelief.light(elevation, center, stride) > 1, "known neighbor at page edge");
        elevation[center] = TerrainRelief.UNKNOWN_HEIGHT;
        yes(TerrainRelief.light(elevation, center, stride) == 1, "unknown center stays unshaded");
        int gray = TerrainRelief.shade(0x75884f, lit, false);
        yes((gray & 255) * 0x010101 == gray, "uncolored terrain is neutral gray");
        yes(TerrainRelief.waterColor(1) != TerrainRelief.waterColor(12), "real water depth changes tone");
        yes(TerrainRelief.waterColor(12) == TerrainRelief.waterColor(24), "deep-water tone is bounded");

        Path tmp = Files.createTempDirectory("cartograph-mca-test");
        try {
            Path file = tmp.resolve("r.-1.0.mca");
            byte[] data = new byte[12288];
            // Local position (31,0): offset sector=2, sectors=1.
            int at = 31 * 4;
            data[at] = 0; data[at+1] = 0; data[at+2] = 2; data[at+3] = 1;
            data[8192+3] = 8; // payload length
            data[8192+4] = 2; // compression type, zlib
            Files.write(file, data);
            yes(ExistingRegionIndex.isPersisted(tmp, -1, 0), "negative chunk header lookup");
            yes(!ExistingRegionIndex.isPersisted(tmp, -2, 0), "absent neighbor");
            yes(ExistingRegionIndex.listPersisted(tmp, -1, 0).size() == 1, "valid region listing");

            // Write a complete zlib-compressed NBT root into the same synthetic
            // region sector, then verify that readNbt never generates a chunk.
            byte[] originalNbt = {10, 0, 0, 0};
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream out = new DeflaterOutputStream(compressed)) {
                out.write(originalNbt);
            }
            byte[] payload = compressed.toByteArray();
            int length = payload.length + 1;
            data[8192] = (byte) (length >>> 24); data[8193] = (byte) (length >>> 16);
            data[8194] = (byte) (length >>> 8); data[8195] = (byte) length;
            System.arraycopy(payload, 0, data, 8197, payload.length);
            Files.write(file, data);
            yes(Arrays.equals(originalNbt, McaChunkReader.readNbt(tmp, -1, 0).orElseThrow()),
                    "real zlib MCA read without creating chunks");
            yes(McaChunkReader.readNbt(tmp, -2, 0).isEmpty(), "unpersisted region remains unknown");
            data[8192+3] = 0;
            Files.write(file, data);
            yes(!ExistingRegionIndex.isPersisted(tmp, -1, 0), "invalid empty chunk payload");
        } finally {
            try (var files = Files.list(tmp)) { for (var file : files.toList()) Files.delete(file); }
            Files.delete(tmp);
        }
        System.out.println("PASS Cartograph common: spiral coverage, exact-once debit, dimension isolation, MCA zlib, monochrome filter, terrain relief");
    }
}
