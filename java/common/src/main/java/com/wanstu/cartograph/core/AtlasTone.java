package com.wanstu.cartograph.core;

/**
 * O(128²) binary majority filter for the two-color paper atlas. The raw
 * vanilla map-color threshold can otherwise look like pixel noise when plants,
 * grass and leaves alternate on the terrain surface.
 *
 * Classification: 0=unknown (never drawn), 1=paper-light, 2=ink-dark,
 * 3=already colored (preserve). Known colored/unknown cells never contribute
 * to the vote of a neighboring monochrome pixel.
 */
public final class AtlasTone {
    private static final int SIDE = 128;
    private static final int STRIDE = SIDE + 1;
    private AtlasTone() {}

    public static byte[] smooth(byte[] source) {
        if (source == null || source.length != SIDE * SIDE)
            throw new IllegalArgumentException("Expected 128x128 map tone");
        int[] valid = new int[STRIDE * STRIDE];
        int[] dark = new int[STRIDE * STRIDE];
        for (int z = 1; z <= SIDE; z++) {
            int rowValid = 0, rowDark = 0;
            for (int x = 1; x <= SIDE; x++) {
                int value = source[(z - 1) * SIDE + x - 1];
                if (value == 1 || value == 2) {
                    rowValid++;
                    if (value == 2) rowDark++;
                }
                int a = z * STRIDE + x;
                valid[a] = valid[a - STRIDE] + rowValid;
                dark[a] = dark[a - STRIDE] + rowDark;
            }
        }
        byte[] output = source.clone();
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
            int offset = z * SIDE + x;
            int value = Byte.toUnsignedInt(source[offset]);
            if (value != 1 && value != 2) continue;
            int x0 = Math.max(0, x - 2), x1 = Math.min(SIDE, x + 3);
            int z0 = Math.max(0, z - 2), z1 = Math.min(SIDE, z + 3);
            int count = area(valid, x0, z0, x1, z1);
            if (count >= 9) {
                int black = area(dark, x0, z0, x1, z1);
                output[offset] = (byte) (black * 2 >= count ? 2 : 1);
            }
        }
        return output;
    }

    private static int area(int[] integral, int x0, int z0, int x1, int z1) {
        return integral[z1 * STRIDE + x1]
                - integral[z0 * STRIDE + x1]
                - integral[z1 * STRIDE + x0]
                + integral[z0 * STRIDE + x0];
    }
}
