package com.wanstu.cartograph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Copy-on-write update of ONE 16x16 pixel chunk inside a paid native map page.
 * Never alters authorization masks or ink/pigment credits. May be tested
 * without Minecraft client, render thread, or live world. */
final class TerrainPatchApplier {
    private TerrainPatchApplier() {}
    static MapNetwork.Page apply(MapNetwork.Page old, MapNetwork.TerrainPatch patch) {
        int pageX = Math.floorDiv(patch.chunkX(), 8), pageZ = Math.floorDiv(patch.chunkZ(), 8);
        if (old == null || !old.complete() || !old.inRange() || old.painted() <= 0
                || !old.atlasId().equals(patch.atlasId())
                || !old.dimension().equals(patch.dimension())
                || old.pageX() != pageX || old.pageZ() != pageZ
                || old.heights().length != MapNetwork.Page.PIXELS
                || old.waterDepths().length != MapNetwork.Page.PIXELS)
            return null;
        int x = Math.floorMod(patch.chunkX(), 8), z = Math.floorMod(patch.chunkZ(), 8);
        int index = z * 8 + x;
        if ((old.surveyedChunks() & 1L << index) == 0) return null;
        byte[] pixels = old.pixels().clone(), features = old.features().clone();
        short[] heights = old.heights().clone();
        byte[] depths = old.waterDepths().clone();
        for (int row = 0; row < 16; row++) {
            int dest = (z * 16 + row) * 128 + x * 16, src = row * 16;
            System.arraycopy(patch.pixels(), src, pixels, dest, 16);
            System.arraycopy(patch.features(), src, features, dest, 16);
            System.arraycopy(patch.heights(), src, heights, dest, 16);
            System.arraycopy(patch.waterDepths(), src, depths, dest, 16);
        }
        List<SurfaceStructure> structures = new ArrayList<>(old.structures().size() + patch.structures().size());
        for (SurfaceStructure candidate : old.structures())
            if (Math.floorDiv(candidate.x(), 16) != patch.chunkX()
                    || Math.floorDiv(candidate.z(), 16) != patch.chunkZ()) structures.add(candidate);
        for (SurfaceStructure candidate : patch.structures())
            if (structures.size() < 64) structures.add(candidate);
        // The server authorized this update from its SURVEY/COLOR journal.
        // These masks and ALL material balances remain unchanged.
        return new MapNetwork.Page(old.atlasId(), old.dimension(), old.pageX(), old.pageZ(),
                pixels, features, heights, depths, old.surveyedChunks(), old.coloredChunks(),
                old.inkUnits(), old.pigmentUnits(), old.glowUnits(), old.compass(), old.inRange(),
                true, false, old.scanned(), old.generated(), old.painted(), old.anchorDimension(),
                old.anchorX(), old.anchorZ(), old.totalPages(), old.sheetSideBlocks(),
                old.totalSurveyed(), old.totalColored(), old.markers(), List.copyOf(structures));
    }
}
