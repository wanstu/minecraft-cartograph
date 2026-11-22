package com.wanstu.cartographv3;

/**
 * Shared presentation contract for native UI and surface rendering.
 *
 * The theme never changes persisted world facts. Theme changes only invalidate
 * presentation caches and re-render the same SurfaceTile facts.
 */
final class FrontendThemeStyle {
    private static final long THEME_RENDER_PIPELINE_VERSION = 2L;

    record Palette(
            int background,
            int top,
            int panel,
            int field,
            int hover,
            int line,
            int text,
            int muted,
            int soft,
            int accent,
            int focus,
            int danger,
            int selected,
            int shadow,
            int gridLine,
            int overlay,
            int overlayLight,
            int rail,
            int switchOn,
            int markerLabel,
            int markerShadow) {}

    private static final Palette SATELLITE = new Palette(
            0xff17383c, 0xff111a20, 0xf8182228, 0xff202b32, 0xff293840,
            0xff46555f, 0xfff2f5f7, 0xffaab5bc, 0xff84939b, 0xfff3c94f,
            0xff68b5f7, 0xffdf6d68, 0xff314754, 0x48000000, 0x610b161b,
            0xa4070b0e, 0x85070b0e, 0xff131b20, 0xff3776ab,
            0xcc12191d, 0x9905090b);

    private static final Palette MODERN = new Palette(
            0xffe7ece8, 0xfff4f7f4, 0xf8f7faf7, 0xffedf3ee, 0xffe1ebe4,
            0xff9baaa0, 0xff24322a, 0xff617067, 0xff7d8c83, 0xff547f62,
            0xff4b7d94, 0xffa75450, 0xffd8e5dc, 0x3023372a, 0x382d4536,
            0x5225372d, 0x3525372d, 0xffe5ece7, 0xff547f62,
            0xeef4f7f4, 0x50304438);

    private static final Palette ANCIENT = new Palette(
            0xffbca06f, 0xffd8bd84, 0xf8e0c995, 0xffdcc48f, 0xffcfb477,
            0xff7b613f, 0xff342616, 0xff69543a, 0xff81694a, 0xff8f572f,
            0xff765a35, 0xff8f4036, 0xffc49d62, 0x603b281a, 0x554f3b28,
            0x6e2b1b12, 0x4d2b1b12, 0xffc8aa72, 0xff8f572f,
            0xe8d8bd84, 0x70503928);

    private FrontendThemeStyle() {}

    static Palette palette() {
        return palette(CartographSettings.frontendTheme());
    }

    static Palette palette(CartographFrontendTheme theme) {
        return switch (theme) {
            case SATELLITE -> SATELLITE;
            case MODERN -> MODERN;
            case ANCIENT -> ANCIENT;
        };
    }

    static long renderSignature() {
        long base = SatelliteSurfaceRenderer.renderSignature();
        long theme = CartographSettings.frontendTheme().ordinal() + 1L;
        long mixed = base ^ (THEME_RENDER_PIPELINE_VERSION * 0x9e3779b97f4a7c15L);
        mixed ^= theme * 0xbf58476d1ce4e5b9L;
        return mixed == 0L ? 1L : mixed;
    }

    static SurfaceTile render(SurfaceTile tile) {
        if (!tile.hasSurfaceFacts()) return tile;
        return tile.withRgb(renderPixels(tile, 1));
    }

    static int[] renderPixels(SurfaceTile tile, int stride) {
        int[] rgb = SatelliteSurfaceRenderer.renderPixels(tile, stride);
        CartographFrontendTheme theme = CartographSettings.frontendTheme();
        if (theme == CartographFrontendTheme.SATELLITE) return rgb;

        int edge = SurfaceTile.SIZE / stride;
        int sampleOffset = Math.max(0, stride / 2);
        int baseX = tile.key().x() * SurfaceTile.SIZE;
        int baseZ = tile.key().z() * SurfaceTile.SIZE;

        for (int z = 0; z < edge; z++) {
            for (int x = 0; x < edge; x++) {
                int sx = Math.min(SurfaceTile.SIZE - 1, x * stride + sampleOffset);
                int sz = Math.min(SurfaceTile.SIZE - 1, z * stride + sampleOffset);
                int index = sz * SurfaceTile.SIZE + sx;
                int worldX = baseX + sx;
                int worldZ = baseZ + sz;
                boolean water = tile.waterDepthAt(index) > 0;
                int relief = tile.reliefHeightAt(index);

                int color = rgb[z * edge + x];
                color = theme == CartographFrontendTheme.MODERN
                        ? modern(color, water, worldX, worldZ)
                        : ancient(color, water, worldX, worldZ);

                if (relief != SurfaceTile.UNKNOWN_HEIGHT) {
                    int left = reliefAt(tile, sx - stride, sz, relief);
                    int up = reliefAt(tile, sx, sz - stride, relief);
                    int gradient = Math.max(Math.abs(relief - left), Math.abs(relief - up));
                    boolean contour = contourCrossed(relief, left, theme)
                            || contourCrossed(relief, up, theme);
                    if (theme == CartographFrontendTheme.MODERN) {
                        if (gradient >= 7) color = mix(color, 0x50655a, 18);
                        if (contour) color = mix(color, 0x667c70, 13);
                    } else {
                        if (gradient >= 5) color = mix(color, 0x5b452f, 28);
                        if (contour) color = mix(color, 0x4c3826, 34);
                    }
                }
                rgb[z * edge + x] = color;
            }
        }
        return rgb;
    }

