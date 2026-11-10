package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.TerrainRelief;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;

/** Shared big-map and HUD texture. A half-resolution area average lives beside the original pixels. */
final class PixelPageTexture {
    private static final int SIDE = AtlasPage.SIDE_BLOCKS;
    private static final int HALF = SIDE / 2;
    private static final int WIDTH = SIDE + HALF;
    private static final int PAPER = 0xe4d5b4;

    private PixelPageTexture() {}

    static DynamicTexture create(MapNetwork.Page page, short[] heights, boolean roads, boolean rails) {
        int halo = TerrainRelief.HALO;
        int stride = SIDE + 2 * halo;
        boolean waterSamples = page.waterDepths().length == SIDE * SIDE;
        NativeImage image = new NativeImage(WIDTH, SIDE, true);
        try {
            for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
                int i = z * SIDE + x;
                int packed = Byte.toUnsignedInt(page.pixels()[i]);
                int rgb = PAPER;
                if (packed > 0 && packed <= 64) {
                    MapColor color = MapColor.byId(packed - 1);
                    int water = waterSamples ? Byte.toUnsignedInt(page.waterDepths()[i]) : 0;
                    int base = materialColor(color, water);
                    int feature = Byte.toUnsignedInt(page.features()[i]);
                    if (roads && feature == 1) base = 0x9b805a;
                    if (rails && feature == 2) base = 0x535554;
                    if (rails && feature == 3) base = 0x65797b;
                    int chunk = (z / 16) * AtlasPage.CHUNKS_PER_SIDE + x / 16;
                    boolean colored = (page.coloredChunks() & (1L << chunk)) != 0;
                    int center = (z + halo) * stride + x + halo;
                    double light = water > 0 ? 1 : TerrainRelief.light(heights, center, stride);
                    rgb = TerrainRelief.shade(base, light, colored);
                }
                image.setPixelRGBA(x, z, toAbgr(rgb));
            }
            for (int z = 0; z < HALF; z++) for (int x = 0; x < HALF; x++) {
                int sx = x * 2, sz = z * 2;
                image.setPixelRGBA(SIDE + x, z,
                        average(image.getPixelRGBA(sx, sz), image.getPixelRGBA(sx + 1, sz),
                                image.getPixelRGBA(sx, sz + 1), image.getPixelRGBA(sx + 1, sz + 1)));
            }
            return new DynamicTexture(image);
        } catch (RuntimeException failure) {
            image.close();
            throw failure;
        }
    }

    static void blit(GuiGraphics graphics, ResourceLocation texture, double screenPixelsPerBlock) {
        boolean overview = screenPixelsPerBlock < 1.0;
        int size = overview ? HALF : SIDE;
        graphics.blit(texture, 0, 0, SIDE, SIDE, overview ? SIDE : 0, 0, size, size, WIDTH, SIDE);
    }

    private static int materialColor(MapColor color, int water) {
        if (water > 0) return TerrainRelief.waterColor(water);
        if (color == MapColor.GRASS) return 0x75884f;
        if (color == MapColor.PLANT) return 0x3f6044;
        return color.col & 0xffffff;
    }

    private static int toAbgr(int rgb) {
        return 0xff000000 | ((rgb & 255) << 16) | (rgb & 0xff00) | ((rgb >>> 16) & 255);
    }

    private static int average(int a, int b, int c, int d) {
        int low = ((a & 255) + (b & 255) + (c & 255) + (d & 255) + 2) / 4;
        int mid = ((a >> 8 & 255) + (b >> 8 & 255) + (c >> 8 & 255) + (d >> 8 & 255) + 2) / 4;
        int high = ((a >> 16 & 255) + (b >> 16 & 255) + (c >> 16 & 255) + (d >> 16 & 255) + 2) / 4;
        return 0xff000000 | high << 16 | mid << 8 | low;
    }
}
