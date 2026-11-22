package com.wanstu.cartographv3;

/** One visual style must know how to present both map data structures. */
interface MapVisualStyleProcessor {
    record Palette(
            int background,
            int top,
            int panel,
            int field,
            int hover,
            int line,
            int text,
            int muted,
            int soft,
            int accent,
            int focus,
            int danger,
            int selected,
            int shadow,
            int gridLine,
            int overlay,
            int overlayLight,
            int rail,
            int switchOn,
            int markerLabel,
            int markerShadow) {}

    Palette palette();

    int[] renderSatellite(SatelliteMapData data, int stride);

    int[] renderHandDrawn(HandDrawnMapData data, int stride);
}
