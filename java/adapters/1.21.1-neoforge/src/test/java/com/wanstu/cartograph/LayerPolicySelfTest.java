package com.wanstu.cartograph;

import com.wanstu.cartograph.core.AtlasLayers;
import com.wanstu.cartograph.core.MapSheetCoverage;
import com.wanstu.cartograph.core.SurveyLedger;

/** The logical paper/ink/pigment policy is independent of renderer/GUI. */
public final class LayerPolicySelfTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void gates() {
        for (int p = 0; p < 2; p++)
            for (int i = 0; i < 2; i++)
                for (int c = 0; c < 2; c++) {
                    var visibility = AtlasLayers.compose(p != 0, i != 0, c != 0);
                    var expected = p == 0 ? AtlasLayers.Visibility.NO_PAPER
                            : i == 0 ? AtlasLayers.Visibility.BLANK_PAPER
                            : c == 0 ? AtlasLayers.Visibility.GRAYSCALE
                            : AtlasLayers.Visibility.COLOR;
                    check(visibility == expected, "Three-gate combination " + p + i + c);
                }
        check(AtlasLayers.compose(true, 0L, -1L, 63)
                == AtlasLayers.Visibility.BLANK_PAPER, "Colored but not inked must remain hidden");
        check(AtlasLayers.compose(true, Long.MIN_VALUE, 0, 63)
                == AtlasLayers.Visibility.GRAYSCALE, "Highest chunk bit works");
        check(AtlasLayers.compose(true, Long.MIN_VALUE, Long.MIN_VALUE, 63)
                == AtlasLayers.Visibility.COLOR, "Highest chunk is colored");
        check(AtlasLayers.compose(false, -1L, -1L, 0)
                == AtlasLayers.Visibility.NO_PAPER, "Paper is authoritative");
        try {
            AtlasLayers.compose(true, 0, 0, 64);
            throw new AssertionError("Bad chunk index accepted");
        } catch (IllegalArgumentException ok) { }
        check(MapSheetCoverage.covers(0, 0, 0, 0, 1, 512),
                "A paper sheet covers anchored page");
        check(!MapSheetCoverage.covers(0, 0, 300, 300, 1, 512),
                "A sheet cannot authorize unlimited area");
        check(!MapSheetCoverage.covers(0, 0, 0, 0, 0, 512),
                "No paper must not expose any map pages");
    }

    private static void independentPaymentAndVisibility() {
        SurveyLedger ledger = new SurveyLedger();
        var key = new SurveyLedger.ChunkKey("minecraft:overworld", 10, -4);
        // Pigment first. Do not force ink permission just because colors
        // are available. The ledger knows nothing about GPU layers.
        var pigment = ledger.survey(key, true, true,
                false, true, 0, 100, 4, 5, false, false);
        check(!pigment.firstSurvey() && pigment.firstColor()
                && pigment.inkCostUnits() == 0 && pigment.pigmentCostUnits() == 5,
                "Pigment-first must charge only pigment");
        check(AtlasLayers.compose(true, pigment.fullySurveyed(), pigment.colored())
                == AtlasLayers.Visibility.BLANK_PAPER,
                "Pigment-first remains blank until ink is unlocked");
        // Later ink, existing pigment is retained with no second color debit.
        var ink = ledger.survey(key, true, true,
                true, true, 100, 0, 4, 5, false, false);
        check(ink.firstSurvey() && !ink.firstColor()
                && ink.inkCostUnits() == 4 && ink.pigmentCostUnits() == 0,
                "Adding ink later should charge only its own layer");
        check(AtlasLayers.compose(true, ink.fullySurveyed(), ink.colored())
                == AtlasLayers.Visibility.COLOR, "Independent colors become visible");
        var repeat = ledger.survey(key, true, true,
                true, true, 0, 0, 4, 5, false, false);
        check(!repeat.firstColor() && !repeat.firstSurvey()
                && repeat.inkCostUnits() == 0 && repeat.pigmentCostUnits() == 0,
                "Cached terrain refresh must never debit materials again");
        var stored = new SurveyLedger(ledger.snapshot());
        check(stored.isColored(key) && stored.updateMayRender(key),
                "Independent permissions survive snapshot restore");
        // Even when the paper range changes, visibility is only controlled by
        // the page permission; permanent already purchased masks are stable.
        check(AtlasLayers.compose(false, stored.updateMayRender(key),
                stored.isColored(key)) == AtlasLayers.Visibility.NO_PAPER,
                "Paper gate applies without deleting existing ink/pigment data");
        var another = new SurveyLedger.ChunkKey("minecraft:overworld", 11, -4);
        var permanent = ledger.survey(another, true, true,
                true, true, 0, 0, 4, 5, true, true);
        check(permanent.firstSurvey() && permanent.firstColor()
                && permanent.inkCostUnits() == 0 && permanent.pigmentCostUnits() == 0,
                "Permanent permissions do not cost additional resources");
        var uncovered = new SurveyLedger.ChunkKey("minecraft:overworld", 12, -4);
        var blocked = ledger.survey(uncovered, true, false,
                true, true, 1000, 1000, 4, 5, false, false);
        check(!blocked.firstSurvey() && !blocked.firstColor(),
                "No paper = no surveying or coloring even if resources exist");
        System.out.println("PASS all 8 paper/ink/pigment gates, color-first, ink-later, permanent, restart snapshot, and no double charging");
    }

    public static void main(String[] args) {
        gates();
        independentPaymentAndVisibility();
    }
}
