package com.wanstu.cartograph.core;

/** Maps one costly paper sheet to a larger square of 128x128 surface tiles.
 * Reuses SpiralCoverage for sheet placement while keeping all tiles at native resolution.
 * Stored atlas anchors remain expressed in 128-block tile coordinates.
 */
public final class MapSheetCoverage {
    public static final int DEFAULT_BLOCKS = 512;
    public static final int MIN_BLOCKS = 128, MAX_BLOCKS = 2048;
    private MapSheetCoverage() {}

    public static boolean valid(int blocks) {
        return blocks >= MIN_BLOCKS && blocks <= MAX_BLOCKS && (blocks & (blocks - 1)) == 0;
    }

    public static int tilesPerSide(int blocks) {
        if (!valid(blocks)) throw new IllegalArgumentException("Invalid paper coverage size: " + blocks);
        return blocks / 128;
    }

    public static boolean covers(int anchorX, int anchorZ, int tileX, int tileZ,
                                 int sheets, int sheetSideBlocks) {
        if (sheets < 1 || !valid(sheetSideBlocks)) return false;
        int scale = sheetSideBlocks / 128;
        // Center the sheet around the atlas opening tile. Global power-of-two
        // alignment would anchor players at a map CORNER (especially on Z).
        return SpiralCoverage.covers(0, 0,
                Math.floorDiv(tileX - anchorX + scale / 2, scale),
                Math.floorDiv(tileZ - anchorZ + scale / 2, scale), sheets);
    }

    public static double centerBlock(int anchorTile, int sheetSideBlocks) {
        int scale = tilesPerSide(sheetSideBlocks);
        return ((long) anchorTile - scale / 2) * 128 + sheetSideBlocks / 2.0;
    }

    public static int areaChunksPerSheet(int sheetSideBlocks) {
        int edge = sheetSideBlocks / 16;
        return edge * edge;
    }
}
