package com.wanstu.cartographv3;

/**
 * Abstract map representation derived from SurfaceTile facts.
 *
 * A 16x16 factual tile is aggregated into 4x4 semantic cells. This intentionally
 * removes block-scale noise before any visual style sees the data. Future
 * semantic layers may enrich this structure without changing DrawingMode.
 */
record HandDrawnMapData(
        ChunkKey key,
        int[] regionColor,
        int[] waterPercent,
        int[] averageRelief,
        int[] reliefRange) {

    static final int CELL_SIZE = 4;
    static final int GRID = SurfaceTile.SIZE / CELL_SIZE;
    static final int CELLS = GRID * GRID;

    static HandDrawnMapData from(SurfaceTile tile) {
        int[] source = SatelliteSurfaceRenderer.renderPixels(tile, 1);
        int[] colors = new int[CELLS];
        int[] water = new int[CELLS];
        int[] relief = new int[CELLS];
        int[] range = new int[CELLS];

        for (int cellZ = 0; cellZ < GRID; cellZ++) {
            for (int cellX = 0; cellX < GRID; cellX++) {
                long r = 0, g = 0, b = 0;
                int count = 0;
                int waterCount = 0;
                int reliefSum = 0;
                int reliefCount = 0;
                int minRelief = Integer.MAX_VALUE;
                int maxRelief = Integer.MIN_VALUE;

                for (int dz = 0; dz < CELL_SIZE; dz++) {
                    for (int dx = 0; dx < CELL_SIZE; dx++) {
                        int x = cellX * CELL_SIZE + dx;
                        int z = cellZ * CELL_SIZE + dz;
                        int index = z * SurfaceTile.SIZE + x;
                        int rgb = source[index];
                        if (rgb != 0) {
                            r += rgb >>> 16 & 255;
                            g += rgb >>> 8 & 255;
                            b += rgb & 255;
                            count++;
                        }
                        if (tile.waterDepthAt(index) > 0) waterCount++;
                        int h = tile.reliefHeightAt(index);
                        if (h != SurfaceTile.UNKNOWN_HEIGHT) {
                            reliefSum += h;
                            reliefCount++;
                            minRelief = Math.min(minRelief, h);
                            maxRelief = Math.max(maxRelief, h);
                        }
                    }
                }

                int cell = cellZ * GRID + cellX;
                colors[cell] = count == 0 ? 0
                        : ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
                water[cell] = waterCount * 100 / (CELL_SIZE * CELL_SIZE);
                relief[cell] = reliefCount == 0 ? SurfaceTile.UNKNOWN_HEIGHT : reliefSum / reliefCount;
                range[cell] = reliefCount == 0 ? 0 : maxRelief - minRelief;
            }
        }
        return new HandDrawnMapData(tile.key(), colors, water, relief, range);
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
        return waterPercent[cellIndex(localX, localZ)];
    }

    int averageReliefAtPixel(int localX, int localZ) {
        return averageRelief[cellIndex(localX, localZ)];
    }

    int reliefRangeAtPixel(int localX, int localZ) {
        return reliefRange[cellIndex(localX, localZ)];
    }

    private int cellIndex(int localX, int localZ) {
        int cellX = Math.clamp(localX / CELL_SIZE, 0, GRID - 1);
        int cellZ = Math.clamp(localZ / CELL_SIZE, 0, GRID - 1);
        return cellZ * GRID + cellX;
    }

    private int interpolateColor(double localX, double localZ) {
        double gx = localX / CELL_SIZE - 0.5;
        double gz = localZ / CELL_SIZE - 0.5;
        int x0 = Math.clamp((int) Math.floor(gx), 0, GRID - 1);
        int z0 = Math.clamp((int) Math.floor(gz), 0, GRID - 1);
        int x1 = Math.min(GRID - 1, x0 + 1);
        int z1 = Math.min(GRID - 1, z0 + 1);
        double tx = Math.max(0, Math.min(1, gx - Math.floor(gx)));
        double tz = Math.max(0, Math.min(1, gz - Math.floor(gz)));

        int top = mix(regionColor[z0 * GRID + x0], regionColor[z0 * GRID + x1], tx);
        int bottom = mix(regionColor[z1 * GRID + x0], regionColor[z1 * GRID + x1], tx);
        return mix(top, bottom, tz);
    }

    private static int mix(int a, int b, double amount) {
        if (a == 0) return b;
        if (b == 0) return a;
        int r = (int) Math.round((a >>> 16 & 255) * (1 - amount) + (b >>> 16 & 255) * amount);
        int g = (int) Math.round((a >>> 8 & 255) * (1 - amount) + (b >>> 8 & 255) * amount);
        int blue = (int) Math.round((a & 255) * (1 - amount) + (b & 255) * amount);
        return r << 16 | g << 8 | blue;
    }
}
