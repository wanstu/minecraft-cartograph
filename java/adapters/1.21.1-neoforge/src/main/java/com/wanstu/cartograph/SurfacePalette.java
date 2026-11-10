package com.wanstu.cartograph;

import com.wanstu.cartograph.core.TerrainRelief;
import net.minecraft.world.level.material.MapColor;

/** Only authorized colored cells carry material palette IDs over the wire. */
final class SurfacePalette {
    private SurfacePalette() {}

    static int rgb(int sample, int water, boolean enhanced) {
        if (sample < 1 || sample > 64) return 0;
        MapColor color = MapColor.byId(sample - 1);
        if (enhanced && water > 0) return TerrainRelief.waterColor(water);
        if (enhanced && color == MapColor.GRASS) return 0x75884f;
        if (enhanced && color == MapColor.PLANT) return 0x3f6044;
        return color.col & 0xffffff;
    }

    static int luminance(int rgb) {
        return ((rgb >> 16 & 255) * 30 + (rgb >> 8 & 255) * 59 + (rgb & 255) * 11 + 50) / 100;
    }

    static byte graySample(byte sample, byte water) {
        if (sample == 0) return 0;
        return (byte) (1 + Math.min(254, luminance(rgb(Byte.toUnsignedInt(sample), Byte.toUnsignedInt(water), true))));
    }
}
