package com.wanstu.cartographv3;

final class SatelliteMapStyleRenderer implements MapStyleRenderer {
    static final SatelliteMapStyleRenderer INSTANCE = new SatelliteMapStyleRenderer();

    private SatelliteMapStyleRenderer() {}

    @Override
    public String id() {
        return "satellite";
    }

    @Override
    public String displayName() {
        return "卫星";
    }

    @Override
    public long signature() {
        return SatelliteSurfaceRenderer.renderSignature();
    }

    @Override
    public int[] renderTile(SurfaceTile facts) {
        if (facts == null || !facts.hasSurfaceFacts()) {
            return new int[SurfaceTile.PIXELS];
        }
        return SatelliteSurfaceRenderer.render(facts).rgb();
    }
}
