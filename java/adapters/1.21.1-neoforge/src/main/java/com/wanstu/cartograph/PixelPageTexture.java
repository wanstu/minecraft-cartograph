package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.TerrainRelief;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.MapColor;
import com.wanstu.cartograph.ClientMapSettings.Style;

/** All views use this shared set of projected terrain samples. */
final class PixelPageTexture {
    static final int SIDE = AtlasPage.SIDE_BLOCKS;
    static final int PAPER = 0xe4d9bf;
    private static final int HALF = SIDE / 2, WIDTH = SIDE + HALF;
    private static final Style[] STYLES = Style.values();
    private static final int HEIGHT = SIDE * STYLES.length;

    private PixelPageTexture() {}

    static float[] lightMap(short[] heights) {
        float[] light = new float[SIDE * SIDE];
        int halo = TerrainRelief.HALO, stride = SIDE + 2 * halo;
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++)
            light[z * SIDE + x] = (float) TerrainRelief.light(heights, (z + halo) * stride + x + halo, stride);
        return light;
    }

    static DynamicTexture create(MapNetwork.Page page, float[] light, ClientMapSettings settings) {
        boolean surface = page.heights().length == SIDE * SIDE;
        NativeImage image = new NativeImage(WIDTH, HEIGHT, true);
        try {
            for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
                int i = z * SIDE + x;
                int packed = Byte.toUnsignedInt(page.pixels()[i]);
                int chunk = (z / 16) * AtlasPage.CHUNKS_PER_SIDE + x / 16;
                boolean colored = (page.coloredChunks() & (1L << chunk)) != 0;
                int depth = surface ? Byte.toUnsignedInt(page.waterDepths()[i]) : 0;
                int height = surface ? page.heights()[i] : TerrainRelief.UNKNOWN_HEIGHT;
                int feature = Byte.toUnsignedInt(page.features()[i]);
                boolean line = surface && settings.layer(ClientMapSettings.CONTOURS)
                        && crossesContour(page.heights(), i, x, z);
                for (Style style : STYLES) {
                    int rgb = packed == 0 ? PAPER : color(style, packed, colored, depth, height,
                            light[i], feature, line, settings, page.pageX() * SIDE + x, page.pageZ() * SIDE + z);
                    image.setPixelRGBA(x, z + style.ordinal() * SIDE, abgr(rgb));
                }
            }
            for (Style style : STYLES) {
                int row = style.ordinal() * SIDE;
                for (int z = 0; z < HALF; z++) for (int x = 0; x < HALF; x++) {
                    int sx = x * 2, sz = row + z * 2;
                    image.setPixelRGBA(SIDE + x, row + z,
                            average(image.getPixelRGBA(sx, sz), image.getPixelRGBA(sx + 1, sz),
                                    image.getPixelRGBA(sx, sz + 1), image.getPixelRGBA(sx + 1, sz + 1)));
                }
            }
            return new DynamicTexture(image);
        } catch (RuntimeException failure) {
            image.close();
            throw failure;
        }
    }

    private static int color(Style style, int sample, boolean colored, int water, int height, float light,
                             int feature, boolean contour, ClientMapSettings settings, int x, int z) {
        int base = colored ? SurfacePalette.rgb(sample, water, style != Style.ORIGINAL) : gray(sample - 1);
        if (style == Style.ILLUSTRATED) {
            double tone = SurfacePalette.luminance(base) / 255.0;
            base = water > 0 ? mix(0x648b8b, 0xa1b0a0, Math.min(1.0, 1.0 / water))
                    : mix(0xaa987b, 0xf4e5bd, 0.37 + 0.63 * tone);
            if (colored && sample == 8 && Math.floorMod(x, 9) < 2 && Math.floorMod(z, 7) < 2)
                base = mix(base, 0x4d6550, .55);
        } else if (style == Style.REALISTIC && colored && sample <= 64) {
            if (water > 0) base = mix(0x214351, 0x70978a, 1.0 / (1 + (water - 1) * .39));
            else {
                MapColor material = MapColor.byId(sample - 1);
                if (material == MapColor.GRASS) base = 0x96956b;
                else if (material == MapColor.PLANT) base = 0x3b5548;
                else base = mix(base, 0x928f7c, .29);
                if (height > 120) base = mix(base, 0xd4cbb4, Math.min(.38, (height - 120) / 280.0));
            }
        }
        if (feature == 1 && settings.layer(ClientMapSettings.ROADS)) base = 0xa48860;
        if (feature >= 2 && settings.layer(ClientMapSettings.RAILS)) base = feature == 2 ? 0x565751 : 0x687a7c;
        if (water == 0 && contour && (style == Style.ILLUSTRATED || style == Style.REALISTIC))
            base = mix(base, 0x625440, style == Style.ILLUSTRATED ? .38 : .24);
        double exposure = style == Style.ORIGINAL || water > 0 ? 1 : light;
        if (style == Style.ILLUSTRATED) exposure = 1 + (exposure - 1) * .55;
        if (style == Style.REALISTIC) exposure = 1 + (exposure - 1) * .76;
        return TerrainRelief.shade(base, exposure, colored);
    }

    private static boolean crossesContour(short[] heights, int index, int x, int z) {
        short y = heights[index];
        if (y == TerrainRelief.UNKNOWN_HEIGHT) return false;
        int band = Math.floorDiv(y, 12);
        return (x > 0 && heights[index - 1] != TerrainRelief.UNKNOWN_HEIGHT && band > Math.floorDiv(heights[index - 1], 12))
                || (z > 0 && heights[index - SIDE] != TerrainRelief.UNKNOWN_HEIGHT && band > Math.floorDiv(heights[index - SIDE], 12));
    }

    static int sample(NativeImage image, Style style, int x, int z, boolean reduced) {
        int row = style.ordinal() * SIDE;
        return reduced ? image.getPixelRGBA(SIDE + x / 2, row + z / 2) : image.getPixelRGBA(x, row + z);
    }

    static void blit(GuiGraphics g, ResourceLocation texture, Style style, double pixelsPerBlock) {
        boolean overview = pixelsPerBlock < 1.0;
        int size = overview ? HALF : SIDE;
        g.blit(texture, 0, 0, SIDE, SIDE, overview ? SIDE : 0, style.ordinal() * SIDE, size, size, WIDTH, HEIGHT);
    }

    static void thumbnail(GuiGraphics g, ResourceLocation texture, Style style, int x, int y, int w, int h) {
        g.blit(texture, x, y, w, h, SIDE, style.ordinal() * SIDE, HALF, HALF, WIDTH, HEIGHT);
    }

    static int abgr(int rgb) {
        return 0xff000000 | ((rgb & 255) << 16) | (rgb & 0xff00) | ((rgb >>> 16) & 255);
    }
    private static int gray(int value) { return value * 0x010101; }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) Math.round((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }
    private static int average(int a, int b, int c, int d) {
        int lo = ((a & 255) + (b & 255) + (c & 255) + (d & 255) + 2) / 4;
        int mid = ((a >> 8 & 255) + (b >> 8 & 255) + (c >> 8 & 255) + (d >> 8 & 255) + 2) / 4;
        int hi = ((a >> 16 & 255) + (b >> 16 & 255) + (c >> 16 & 255) + (d >> 16 & 255) + 2) / 4;
        return 0xff000000 | hi << 16 | mid << 8 | lo;
    }
}
