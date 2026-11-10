package com.wanstu.cartograph.core;

import java.util.HashSet;
import java.util.Set;

/**
 * World/atlas-specific irreversible first-survey payment ledger.
 *
 * Store and restore a snapshot with the level's saved data. Mutation is
 * confined to the server's main thread by the adapter, not to a renderer.
 * An already-surveyed block may be re-rendered any number of times free.
 */
public final class SurveyLedger {
    public record ChunkKey(String dimension, int x, int z) {
        public ChunkKey {
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension required");
        }
    }

    public record Snapshot(Set<ChunkKey> surveyed, Set<ChunkKey> colorized) {
        public Snapshot {
            surveyed = Set.copyOf(surveyed);
            colorized = Set.copyOf(colorized);
        }
    }

    public record Result(boolean firstSurvey, boolean firstColor, int inkCostUnits,
                         int pigmentCostUnits, boolean fullySurveyed, boolean colored) {}

    private final Set<ChunkKey> surveyed = new HashSet<>();
    private final Set<ChunkKey> colorized = new HashSet<>();

    public SurveyLedger() {}

    public SurveyLedger(Snapshot snapshot) {
        surveyed.addAll(snapshot.surveyed());
        colorized.addAll(snapshot.colorized());
    }

    /** Advance each world-aligned mask independently after a real snapshot is ready. */
    public Result survey(ChunkKey key, boolean generated, boolean pageCovered,
                         boolean inkUnlocked, boolean colorsUnlocked,
                         int inkAvailable, int pigmentAvailable, int inkCost, int pigmentCost,
                         boolean permanentInk, boolean permanentColor) {
        if (inkCost < 1 || pigmentCost < 1) throw new IllegalArgumentException("Cost must be positive");
        boolean firstSurvey = false, firstColor = false;
        if (generated && pageCovered) {
            if (inkUnlocked && (permanentInk || inkAvailable >= inkCost)) firstSurvey = surveyed.add(key);
            if (colorsUnlocked && (permanentColor || pigmentAvailable >= pigmentCost)) firstColor = colorized.add(key);
        }
        return new Result(firstSurvey, firstColor,
                firstSurvey && !permanentInk ? inkCost : 0,
                firstColor && !permanentColor ? pigmentCost : 0,
                surveyed.contains(key), colorized.contains(key));
    }

    public int surveyedCount() { return surveyed.size(); }
    public int colorizedCount() { return colorized.size(); }

    /** Rendering an already-surveyed chunk is always free; do not call survey for dirty-only updates. */
    public boolean updateMayRender(ChunkKey key) { return surveyed.contains(key); }
    public boolean isColored(ChunkKey key) { return colorized.contains(key); }

    public Snapshot snapshot() { return new Snapshot(surveyed, colorized); }
}
