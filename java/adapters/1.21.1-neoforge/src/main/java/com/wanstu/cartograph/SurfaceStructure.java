package com.wanstu.cartograph;

/** Verified, actually existing *surface* structure in a fully generated chunk.
 * These automatic icons never spend glow ink and are not editable player markers.
 */
public record SurfaceStructure(String type, int x, int z) {
    public SurfaceStructure {
        if (type == null || type.isBlank() || type.length() > 64)
            throw new IllegalArgumentException("invalid real structure type");
    }
}
