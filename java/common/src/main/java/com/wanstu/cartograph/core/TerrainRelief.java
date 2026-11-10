package com.wanstu.cartograph.core;

/** Directional relief computed from actual surface heights and a known-neighbor halo. */
public final class TerrainRelief {
    public static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;
    public static final int HALO = 12;
    public static final int MAX_WATER_DEPTH = 24;
    private static final int[] SUN_STEPS = {1, 2, 4, 8, HALO};
    private static final double SUN_UP = 0.58;
    private static final double DIAGONAL = Math.sqrt(2.0);

    private TerrainRelief() {}

    public static double light(short[] heights, int index, int stride) {
        short center = heights[index];
        if (center == UNKNOWN_HEIGHT) return 1.0;
        double dx = slope(heights, index, 1, 2, center) * 0.32
                + slope(heights, index, 1, 8, center) * 0.68;
        double dz = slope(heights, index, stride, 2, center) * 0.32
                + slope(heights, index, stride, 8, center) * 0.68;
        double diffuse = (SUN_UP + 0.58 * dx + 0.57 * dz) / Math.sqrt(1 + dx * dx + dz * dz);

        double horizon = 0;
        for (int step : SUN_STEPS) {
            short upSun = heights[index - step * (stride + 1)];
            if (upSun == UNKNOWN_HEIGHT) break;
            horizon = Math.max(horizon, (upSun - center) / (step * DIAGONAL));
        }
        double shadow = 0.19 * smooth((horizon - 0.69) / 0.8);
        double exposure = clamp((1 + (diffuse - SUN_UP) * 0.82) * (1 - shadow), 0.58, 1.30);
        return Math.round(exposure * 48) / 48.0;
    }

    private static double slope(short[] heights, int index, int axis, int distance, int center) {
        int lower = heights[index - axis * distance];
        int upper = heights[index + axis * distance];
        if (lower == UNKNOWN_HEIGHT && upper == UNKNOWN_HEIGHT) return 0;
        if (lower == UNKNOWN_HEIGHT) return (upper - center) / (double) distance;
        if (upper == UNKNOWN_HEIGHT) return (center - lower) / (double) distance;
        return (upper - lower) / (2.0 * distance);
    }

    public static int waterColor(int depth) {
        double deep = smooth((depth - 1) / 11.0);
        return rgb(mix(75, 40, deep), mix(105, 62, deep), mix(101, 74, deep));
    }

    public static int shade(int baseRgb, double light, boolean colored) {
        int red = baseRgb >> 16 & 255, green = baseRgb >> 8 & 255, blue = baseRgb & 255;
        int gray = (30 * red + 59 * green + 11 * blue + 50) / 100;
        double shadow = Math.max(0, 1.0 - light);
        if (!colored) {
            int value = channel(gray * light);
            return rgb(value, value, value);
        }
        return rgb(channel(mix(gray, red, 0.84) * light),
                channel(mix(gray, green, 0.84) * light + 2 * shadow),
                channel(mix(gray, blue, 0.84) * light + 12 * shadow));
    }

    private static int rgb(int r, int g, int b) { return r << 16 | g << 8 | b; }
    private static int mix(int a, int b, double ratio) { return channel(a + (b - a) * ratio); }
    private static int channel(double value) { return (int) Math.round(clamp(value, 0, 255)); }
    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
    private static double smooth(double value) {
        double t = clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
