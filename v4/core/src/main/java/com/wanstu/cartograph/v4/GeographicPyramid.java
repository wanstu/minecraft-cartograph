package com.wanstu.cartograph.v4;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Reference aggregation, not a production storage implementation. */
public final class GeographicPyramid {
    public static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;
    private static final int CLASSES = FeatureDictionary.Terrain.values().length;

    // RGB channels must be linear-light, unsigned 16-bit. Height validity is independent.
    public record Sample(int featureId, int surfaceY, int groundY, int waterDepth,
                         int red, int green, int blue) {
        public Sample {
            if (featureId <= 0 || waterDepth < 0 || waterDepth > 65535 ||
                    !channel(red) || !channel(green) || !channel(blue))
                throw new IllegalArgumentException("Invalid known sample");
        }
        private static boolean channel(int value) { return value >= 0 && value <= 65535; }
    }

    public record Cell(int level, int x, int z) {
        public Cell parent() {
            return new Cell(level + 1, Math.floorDiv(x, 2), Math.floorDiv(z, 2));
        }
    }

    public static final class Stats {
        private long known, surfaceCount, groundCount, surfaceSum, groundSum;
        private long redSum, greenSum, blueSum, wetCount, depthSum;
        private int minGround = Integer.MAX_VALUE, maxGround = Integer.MIN_VALUE;
        private final long[] histogram = new long[CLASSES];

        private void add(Stats s) {
            known += s.known; surfaceCount += s.surfaceCount; groundCount += s.groundCount;
            surfaceSum += s.surfaceSum; groundSum += s.groundSum;
            redSum += s.redSum; greenSum += s.greenSum; blueSum += s.blueSum;
            wetCount += s.wetCount; depthSum += s.depthSum;
            minGround = Math.min(minGround, s.minGround);
            maxGround = Math.max(maxGround, s.maxGround);
            for (int i = 0; i < CLASSES; i++) histogram[i] += s.histogram[i];
        }
        public long known() { return known; }
        public long surfaceCount() { return surfaceCount; }
        public long groundCount() { return groundCount; }
        public long surfaceSum() { return surfaceSum; }
        public long groundSum() { return groundSum; }
        public long redSum() { return redSum; }
        public long greenSum() { return greenSum; }
        public long blueSum() { return blueSum; }
        public long wetCount() { return wetCount; }
        public long depthSum() { return depthSum; }
        public int minGround() { return groundCount == 0 ? UNKNOWN_HEIGHT : minGround; }
        public int maxGround() { return groundCount == 0 ? UNKNOWN_HEIGHT : maxGround; }
        public long[] histogram() { return histogram.clone(); }
        public long count(FeatureDictionary.Terrain t) { return histogram[t.ordinal()]; }
    }

    private final int maxLevel;
    private final FeatureDictionary dictionary;
    private final Map<Cell, Sample> leaves = new HashMap<>();
    private final Map<Cell, Stats> stats = new HashMap<>();

    public GeographicPyramid(int maxLevel, FeatureDictionary dictionary) {
        if (maxLevel < 0 || maxLevel > 10) throw new IllegalArgumentException("LOD");
        this.maxLevel = maxLevel;
        this.dictionary = Objects.requireNonNull(dictionary);
    }

    public Stats at(Cell cell) { return stats.get(cell); }
    public Sample sampleAt(int x, int z) { return leaves.get(new Cell(0, x, z)); }
    public long capacity(Cell cell) {
        if (cell.level < 0 || cell.level > maxLevel) throw new IllegalArgumentException("LOD");
        return 1L << (2 * cell.level);
    }

    /** Returns updated cell count; null removes observed coverage. */
    public int update(int x, int z, Sample sample) {
        Cell cell = new Cell(0, x, z);
        if (Objects.equals(leaves.get(cell), sample)) return 0;
        if (sample == null) {
            leaves.remove(cell);
            stats.remove(cell);
        } else {
            var feature = dictionary.get(sample.featureId());
            Stats leaf = new Stats();
            leaf.known = 1;
            if (sample.surfaceY() != UNKNOWN_HEIGHT) {
                leaf.surfaceCount = 1; leaf.surfaceSum = sample.surfaceY();
            }
            if (sample.groundY() != UNKNOWN_HEIGHT) {
                leaf.groundCount = 1; leaf.groundSum = sample.groundY();
                leaf.minGround = leaf.maxGround = sample.groundY();
            }
            leaf.redSum = sample.red(); leaf.greenSum = sample.green(); leaf.blueSum = sample.blue();
            if (sample.waterDepth() > 0) { leaf.wetCount = 1; leaf.depthSum = sample.waterDepth(); }
            leaf.histogram[feature.terrain().ordinal()] = 1;
            leaves.put(cell, sample);
            stats.put(cell, leaf);
        }
        for (int level = 1; level <= maxLevel; level++) {
            cell = cell.parent();
            Stats merged = new Stats();
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                Stats child = stats.get(new Cell(level - 1, cell.x * 2 + dx, cell.z * 2 + dz));
                if (child != null) merged.add(child);
            }
            if (merged.known == 0) stats.remove(cell); else stats.put(cell, merged);
        }
        return maxLevel + 1;
    }
}
