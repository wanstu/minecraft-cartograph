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
            if (!surveyed.containsAll(colorized)) throw new IllegalArgumentException("colorized must be surveyed");
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

    /**
     * Only call after the adapter proves the chunk is already persisted
     * AND the atlas has paid/allocated a real 128x128 page for this chunk.
     * Units are integer tenths; 0 multiplier is valid but never unlocks
     * a missing ink/primary-color upgrade.
     *
     * The caller must immediately persist ledger + resource balances
     * atomically to prevent a crash granting a free page.
     */
    public Result survey(ChunkKey key, boolean persisted, boolean pageCovered,
                         boolean inkUnlocked, boolean colorsUnlocked,
                         int inkAvailable, int pigmentAvailable,
                         int inkCost, int pigmentCost) {
        boolean existing = surveyed.contains(key);
        if (!persisted || !pageCovered || (!existing && !inkUnlocked))
            return new Result(false, false, 0, 0, existing, colorized.contains(key));
        if (!existing && inkAvailable < inkCost)
            return new Result(false, false, 0, 0, false, false);
        boolean firstSurvey = false;
        if (!existing) {
            surveyed.add(key);
            firstSurvey = true;
        }
        boolean firstColor = false;
        if (colorsUnlocked && !colorized.contains(key)
                && pigmentAvailable >= pigmentCost) {
            colorized.add(key);
            firstColor = true;
        }
        return new Result(firstSurvey, firstColor, firstSurvey ? inkCost : 0,
                firstColor ? pigmentCost : 0, true, colorized.contains(key));
    }

    /** Rendering an already-surveyed chunk is always free; do not call survey for dirty-only updates. */
    public boolean updateMayRender(ChunkKey key) { return surveyed.contains(key); }
    public boolean isColored(ChunkKey key) { return colorized.contains(key); }

    public Snapshot snapshot() { return new Snapshot(surveyed, colorized); }
}
