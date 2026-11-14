package com.wanstu.cartographv3;

public record SurfaceTile(
        ChunkKey key,
        int[] rgb,
        int[] stateIds,
        int[] biomeIds,
        short[] surfaceHeights,
        short[] reliefHeights,
        byte[] waterDepths) {

    public static final int SIZE = 16;
    public static final int PIXELS = SIZE * SIZE;
    public static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    public SurfaceTile(ChunkKey key, int[] rgb) {
        this(key, rgb, null, null, null, null, null);
    }

    public SurfaceTile {
        if (key == null || rgb == null || rgb.length != PIXELS)
            throw new IllegalArgumentException("Invalid surface tile");
        rgb = rgb.clone();

        boolean factsPresent = stateIds != null || biomeIds != null
                || surfaceHeights != null || reliefHeights != null || waterDepths != null;
        if (factsPresent) {
            if (stateIds == null || biomeIds == null
                    || surfaceHeights == null || reliefHeights == null || waterDepths == null
                    || stateIds.length != PIXELS || biomeIds.length != PIXELS
                    || surfaceHeights.length != PIXELS || reliefHeights.length != PIXELS
                    || waterDepths.length != PIXELS) {
                throw new IllegalArgumentException("Incomplete surface facts");
            }
            stateIds = stateIds.clone();
            biomeIds = biomeIds.clone();
            surfaceHeights = surfaceHeights.clone();
            reliefHeights = reliefHeights.clone();
            waterDepths = waterDepths.clone();
        }
    }

    static SurfaceTile fromFacts(
            ChunkKey key,
            int[] stateIds,
            int[] biomeIds,
            short[] surfaceHeights,
            short[] reliefHeights,
            byte[] waterDepths) {
        return new SurfaceTile(
                key, new int[PIXELS],
                stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths);
    }

    SurfaceTile withRgb(int[] nextRgb) {
        return new SurfaceTile(
                key, nextRgb,
                stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths);
    }

    boolean hasSurfaceFacts() {
        return stateIds != null;
    }

    @Override public int[] rgb() { return rgb.clone(); }
    @Override public int[] stateIds() { return stateIds == null ? null : stateIds.clone(); }
    @Override public int[] biomeIds() { return biomeIds == null ? null : biomeIds.clone(); }
    @Override public short[] surfaceHeights() {
        return surfaceHeights == null ? null : surfaceHeights.clone();
    }
    @Override public short[] reliefHeights() {
        return reliefHeights == null ? null : reliefHeights.clone();
    }
    @Override public byte[] waterDepths() {
        return waterDepths == null ? null : waterDepths.clone();
    }

    public int colorAt(int x, int z) {
        return rgb[z * SIZE + x];
    }

    int stateIdAt(int index) {
        return stateIds == null ? -1 : stateIds[index];
    }

    int biomeIdAt(int index) {
        return biomeIds == null ? -1 : biomeIds[index];
    }

    int surfaceHeightAt(int index) {
        return surfaceHeights == null ? UNKNOWN_HEIGHT : surfaceHeights[index];
    }

    int reliefHeightAt(int index) {
        return reliefHeights == null ? UNKNOWN_HEIGHT : reliefHeights[index];
    }

    int waterDepthAt(int index) {
        return waterDepths == null ? 0 : Byte.toUnsignedInt(waterDepths[index]);
    }

    public long fingerprint64() {
        long hash = 0xcbf29ce484222325L;
        if (hasSurfaceFacts()) {
            for (int i = 0; i < PIXELS; i++) {
                hash = mixInt(hash, stateIds[i]);
                hash = mixInt(hash, biomeIds[i]);
                hash = mixShort(hash, surfaceHeights[i]);
                hash = mixShort(hash, reliefHeights[i]);
                hash ^= waterDepths[i] & 0xffL;
                hash *= 0x100000001b3L;
            }
            return hash;
        }

        for (int color : rgb) {
            hash = mixInt(hash, color);
        }
        return hash;
    }

    private static long mixInt(long hash, int value) {
        hash ^= value & 0xffL; hash *= 0x100000001b3L;
        hash ^= (value >>> 8) & 0xffL; hash *= 0x100000001b3L;
        hash ^= (value >>> 16) & 0xffL; hash *= 0x100000001b3L;
        hash ^= (value >>> 24) & 0xffL; hash *= 0x100000001b3L;
        return hash;
    }

    private static long mixShort(long hash, short value) {
        int unsigned = value & 0xffff;
        hash ^= unsigned & 0xffL; hash *= 0x100000001b3L;
        hash ^= (unsigned >>> 8) & 0xffL; hash *= 0x100000001b3L;
        return hash;
    }
}
