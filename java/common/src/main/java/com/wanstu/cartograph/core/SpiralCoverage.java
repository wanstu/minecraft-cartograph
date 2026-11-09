package com.wanstu.cartograph.core;

/** Deterministic contiguous page placement around the page where an atlas was opened. */
public final class SpiralCoverage {
    private SpiralCoverage() {}
    public record Offset(int x, int z) {}

    public static Offset offsetFor(int index) {
        if (index < 0) throw new IllegalArgumentException("index must be >= 0");
        if (index == 0) return new Offset(0, 0);
        int x = 0, z = 0, n = 0, step = 1;
        while (true) {
            for (int i = 0; i < step; i++) { x++; if (++n == index) return new Offset(x, z); }
            for (int i = 0; i < step; i++) { z++; if (++n == index) return new Offset(x, z); }
            step++;
            for (int i = 0; i < step; i++) { x--; if (++n == index) return new Offset(x, z); }
            for (int i = 0; i < step; i++) { z--; if (++n == index) return new Offset(x, z); }
            step++;
        }
    }

    public static boolean covers(int anchorX, int anchorZ, int pageX, int pageZ, int pages) {
        long dx = (long) pageX - anchorX, dz = (long) pageZ - anchorZ;
        long ring = Math.max(Math.abs(dx), Math.abs(dz));
        if (ring == 0) return pages >= 1;
        if (ring > pages) return false; // Reject enormous coordinates before the square can overflow.
        // The first point on ring r has spiral index (2r-1)^2.
        // Work out the offset on that ring in O(1), with long intermediates
        // so maliciously large client coordinates cannot overflow.
        long first = (2L * ring - 1) * (2L * ring - 1);
        if (first >= pages) return false;
        long offset;
        if (dx == ring && dz >= 1 - ring) offset = dz + ring - 1;
        else if (dz == ring) offset = 2 * ring + (ring - 1 - dx);
        else if (dx == -ring) offset = 4 * ring + (ring - 1 - dz);
        else offset = 6 * ring + (dx + ring - 1);
        return first + offset < pages;
    }
}
