package com.wanstu.cartograph;

/**
 * Dye-only copy-on-write pixel patch. Never changes paper, ink coverage,
 * terrain heights, road/structure facts or ink/glow/paper balances.
 * The server-signed bit mask is intersected with already surveyed bits; a
 * stale local page can never reveal pixels from un-inked world chunks.
 */
final class ColorPatchApplier {
    private ColorPatchApplier() {}

    static MapNetwork.Page apply(MapNetwork.Page old, MapNetwork.ColorDelta delta) {
        if (old == null || !old.complete() || !old.inRange()
                || !old.atlasId().equals(delta.atlasId())
                || !old.dimension().equals(delta.dimension())
                || old.pageX() != delta.pageX() || old.pageZ() != delta.pageZ()
                || old.pixels().length != MapNetwork.Page.PIXELS)
            return null;
        byte[] output = old.pixels().clone();
        long allowed = delta.chunkMask() & old.surveyedChunks();
        int offset = 0;
        for (int index = 0; index < 64; index++) {
            if ((delta.chunkMask() & (1L << index)) == 0) continue;
            if ((allowed & (1L << index)) != 0) {
                int px = index % 8, pz = index / 8;
                for (int y = 0; y < 16; y++)
                    System.arraycopy(delta.pixels(), offset + y * 16,
                            output, (pz * 16 + y) * 128 + px * 16, 16);
            }
            offset += 256;
        }
        return new MapNetwork.Page(old.atlasId(), old.dimension(), old.pageX(), old.pageZ(),
                output, old.features(), old.heights(), old.waterDepths(),
                old.surveyedChunks(), old.coloredChunks() | allowed,
                old.inkUnits(), delta.pigmentUnits(), old.glowUnits(), old.compass(),
                old.inRange(), old.complete(), old.cachedOnly(), old.scanned(),
                old.generated(), old.painted(), old.anchorDimension(), old.anchorX(),
                old.anchorZ(), old.totalPages(), old.sheetSideBlocks(), old.totalSurveyed(),
                Math.max(old.totalColored(), delta.totalColored()),
                old.markers(), old.structures());
    }
}
