package com.wanstu.cartograph.core;

/**
 * Same scale as an unexpanded vanilla map: 128x128 blocks per page.
 * Handles negative world coordinates using mathematical floor division.
 */
public record AtlasPage(String dimension, int pageX, int pageZ) {
    public static final int SIDE_BLOCKS = 128;
    public static final int CHUNKS_PER_SIDE = 8;

    public static AtlasPage atChunk(String dimension, int chunkX, int chunkZ) {
        return new AtlasPage(dimension, Math.floorDiv(chunkX, CHUNKS_PER_SIDE),
                Math.floorDiv(chunkZ, CHUNKS_PER_SIDE));
    }
    public static AtlasPage atBlock(String dimension, int blockX, int blockZ) {
        return new AtlasPage(dimension, Math.floorDiv(blockX, SIDE_BLOCKS),
                Math.floorDiv(blockZ, SIDE_BLOCKS));
    }
    public boolean containsChunk(int chunkX, int chunkZ) {
        return Math.floorDiv(chunkX, CHUNKS_PER_SIDE) == pageX
                && Math.floorDiv(chunkZ, CHUNKS_PER_SIDE) == pageZ;
    }
}