    private static int reliefAt(SurfaceTile tile, int x, int z, int fallback) {
        if (x < 0 || z < 0 || x >= SurfaceTile.SIZE || z >= SurfaceTile.SIZE) return fallback;
        int value = tile.reliefHeightAt(z * SurfaceTile.SIZE + x);
        return value == SurfaceTile.UNKNOWN_HEIGHT ? fallback : value;
    }

    private static boolean contourCrossed(int a, int b, CartographFrontendTheme theme) {
        int step = theme == CartographFrontendTheme.ANCIENT ? 8 : 12;
        return Math.floorDiv(a, step) != Math.floorDiv(b, step);
    }

    private static int modern(int rgb, boolean water, int worldX, int worldZ) {
        if (rgb == 0) return 0;
        int paper = water ? 0x8fb7c2 : 0xe5ebe6;
        int result = mix(rgb, paper, water ? 25 : 20);
        result = desaturate(result, water ? 12 : 18);
        result = adjust(result, 1.04);
        int grain = noise(worldX, worldZ, 5) - 2;
        return offset(result, grain);
    }

    private static int ancient(int rgb, boolean water, int worldX, int worldZ) {
        if (rgb == 0) return 0;
        int result;
        if (water) {
            result = mix(rgb, 0x7f9c99, 55);
            result = desaturate(result, 35);
        } else {
            int sepia = sepia(rgb);
            result = mix(sepia, 0xd2b77e, 34);
            result = desaturate(result, 18);
        }
        result = quantizeColor(result, 12);
        int grain = noise(worldX, worldZ, 7) - 3;
        return offset(result, grain);
    }

    private static int sepia(int rgb) {
        int r = rgb >>> 16 & 255;
        int g = rgb >>> 8 & 255;
        int b = rgb & 255;
        int nr = clamp((int) (r * .393 + g * .769 + b * .189));
        int ng = clamp((int) (r * .349 + g * .686 + b * .168));
        int nb = clamp((int) (r * .272 + g * .534 + b * .131));
        return nr << 16 | ng << 8 | nb;
    }

    private static int desaturate(int rgb, int percent) {
        int r = rgb >>> 16 & 255;
        int g = rgb >>> 8 & 255;
        int b = rgb & 255;
        int gray = (r * 30 + g * 59 + b * 11) / 100;
        return mix(rgb, gray << 16 | gray << 8 | gray, percent);
    }

    private static int adjust(int rgb, double factor) {
        int r = clamp((int) ((rgb >>> 16 & 255) * factor));
        int g = clamp((int) ((rgb >>> 8 & 255) * factor));
        int b = clamp((int) ((rgb & 255) * factor));
        return r << 16 | g << 8 | b;
    }

    private static int quantizeColor(int rgb, int step) {
        int r = quantizeChannel(rgb >>> 16 & 255, step);
        int g = quantizeChannel(rgb >>> 8 & 255, step);
        int b = quantizeChannel(rgb & 255, step);
        return r << 16 | g << 8 | b;
    }

    private static int quantizeChannel(int value, int step) {
        return clamp((value + step / 2) / step * step);
    }

    private static int offset(int rgb, int delta) {
        int r = clamp((rgb >>> 16 & 255) + delta);
        int g = clamp((rgb >>> 8 & 255) + delta);
        int b = clamp((rgb & 255) + delta);
        return r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, int percent) {
        int inverse = 100 - percent;
        int r = ((a >>> 16 & 255) * inverse + (b >>> 16 & 255) * percent) / 100;
        int g = ((a >>> 8 & 255) * inverse + (b >>> 8 & 255) * percent) / 100;
        int blue = ((a & 255) * inverse + (b & 255) * percent) / 100;
        return r << 16 | g << 8 | blue;
    }

    private static int noise(int x, int z, int range) {
        int n = x * 0x1f123bb5 ^ z * 0x5f356495;
        n ^= n >>> 16;
        n *= 0x45d9f3b;
        n ^= n >>> 16;
        return Math.floorMod(n, range);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
