package com.wanstu.cartographv3;

import java.util.Arrays;

/**
 * Abstract map representation derived from SurfaceTile facts.
 *
 * A 16x16 factual tile is aggregated into 4x4 semantic cells before any visual
 * style sees it. Cells carry terrain classes, water semantics and robust relief
 * facts rather than averaged block pixels.
 */
record HandDrawnMapData(
        ChunkKey key,
        int[] regionColor,
        int[] waterPercent,
        int[] waterDepth,
        int[] forestPercent,
        int[] canopyHeight,
        int[] averageRelief,
        int[] reliefRange) {

    static final int CELL_SIZE = 4;
    static final int GRID = SurfaceTile.SIZE / CELL_SIZE;
    static final int CELLS = GRID * GRID;
    static final int WATER_THRESHOLD = 19;
    static final int FOREST_THRESHOLD = 31;

    private enum TerrainClass {
        UNKNOWN(0),
        WATER(0x789daa),
        VEGETATION(0x73966b),
        DRY(0xb69b67),
        LIGHT_NEUTRAL(0xd9ddd5),
        STONE(0xaeb5aa),
        DARK_NEUTRAL(0x7f877e),
        COOL(0x8099a1),
        UNUSUAL(0x8b7b91),
        EARTH(0x9b7762);

        final int color;

        TerrainClass(int color) {
            this.color = color;
        }
    }

    static HandDrawnMapData from(SurfaceTile tile) {
        int[] source = SatelliteSurfaceRenderer.renderPixels(tile, 1);
        TerrainClass[] classes = new TerrainClass[CELLS];
        int[] water = new int[CELLS];
        int[] depth = new int[CELLS];
        int[] forest = new int[CELLS];
        int[] canopy = new int[CELLS];
        int[] relief = new int[CELLS];
        int[] range = new int[CELLS];

        for (int cellZ = 0; cellZ < GRID; cellZ++) {
            for (int cellX = 0; cellX < GRID; cellX++) {
                int[] votes = new int[TerrainClass.values().length];
                int[] heights = new int[CELL_SIZE * CELL_SIZE];
                int heightCount = 0;
                int waterCount = 0;
                int waterDepthTotal = 0;
                int canopyCount = 0;
                int canopyHeightTotal = 0;

                for (int dz = 0; dz < CELL_SIZE; dz++) {
                    for (int dx = 0; dx < CELL_SIZE; dx++) {
                        int x = cellX * CELL_SIZE + dx;
                        int z = cellZ * CELL_SIZE + dz;
                        int index = z * SurfaceTile.SIZE + x;
                        int sampleDepth = tile.waterDepthAt(index);
                        boolean isWater = sampleDepth > 0;
                        if (isWater) {
                            waterCount++;
                            waterDepthTotal += sampleDepth;
                        }

                        TerrainClass terrain = classify(source[index], isWater);
                        if (terrain != TerrainClass.UNKNOWN) votes[terrain.ordinal()]++;

                        int surfaceY = tile.surfaceHeightAt(index);
                        int reliefY = tile.reliefHeightAt(index);
                        if (!isWater
                                && surfaceY != SurfaceTile.UNKNOWN_HEIGHT
                                && reliefY != SurfaceTile.UNKNOWN_HEIGHT) {
                            int canopyDelta = surfaceY - reliefY;
                            if (canopyDelta >= 2) {
                                canopyCount++;
                                canopyHeightTotal += canopyDelta;
                            }
                        }

                        int h = reliefY;
                        if (h != SurfaceTile.UNKNOWN_HEIGHT) heights[heightCount++] = h;
                    }
                }

                int cell = cellZ * GRID + cellX;
                int waterPct = waterCount * 100 / (CELL_SIZE * CELL_SIZE);
                water[cell] = waterPct;
                depth[cell] = waterCount == 0
                        ? 0 : Math.round((float) waterDepthTotal / waterCount);
                forest[cell] = canopyCount * 100 / (CELL_SIZE * CELL_SIZE);
                canopy[cell] = canopyCount == 0
                        ? 0 : Math.round((float) canopyHeightTotal / canopyCount);
                classes[cell] = waterPct >= WATER_THRESHOLD
                        ? TerrainClass.WATER : dominant(votes);

                if (heightCount == 0) {
                    relief[cell] = SurfaceTile.UNKNOWN_HEIGHT;
                    range[cell] = 0;
                } else {
                    Arrays.sort(heights, 0, heightCount);
                    relief[cell] = heights[heightCount / 2];
                    int low = heights[Math.max(0, heightCount / 4)];
                    int high = heights[Math.min(heightCount - 1, (heightCount * 3) / 4)];
                    range[cell] = Math.max(0, high - low);
                }
            }
        }

        classes = smoothClasses(classes, water);
        depth = smoothWaterDepth(depth, water);
        forest = smoothForestPercent(forest, water);
        canopy = smoothCanopyHeight(canopy, forest);
        relief = smoothRelief(relief);
        range = smoothRanges(range);
        int[] colors = new int[CELLS];
        for (int i = 0; i < CELLS; i++) colors[i] = classes[i].color;

        return new HandDrawnMapData(tile.key(), colors, water, depth, forest, canopy, relief, range);
    }

    /**
     * Region color is categorical. Satellite textures only help classify the
     * terrain; their exact RGB values never enter the hand-drawn data model.
     */
    private static TerrainClass classify(int rgb, boolean water) {
        if (water) return TerrainClass.WATER;
        if (rgb == 0) return TerrainClass.UNKNOWN;

        double r = (rgb >>> 16 & 255) / 255.0;
        double g = (rgb >>> 8 & 255) / 255.0;
        double b = (rgb & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        double saturation = max == 0 ? 0 : delta / max;

        if (saturation < 0.14) {
            if (max >= 0.82) return TerrainClass.LIGHT_NEUTRAL;
            if (max >= 0.58) return TerrainClass.STONE;
            return TerrainClass.DARK_NEUTRAL;
        }

        double hue;
        if (max == r) hue = 60 * ((g - b) / delta);
        else if (max == g) hue = 60 * ((b - r) / delta + 2);
        else hue = 60 * ((r - g) / delta + 4);
        if (hue < 0) hue += 360;

        if (hue >= 55 && hue < 175) return TerrainClass.VEGETATION;
        if (hue >= 35 && hue < 55) return TerrainClass.DRY;
        if (hue >= 175 && hue < 255) return TerrainClass.COOL;
        if (hue >= 255 && hue < 330) return TerrainClass.UNUSUAL;
        if (hue < 35 || hue >= 330) return TerrainClass.EARTH;
        return TerrainClass.DRY;
    }

    private static TerrainClass dominant(int[] votes) {
        int best = 0;
        int bestCount = 0;
        for (int i = 1; i < votes.length; i++) {
            if (votes[i] > bestCount) {
                best = i;
                bestCount = votes[i];
            }
        }
        return bestCount == 0 ? TerrainClass.UNKNOWN : TerrainClass.values()[best];
    }

    /**
     * Remove isolated material islands while protecting meaningful water cells.
     */
    private static TerrainClass[] smoothClasses(TerrainClass[] source, int[] waterPercent) {
        TerrainClass[] smoothed = source.clone();
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int index = z * GRID + x;
                if (source[index] == TerrainClass.WATER
                        && waterPercent[index] >= WATER_THRESHOLD) continue;

                int[] votes = new int[TerrainClass.values().length];
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= GRID) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= GRID) continue;
                        TerrainClass terrain = source[sampleZ * GRID + sampleX];
                        if (terrain == TerrainClass.UNKNOWN) continue;
                        int weight = dx == 0 && dz == 0 ? 3
                                : (dx == 0 || dz == 0 ? 2 : 1);
                        votes[terrain.ordinal()] += weight;
                    }
                }
                TerrainClass majority = dominant(votes);
                if (majority != TerrainClass.UNKNOWN) smoothed[index] = majority;
            }
        }
        return smoothed;
    }

    /**
     * Water depth is a map-scale semantic. Smooth it only through cells that
     * actually contain water so depth shading never bleeds onto dry land.
     */
    private static int[] smoothWaterDepth(int[] source, int[] waterPercent) {
        int[] smoothed = source.clone();
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int index = z * GRID + x;
                if (waterPercent[index] < WATER_THRESHOLD) continue;

                int sum = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= GRID) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= GRID) continue;
                        int sample = sampleZ * GRID + sampleX;
                        if (waterPercent[sample] < WATER_THRESHOLD) continue;
                        int w = dx == 0 && dz == 0 ? 4 : 1;
                        sum += source[sample] * w;
                        weight += w;
                    }
                }
                if (weight > 0) smoothed[index] = Math.round((float) sum / weight);
            }
        }
        return smoothed;
    }

    /**
     * Forest density is derived from canopy coverage, not from green color.
     * WORLD_SURFACE includes leaves while MOTION_BLOCKING_NO_LEAVES does not,
     * so their height delta identifies actual canopy without block-ID lists.
     */
    private static int[] smoothForestPercent(int[] source, int[] waterPercent) {
        int[] smoothed = new int[source.length];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int index = z * GRID + x;
                if (waterPercent[index] >= WATER_THRESHOLD) {
                    smoothed[index] = 0;
                    continue;
                }

                int sum = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= GRID) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= GRID) continue;
                        int sample = sampleZ * GRID + sampleX;
                        if (waterPercent[sample] >= WATER_THRESHOLD) continue;
                        int w = dx == 0 && dz == 0 ? 4
                                : (dx == 0 || dz == 0 ? 2 : 1);
                        sum += source[sample] * w;
                        weight += w;
                    }
                }
                smoothed[index] = weight == 0 ? 0 : Math.round((float) sum / weight);
            }
        }
        return smoothed;
    }

    private static int[] smoothCanopyHeight(int[] source, int[] forestPercent) {
        int[] smoothed = new int[source.length];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int index = z * GRID + x;
                if (forestPercent[index] < FOREST_THRESHOLD) continue;

                int sum = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= GRID) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= GRID) continue;
                        int sample = sampleZ * GRID + sampleX;
                        if (forestPercent[sample] < FOREST_THRESHOLD || source[sample] <= 0) continue;
                        int w = dx == 0 && dz == 0 ? 4 : 1;
                        sum += source[sample] * w;
                        weight += w;
                    }
                }
                smoothed[index] = weight == 0 ? source[index]
                        : Math.round((float) sum / weight);
            }
        }
        return smoothed;
    }

    private static int[] smoothRelief(int[] source) {
        int[] smoothed = new int[source.length];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int sum = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= GRID) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= GRID) continue;
                        int value = source[sampleZ * GRID + sampleX];
                        if (value == SurfaceTile.UNKNOWN_HEIGHT) continue;
                        int w = dx == 0 && dz == 0 ? 4 : 1;
                        sum += value * w;
                        weight += w;
                    }
                }
                smoothed[z * GRID + x] = weight == 0
                        ? SurfaceTile.UNKNOWN_HEIGHT : Math.round((float) sum / weight);
            }
        }
        return smoothed;
    }

    private static int[] smoothRanges(int[] source) {
        int[] smoothed = new int[source.length];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int sum = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= GRID) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= GRID) continue;
                        int w = dx == 0 && dz == 0 ? 3 : 1;
                        sum += source[sampleZ * GRID + sampleX] * w;
                        weight += w;
                    }
                }
                int value = weight == 0 ? 0 : Math.round((float) sum / weight);
                smoothed[z * GRID + x] = value < 5 ? 0 : value;
            }
        }
        return smoothed;
    }

    int[] abstractPixels(int stride) {
        int edge = SurfaceTile.SIZE / stride;
        int[] out = new int[edge * edge];
        int sampleOffset = Math.max(0, stride / 2);
        for (int z = 0; z < edge; z++) {
            for (int x = 0; x < edge; x++) {
                double localX = Math.min(SurfaceTile.SIZE - 1, x * stride + sampleOffset);
                double localZ = Math.min(SurfaceTile.SIZE - 1, z * stride + sampleOffset);
                out[z * edge + x] = interpolateColor(localX, localZ);
            }
        }
        return out;
    }

    int waterPercentAtPixel(int localX, int localZ) {
        return interpolateScalar(waterPercent, localX, localZ, Integer.MIN_VALUE);
    }

    int waterDepthAtPixel(int localX, int localZ) {
        return interpolateScalar(waterDepth, localX, localZ, Integer.MIN_VALUE);
    }

    int shorelineStrengthAtPixel(int localX, int localZ, int radius) {
        int center = waterPercentAtPixel(localX, localZ);
        if (center == Integer.MIN_VALUE) return 0;

        int sampleRadius = Math.max(1, Math.min(CELL_SIZE, radius));
        boolean water = center >= WATER_THRESHOLD;
        int[] samples = {
                waterPercentAtPixel(Math.max(0, localX - sampleRadius), localZ),
                waterPercentAtPixel(Math.min(SurfaceTile.SIZE - 1, localX + sampleRadius), localZ),
                waterPercentAtPixel(localX, Math.max(0, localZ - sampleRadius)),
                waterPercentAtPixel(localX, Math.min(SurfaceTile.SIZE - 1, localZ + sampleRadius))
        };

        int strongest = 0;
        for (int sample : samples) {
            if (sample == Integer.MIN_VALUE) continue;
            if ((sample >= WATER_THRESHOLD) != water) return 100;
            strongest = Math.max(strongest, Math.abs(center - sample));
        }

        if (center >= WATER_THRESHOLD - 6 && center <= WATER_THRESHOLD + 20) {
            strongest = Math.max(strongest, 32);
        }
        return Math.min(100, strongest * 2);
    }

    int forestPercentAtPixel(int localX, int localZ) {
        return interpolateScalar(forestPercent, localX, localZ, Integer.MIN_VALUE);
    }

    int canopyHeightAtPixel(int localX, int localZ) {
        return interpolateScalar(canopyHeight, localX, localZ, Integer.MIN_VALUE);
    }

    int forestEdgeStrengthAtPixel(int localX, int localZ, int radius) {
        int center = forestPercentAtPixel(localX, localZ);
        if (center == Integer.MIN_VALUE) return 0;

        int sampleRadius = Math.max(1, Math.min(CELL_SIZE, radius));
        boolean forest = center >= FOREST_THRESHOLD;
        int[] samples = {
                forestPercentAtPixel(Math.max(0, localX - sampleRadius), localZ),
                forestPercentAtPixel(Math.min(SurfaceTile.SIZE - 1, localX + sampleRadius), localZ),
                forestPercentAtPixel(localX, Math.max(0, localZ - sampleRadius)),
                forestPercentAtPixel(localX, Math.min(SurfaceTile.SIZE - 1, localZ + sampleRadius))
        };

        int strongest = 0;
        for (int sample : samples) {
            if (sample == Integer.MIN_VALUE) continue;
            if ((sample >= FOREST_THRESHOLD) != forest) return 100;
            strongest = Math.max(strongest, Math.abs(center - sample));
        }

        if (center >= FOREST_THRESHOLD - 8 && center <= FOREST_THRESHOLD + 20) {
            strongest = Math.max(strongest, 28);
        }
        return Math.min(100, strongest * 2);
    }

    int averageReliefAtPixel(int localX, int localZ) {
        return interpolateScalar(averageRelief, localX, localZ, SurfaceTile.UNKNOWN_HEIGHT);
    }

    int reliefRangeAtPixel(int localX, int localZ) {
        return interpolateScalar(reliefRange, localX, localZ, Integer.MIN_VALUE);
    }

    private int interpolateScalar(int[] values, double localX, double localZ, int unknown) {
        SampleWindow window = sampleWindow(localX, localZ);
        int[] samples = {
                values[window.z0 * GRID + window.x0],
                values[window.z0 * GRID + window.x1],
                values[window.z1 * GRID + window.x0],
                values[window.z1 * GRID + window.x1]
        };
        double[] weights = {
                (1 - window.tx) * (1 - window.tz),
                window.tx * (1 - window.tz),
                (1 - window.tx) * window.tz,
                window.tx * window.tz
        };
        double sum = 0;
        double weight = 0;
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] == unknown) continue;
            sum += samples[i] * weights[i];
            weight += weights[i];
        }
        return weight == 0 ? unknown : (int) Math.round(sum / weight);
    }

    private int interpolateColor(double localX, double localZ) {
        SampleWindow window = sampleWindow(localX, localZ);
        int top = mix(
                regionColor[window.z0 * GRID + window.x0],
                regionColor[window.z0 * GRID + window.x1],
                window.tx);
        int bottom = mix(
                regionColor[window.z1 * GRID + window.x0],
                regionColor[window.z1 * GRID + window.x1],
                window.tx);
        return mix(top, bottom, window.tz);
    }

    private SampleWindow sampleWindow(double localX, double localZ) {
        double gx = localX / CELL_SIZE - 0.5;
        double gz = localZ / CELL_SIZE - 0.5;
        int floorX = (int) Math.floor(gx);
        int floorZ = (int) Math.floor(gz);
        int x0 = Math.clamp(floorX, 0, GRID - 1);
        int z0 = Math.clamp(floorZ, 0, GRID - 1);
        int x1 = Math.min(GRID - 1, x0 + 1);
        int z1 = Math.min(GRID - 1, z0 + 1);
        double tx = x0 == x1 ? 0 : Math.max(0, Math.min(1, gx - floorX));
        double tz = z0 == z1 ? 0 : Math.max(0, Math.min(1, gz - floorZ));
        return new SampleWindow(x0, z0, x1, z1, tx, tz);
    }

    private record SampleWindow(int x0, int z0, int x1, int z1, double tx, double tz) {}

    private static int mix(int a, int b, double amount) {
        if (a == 0) return b;
        if (b == 0) return a;
        int r = (int) Math.round((a >>> 16 & 255) * (1 - amount) + (b >>> 16 & 255) * amount);
        int g = (int) Math.round((a >>> 8 & 255) * (1 - amount) + (b >>> 8 & 255) * amount);
        int blue = (int) Math.round((a & 255) * (1 - amount) + (b & 255) * amount);
        return r << 16 | g << 8 | blue;
    }
}
