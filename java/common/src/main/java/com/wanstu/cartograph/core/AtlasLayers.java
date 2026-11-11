package com.wanstu.cartograph.core;

/**
 * Three MATERIAL permissions, NOT three GPU layers or three terrain copies.
 *
 * Real sampled terrain remains a single shared source; these three gates
 * produce one composed image in a single texture upload.
 *
 * 1. Paper owns a page range (not one bit per block).
 * 2. Survey ink owns a 16x16 chunk's map visibility.
 * 3. Pigment independently owns its color permission; it may be bought before
 *    survey ink and never reveals an un-inked chunk by itself.
 *
 * The journal for ink/color is server-authoritative; a cached map texture can
 * only display an already-authorized state, never grant a permission.
 */
public final class AtlasLayers {
    public enum Visibility { NO_PAPER, BLANK_PAPER, GRAYSCALE, COLOR }

    private AtlasLayers() {}

    public static Visibility compose(boolean paper, boolean surveyed, boolean colored) {
        if (!paper) return Visibility.NO_PAPER;
        if (!surveyed) return Visibility.BLANK_PAPER;
        return colored ? Visibility.COLOR : Visibility.GRAYSCALE;
    }

    /** Bit-mapped chunk authorization within one 128x128 terrain page. */
    public static Visibility compose(boolean paper, long surveyedMask,
                                     long coloredMask, int chunk) {
        if (chunk < 0 || chunk >= 64) throw new IllegalArgumentException("chunk index");
        long bit = 1L << chunk;
        return compose(paper, (surveyedMask & bit) != 0, (coloredMask & bit) != 0);
    }
}
