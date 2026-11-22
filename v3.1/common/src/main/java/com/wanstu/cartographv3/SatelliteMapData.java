package com.wanstu.cartographv3;

/**
 * High-fidelity surface representation. It deliberately preserves block-scale
 * surface facts and delegates exact resource-pack-aware coloring to the
 * satellite renderer.
 */
record SatelliteMapData(SurfaceTile facts) {
    static SatelliteMapData from(SurfaceTile facts) {
        return new SatelliteMapData(facts);
    }

    int[] basePixels(int stride) {
        return SatelliteSurfaceRenderer.renderPixels(facts, stride);
    }

    int reliefAt(int x, int z, int fallback) {
        if (x < 0 || z < 0 || x >= SurfaceTile.SIZE || z >= SurfaceTile.SIZE) return fallback;
        int value = facts.reliefHeightAt(z * SurfaceTile.SIZE + x);
        return value == SurfaceTile.UNKNOWN_HEIGHT ? fallback : value;
    }

    boolean waterAt(int index) {
        return facts.waterDepthAt(index) > 0;
    }
}
