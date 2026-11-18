package com.wanstu.cartographv3;

public record ChunkKey(String dimension, int x, int z) {
    public ChunkKey {
        if (dimension == null || dimension.isBlank() || dimension.length() > 128)
            throw new IllegalArgumentException("Invalid dimension");
    }
}
