package com.wanstu.cartographv3;

import net.minecraft.world.level.material.MapColor;

final class SurfaceColors {
    static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    private SurfaceColors() {}

    static int surfaceColor(MapColor color, int waterDepth, int worldX, int worldZ) {
        if (color != MapColor.WATER) return color.col & 0x00ffffff;

        double depthShade = Math.max(0, waterDepth) * 0.1
                + (((worldX + worldZ) & 1) == 0 ? 0.0 : 0.2);
        MapColor.Brightness brightness;
        if (depthShade < 0.5) {
            brightness = MapColor.Brightness.HIGH;
        } else if (depthShade > 0.9) {
            brightness = MapColor.Brightness.LOW;
        } else {
            brightness = MapColor.Brightness.NORMAL;
        }
        return color.calculateRGBColor(brightness) & 0x00ffffff;
    }

    static int[] shade(int[] rgb, short[] heights) {
        int[] out = rgb.clone();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int base = rgb[i] & 0x00ffffff;
                if (base == 0 || heights[i] == UNKNOWN_HEIGHT) {
                    out[i] = 0;
                    continue;
                }

                double slopeX = axisSlope(heights, x, z, 1, 0);
                double slopeZ = axisSlope(heights, x, z, 0, 1);
                double sum = 0.0;
                int axes = 0;
                if (!Double.isNaN(slopeX)) {
                    sum += slopeX;
                    axes++;
                }
                if (!Double.isNaN(slopeZ)) {
                    sum += slopeZ;
                    axes++;
                }

                double delta = axes == 0 ? 0.0 : sum / axes;
                double factor = Math.max(0.78, Math.min(1.20, 1.0 + delta * 0.035));
                int r = Math.min(255, (int) (((base >>> 16) & 255) * factor));
                int g = Math.min(255, (int) (((base >>> 8) & 255) * factor));
                int b = Math.min(255, (int) ((base & 255) * factor));
                out[i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    private static double axisSlope(short[] heights, int x, int z, int dx, int dz) {
        int centerIndex = z * 16 + x;
        short center = heights[centerIndex];

        int previousX = x - dx;
        int previousZ = z - dz;
        int nextX = x + dx;
        int nextZ = z + dz;

        boolean hasPrevious = previousX >= 0 && previousX < 16
                && previousZ >= 0 && previousZ < 16;
        boolean hasNext = nextX >= 0 && nextX < 16
                && nextZ >= 0 && nextZ < 16;

        short previous = hasPrevious
                ? heights[previousZ * 16 + previousX]
                : UNKNOWN_HEIGHT;
        short next = hasNext
                ? heights[nextZ * 16 + nextX]
                : UNKNOWN_HEIGHT;

        boolean previousKnown = previous != UNKNOWN_HEIGHT;
        boolean nextKnown = next != UNKNOWN_HEIGHT;
        if (previousKnown && nextKnown) return (next - previous) * 0.5;
        if (previousKnown) return center - previous;
        if (nextKnown) return next - center;
        return Double.NaN;
    }
}
