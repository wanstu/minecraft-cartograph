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
        System.out.println("PASS Cartograph common: spiral coverage, exact-once debit, dimension isolation, MCA zlib decoding");
    }
}
