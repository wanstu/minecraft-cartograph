package com.wanstu.cartographv3;

final class SurfaceColors {
    static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    private SurfaceColors() {}

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
                int neighbours = 0;
                int sum = 0;
                if (x > 0 && heights[i - 1] != UNKNOWN_HEIGHT) {
                    sum += heights[i] - heights[i - 1];
                    neighbours++;
                }
                if (z > 0 && heights[i - 16] != UNKNOWN_HEIGHT) {
                    sum += heights[i] - heights[i - 16];
                    neighbours++;
                }
                double delta = neighbours == 0 ? 0.0 : (double) sum / neighbours;
                double factor = Math.max(0.78, Math.min(1.20, 1.0 + delta * 0.035));
                int r = Math.min(255, (int) (((base >>> 16) & 255) * factor));
                int g = Math.min(255, (int) (((base >>> 8) & 255) * factor));
                int b = Math.min(255, (int) ((base & 255) * factor));
                out[i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }
}
