package com.wanstu.cartographv3;

public record SurfaceTile(ChunkKey key, int[] rgb) {
    public static final int SIZE = 16;
    public static final int PIXELS = SIZE * SIZE;

    public SurfaceTile {
        if (key == null || rgb == null || rgb.length != PIXELS)
            throw new IllegalArgumentException("Invalid surface tile");
        rgb = rgb.clone();
    }

    @Override public int[] rgb() { return rgb.clone(); }
    public int colorAt(int x, int z) { return rgb[z * SIZE + x]; }

    public long fingerprint64() {
        long hash = 0xcbf29ce484222325L;
        for (int color : rgb) {
            hash ^= color & 0xffL; hash *= 0x100000001b3L;
            hash ^= (color >>> 8) & 0xffL; hash *= 0x100000001b3L;
            hash ^= (color >>> 16) & 0xffL; hash *= 0x100000001b3L;
            hash ^= (color >>> 24) & 0xffL; hash *= 0x100000001b3L;
        }
        return hash;
    }
}
