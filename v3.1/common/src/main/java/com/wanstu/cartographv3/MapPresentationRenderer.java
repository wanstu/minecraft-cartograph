package com.wanstu.cartographv3;

/** Resolves drawing mode first, then delegates presentation to the active visual style. */
final class MapPresentationRenderer {
    private static final long PIPELINE_VERSION = 4L;

    private MapPresentationRenderer() {}

    static MapVisualStyleProcessor.Palette palette() {
        return CartographSettings.visualStyle().processor().palette();
    }

    static long renderSignature() {
        long base = SatelliteSurfaceRenderer.renderSignature();
        long mode = CartographSettings.drawingMode().ordinal() + 1L;
        long style = Integer.toUnsignedLong(CartographSettings.visualStyle().id().hashCode());
        long mixed = base ^ (PIPELINE_VERSION * 0x9e3779b97f4a7c15L);
        mixed ^= mode * 0xbf58476d1ce4e5b9L;
        mixed ^= style * 0x94d049bb133111ebL;
        return mixed == 0L ? 1L : mixed;
    }

    static SurfaceTile render(SurfaceTile tile) {
        if (!tile.hasSurfaceFacts()) return tile;
        return tile.withRgb(renderPixels(tile, 1));
    }

    static int[] renderPixels(SurfaceTile tile, int stride) {
        MapVisualStyleProcessor processor = CartographSettings.visualStyle().processor();
        return switch (CartographSettings.drawingMode()) {
            case SATELLITE -> processor.renderSatellite(SatelliteMapData.from(tile), stride);
            case HAND_DRAWN -> processor.renderHandDrawn(HandDrawnMapData.from(tile), stride);
        };
    }
}
