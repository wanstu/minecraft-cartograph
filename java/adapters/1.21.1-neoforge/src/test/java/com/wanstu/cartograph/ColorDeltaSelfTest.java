package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import java.nio.file.*;
import java.util.*;

/** P2 fast pigment-only updates: authoritative payment, immutable 16x16
 * edits, legacy v5 cache restore and un-inked chunk security. */
public final class ColorDeltaSelfTest {
    static void check(boolean test, String name) {
        if (!test) throw new AssertionError(name);
    }

    static MapNetwork.Page example() {
        byte[] gray = new byte[MapNetwork.Page.PIXELS];
        byte[] features = new byte[gray.length], water = new byte[gray.length];
        short[] heights = new short[gray.length];
        Arrays.fill(gray, (byte) 32);
        Arrays.fill(features, (byte) 4);
        Arrays.fill(heights, (short) 120);
        Arrays.fill(water, (byte) 1);
        return new MapNetwork.Page("atlas-color-test", "minecraft:overworld",
                -1, 2, gray, features, heights, water,
                1L | Long.MIN_VALUE, 0L,
                990, 1234, 555, true, true, true, false, 64, 64, 2,
                "minecraft:overworld", -1, 2, 7, 512, 21, 0,
                List.of(), List.of());
    }

    static void pigmentPermissions() {
        SurveyLedger ledger = new SurveyLedger();
        var chunk = new SurveyLedger.ChunkKey("minecraft:overworld", -8, 16);
        var colorFirst = ledger.colorize(chunk, true, true, true, 50, 5, false);
        check(colorFirst.firstColor() && !colorFirst.firstSurvey(), "Color may precede survey");
        check(colorFirst.pigmentCostUnits() == 5 && colorFirst.inkCostUnits() == 0,
                "Independent color debit");
        check(!ledger.updateMayRender(chunk) && ledger.isColored(chunk),
                "Uninked color permission is INVISIBLE");
        var repeat = ledger.colorize(chunk, true, true, true, 0, 5, false);
        check(!repeat.firstColor() && repeat.pigmentCostUnits() == 0, "Dye purchased once");
        var inkLater = ledger.survey(chunk, true, true, true, true,
                20, 0, 4, 5, false, false);
        check(inkLater.firstSurvey() && !inkLater.firstColor() && inkLater.inkCostUnits() == 4
                && inkLater.pigmentCostUnits() == 0, "Ink reveals pre-bought colors without duplicate debit");
        var permanent = ledger.colorize(
                new SurveyLedger.ChunkKey("minecraft:overworld", -7, 16),
                true, true, true, 0, 5, true);
        check(permanent.firstColor() && permanent.pigmentCostUnits() == 0,
                "Permanent color may unlock with no dye balance");
        var covered = ledger.colorize(
                new SurveyLedger.ChunkKey("minecraft:overworld", -6, 16),
                true, false, true, 50, 5, false);
        check(!covered.firstColor(), "Cannot buy color outside paper coverage");
        var restart = new SurveyLedger(ledger.snapshot());
        check(restart.isColored(chunk) && restart.updateMayRender(chunk),
                "Independent ink and color survive snapshot restart");
    }

    static void pixelPatchAndDisk() throws Exception {
        MapNetwork.Page old = example();
        byte[] samples = new byte[256 * 3];
        Arrays.fill(samples, 0, 256, (byte) 13);       // paid page corner
        Arrays.fill(samples, 256, 512, (byte) 17);     // not inked -> ignore
        Arrays.fill(samples, 512, 768, (byte) 29);     // paid last chunk
        var delta = new MapNetwork.ColorDelta(
                old.atlasId(), old.dimension(), old.pageX(), old.pageZ(),
                1L | 2L | Long.MIN_VALUE, samples, 456, 8);
        MapNetwork.Page changed = ColorPatchApplier.apply(old, delta);
        check(changed != null && changed.pixels()[0] == 13, "Authorized top-left block recolored");
        check(changed.pixels()[15 + 15*128] == 13, "Full top-left 16x16 recolored");
        check(changed.pixels()[16] == 32 && changed.pixels()[17] == 32,
                "Uninked data cannot be leaked even when server mask contains that chunk");
        check(changed.pixels()[112 + 112*128] == 29 &&
                changed.pixels()[127 + 127*128] == 29, "Last 16x16 chunk recolored");
        check(changed.pixels()[32] == 32, "All unrelated chunks unchanged");
        check(changed.coloredChunks() == (1L | Long.MIN_VALUE)
                && changed.surveyedChunks() == old.surveyedChunks(), "Only pigmented already-paid mask bits added");
        check(changed.pigmentUnits() == 456 && changed.inkUnits() == 990
                && changed.glowUnits() == 555 && changed.totalPages() == 7
                && changed.totalColored() == 8 && changed.totalSurveyed() == 21,
                "No ink/glow/paper/coverage side effects, server-authorized color balance");
        check(changed.heights() == old.heights()
                && changed.features() == old.features()
                && changed.waterDepths() == old.waterDepths()
                && changed.structures() == old.structures(),
                "Color delta never reconstructs geometry or height");
        check(old.coloredChunks() == 0 && old.pixels()[0] == 32 && old.pigmentUnits() == 1234,
                "Original cached frame immutable");
        check(ColorPatchApplier.apply(old, new MapNetwork.ColorDelta(
                "different", old.dimension(), -1, 2, 0L, new byte[0], 0, 0)) == null,
                "Cannot cross atlas identity");
        check(ColorPatchApplier.apply(old, new MapNetwork.ColorDelta(
                old.atlasId(), "minecraft:the_nether", -1, 2, 0L, new byte[0], 0, 0)) == null,
                "Cannot cross world dimension");
        check(ColorPatchApplier.apply(old, new MapNetwork.ColorDelta(
                old.atlasId(), old.dimension(), 0, 2, 0L, new byte[0], 0, 0)) == null,
                "Cannot cross page coordinates");
        try {
            new MapNetwork.ColorDelta(old.atlasId(), old.dimension(), -1, 2,
                    3L, new byte[256], 5, 7);
            throw new AssertionError("Invalid compressed color payload accepted");
        } catch (IllegalArgumentException expected) { }

        Path tmp = Files.createTempDirectory("cartograph-color-delta-v5");
        try {
            var key = new ClientPageStore.Key(
                    ClientPageStore.namespace("local:world-color"),
                    old.atlasId(), old.dimension(), old.pageX(), old.pageZ());
            Path file = ClientPageStore.file(tmp, key);
            ClientPageStore.write(file, old);
            var legacy = ClientPageStore.read(file, key);
            check(legacy != null && legacy.coloredChunks() == 0,
                    "Old v5 gray cache still readable");
            var patched = ColorPatchApplier.apply(legacy, delta);
            ClientPageStore.write(file, patched);
            var restored = ClientPageStore.read(file, key);
            check(restored != null && restored.pixels()[0] == 13
                    && restored.pixels()[16] == 32
                    && restored.pixels()[112 + 112*128] == 29
                    && restored.coloredChunks() == changed.coloredChunks()
                    && restored.pigmentUnits() == 456 && restored.inkUnits() == 990,
                    "Recolored file can restart in original v5 cache format");
        } finally {
            try (var files = Files.walk(tmp)) {
                for (Path f : files.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(f);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        pigmentPermissions();
        pixelPatchAndDisk();
        System.out.println("PASS dye-first, survey-later, 16x16 color delta only, no paper/ink fee, stale cache isolation and old v5 GZIP restart");
    }
}
