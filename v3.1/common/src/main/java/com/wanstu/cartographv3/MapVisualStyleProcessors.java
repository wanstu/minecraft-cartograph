package com.wanstu.cartographv3;

/** Built-in visual-style processors. Every style implements both data structures. */
final class MapVisualStyleProcessors {
    static final MapVisualStyleProcessor MODERN = new Modern();
    static final MapVisualStyleProcessor ANCIENT = new Ancient();
    static final MapVisualStyleProcessor ANIME = new Anime();

    private static final MapVisualStyleProcessor.Palette MODERN_PALETTE =
            new MapVisualStyleProcessor.Palette(
                    0xffe7ece8, 0xfff4f7f4, 0xf8f7faf7, 0xffedf3ee, 0xffe1ebe4,
                    0xff9baaa0, 0xff24322a, 0xff617067, 0xff7d8c83, 0xff547f62,
                    0xff4b7d94, 0xffa75450, 0xffd8e5dc, 0x3023372a, 0x382d4536,
                    0x5225372d, 0x3525372d, 0xffe5ece7, 0xff547f62,
                    0xeef4f7f4, 0x50304438);

    private static final MapVisualStyleProcessor.Palette ANCIENT_PALETTE =
            new MapVisualStyleProcessor.Palette(
                    0xffbca06f, 0xffd8bd84, 0xf8e0c995, 0xffdcc48f, 0xffcfb477,
                    0xff7b613f, 0xff342616, 0xff69543a, 0xff81694a, 0xff8f572f,
                    0xff765a35, 0xff8f4036, 0xffc49d62, 0x603b281a, 0x554f3b28,
                    0x6e2b1b12, 0x4d2b1b12, 0xffc8aa72, 0xff8f572f,
                    0xe8d8bd84, 0x70503928);

    private static final MapVisualStyleProcessor.Palette ANIME_PALETTE =
            new MapVisualStyleProcessor.Palette(
                    0xffdcecf2, 0xfff7fbfd, 0xf9ffffff, 0xffedf7fb, 0xffdceef6,
                    0xff7998a7, 0xff24313d, 0xff607381, 0xff7c91a0, 0xffe06f8b,
                    0xff5fa9e6, 0xffd95462, 0xffd5edf7, 0x30405c6b, 0x38486a7b,
                    0x4d2d4552, 0x302d4552, 0xffe8f5fa, 0xff5fa9e6,
                    0xeff8fcff, 0x50405b69);

    private MapVisualStyleProcessors() {}

    private static final class Modern implements MapVisualStyleProcessor {
        @Override
        public Palette palette() {
            return MODERN_PALETTE;
        }

        @Override
        public int[] renderSatellite(SatelliteMapData data, int stride) {
            int[] pixels = data.basePixels(stride);
            styleSatellite(data, pixels, stride, StyleKind.MODERN);
            return pixels;
        }

        @Override
        public int[] renderHandDrawn(HandDrawnMapData data, int stride) {
            int[] pixels = data.abstractPixels(stride);
            styleHandDrawn(data, pixels, stride, StyleKind.MODERN);
            return pixels;
        }
    }

    private static final class Ancient implements MapVisualStyleProcessor {
        @Override
        public Palette palette() {
            return ANCIENT_PALETTE;
        }

        @Override
        public int[] renderSatellite(SatelliteMapData data, int stride) {
            int[] pixels = data.basePixels(stride);
            styleSatellite(data, pixels, stride, StyleKind.ANCIENT);
            return pixels;
        }

        @Override
        public int[] renderHandDrawn(HandDrawnMapData data, int stride) {
            int[] pixels = data.abstractPixels(stride);
            styleHandDrawn(data, pixels, stride, StyleKind.ANCIENT);
            return pixels;
        }
    }

    private static final class Anime implements MapVisualStyleProcessor {
        @Override
        public Palette palette() {
            return ANIME_PALETTE;
        }

        @Override
        public int[] renderSatellite(SatelliteMapData data, int stride) {
            int[] pixels = data.basePixels(stride);
            styleSatellite(data, pixels, stride, StyleKind.ANIME);
            return pixels;
        }

        @Override
        public int[] renderHandDrawn(HandDrawnMapData data, int stride) {
            int[] pixels = data.abstractPixels(stride);
            styleHandDrawn(data, pixels, stride, StyleKind.ANIME);
            return pixels;
        }
    }

    private enum StyleKind { MODERN, ANCIENT, ANIME }

    private static void styleSatellite(
            SatelliteMapData data, int[] pixels, int stride, StyleKind style) {
        int edge = SurfaceTile.SIZE / stride;
        int sampleOffset = Math.max(0, stride / 2);
        int baseX = data.facts().key().x() * SurfaceTile.SIZE;
        int baseZ = data.facts().key().z() * SurfaceTile.SIZE;
        for (int z = 0; z < edge; z++) {
            for (int x = 0; x < edge; x++) {
                int sx = Math.min(SurfaceTile.SIZE - 1, x * stride + sampleOffset);
                int sz = Math.min(SurfaceTile.SIZE - 1, z * stride + sampleOffset);
                int index = sz * SurfaceTile.SIZE + sx;
                boolean water = data.waterAt(index);
                int relief = data.facts().reliefHeightAt(index);
                int color = pixels[z * edge + x];
                color = applyStyle(color, water, baseX + sx, baseZ + sz, style, false);

                if (relief != SurfaceTile.UNKNOWN_HEIGHT) {
                    int left = data.reliefAt(sx - stride, sz, relief);
                    int up = data.reliefAt(sx, sz - stride, relief);
                    color = applyRelief(color, relief, left, up, style, false);
                }
                pixels[z * edge + x] = color;
            }
        }
    }

    private static void styleHandDrawn(
            HandDrawnMapData data, int[] pixels, int stride, StyleKind style) {
        int edge = SurfaceTile.SIZE / stride;
        int sampleOffset = Math.max(0, stride / 2);
        int baseX = data.key().x() * SurfaceTile.SIZE;
        int baseZ = data.key().z() * SurfaceTile.SIZE;
        int shorelineRadius = Math.max(2, Math.min(HandDrawnMapData.CELL_SIZE, stride));
        for (int z = 0; z < edge; z++) {
            for (int x = 0; x < edge; x++) {
                int sx = Math.min(SurfaceTile.SIZE - 1, x * stride + sampleOffset);
                int sz = Math.min(SurfaceTile.SIZE - 1, z * stride + sampleOffset);
                int waterPercent = data.waterPercentAtPixel(sx, sz);
                boolean water = waterPercent >= HandDrawnMapData.WATER_THRESHOLD;
                int color = pixels[z * edge + x];
                color = applyStyle(color, water, baseX + sx, baseZ + sz, style, true);

                if (water) {
                    int depth = data.waterDepthAtPixel(sx, sz);
                    color = applyHandDrawnWaterDepth(color, depth, style);
                } else {
                    int forestPercent = data.forestPercentAtPixel(sx, sz);
                    if (forestPercent >= HandDrawnMapData.FOREST_THRESHOLD) {
                        int canopyHeight = data.canopyHeightAtPixel(sx, sz);
                        color = applyHandDrawnForest(color, forestPercent, canopyHeight, style);
                    }

                    int relief = data.averageReliefAtPixel(sx, sz);
                    if (relief != SurfaceTile.UNKNOWN_HEIGHT) {
                        int range = data.reliefRangeAtPixel(sx, sz);
                        color = applyAbstractRelief(color, relief, range, style);
                    }

                    int forestEdge = data.forestEdgeStrengthAtPixel(sx, sz, shorelineRadius);
                    if (forestEdge > 0) color = applyHandDrawnForestEdge(color, forestEdge, style);
                }

                int shoreline = data.shorelineStrengthAtPixel(sx, sz, shorelineRadius);
                if (shoreline > 0) color = applyHandDrawnShoreline(color, shoreline, style);
                pixels[z * edge + x] = color;
            }
        }
    }

    private static int applyStyle(
            int rgb, boolean water, int worldX, int worldZ, StyleKind style, boolean handDrawn) {
        if (rgb == 0) return 0;
        return switch (style) {
            case MODERN -> modern(rgb, water, worldX, worldZ, handDrawn);
            case ANCIENT -> ancient(rgb, water, worldX, worldZ, handDrawn);
            case ANIME -> anime(rgb, water, worldX, worldZ, handDrawn);
        };
    }

    private static int modern(
            int rgb, boolean water, int worldX, int worldZ, boolean handDrawn) {
        int paper = water ? 0x91bbc6 : 0xe7ede8;
        int result = mix(rgb, paper, water ? (handDrawn ? 38 : 18) : (handDrawn ? 32 : 12));
        result = desaturate(result, handDrawn ? 25 : 10);
        result = adjust(result, handDrawn ? 1.06 : 1.02);
        return handDrawn ? result : offset(result, noise(worldX, worldZ, 3) - 1);
    }

    private static int ancient(
            int rgb, boolean water, int worldX, int worldZ, boolean handDrawn) {
        int result;
        if (water) {
            result = mix(rgb, 0x789895, handDrawn ? 62 : 52);
            result = desaturate(result, handDrawn ? 32 : 32);
        } else if (handDrawn) {
            // Hand-drawn data is already categorical. Keep those terrain hues
            // readable and age them instead of flattening every land class into
            // the same parchment brown.
            result = mix(rgb, sepia(rgb), 42);
            result = mix(result, 0xd1b47b, 22);
            result = desaturate(result, 18);
        } else {
            result = mix(sepia(rgb), 0xd1b47b, 32);
            result = desaturate(result, 18);
        }
        result = quantizeColor(result, handDrawn ? 18 : 12);
        return handDrawn ? result : offset(result, noise(worldX, worldZ, 7) - 3);
    }

    private static int anime(
            int rgb, boolean water, int worldX, int worldZ, boolean handDrawn) {
        int target = water ? 0x6cbfe7 : 0x9fd17b;
        int result = mix(rgb, target, handDrawn ? 34 : 15);
        result = saturate(result, handDrawn ? 32 : 18);
        result = adjust(result, handDrawn ? 1.08 : 1.04);
        result = quantizeColor(result, handDrawn ? 18 : 10);
        return handDrawn ? result : offset(result, noise(worldX, worldZ, 5) - 2);
    }

    private static int applyHandDrawnForest(
            int color, int forestPercent, int canopyHeight, StyleKind style) {
        int density = Math.max(0, forestPercent - HandDrawnMapData.FOREST_THRESHOLD);
        int amount = Math.min(46, 18 + density / 2);
        int target = switch (style) {
            case MODERN -> 0x5f7f60;
            case ANCIENT -> 0x747b55;
            case ANIME -> 0x52a85e;
        };
        int result = mix(color, target, amount);

        if (canopyHeight > 3 && canopyHeight != Integer.MIN_VALUE) {
            int heightAmount = Math.min(14, (canopyHeight - 3) * 2);
            int deep = switch (style) {
                case MODERN -> 0x456248;
                case ANCIENT -> 0x59603f;
                case ANIME -> 0x347d43;
            };
            result = mix(result, deep, heightAmount);
        }
        return result;
    }

    private static int applyHandDrawnForestEdge(int color, int strength, StyleKind style) {
        if (strength < 28) return color;
        int amount = Math.min(48, 12 + strength / 3);
        int ink = switch (style) {
            case MODERN -> 0x4c6751;
            case ANCIENT -> 0x5a5035;
            case ANIME -> 0x347745;
        };
        return mix(color, ink, amount);
    }

    private static int applyHandDrawnWaterDepth(int color, int depth, StyleKind style) {
        if (depth <= 1 || depth == Integer.MIN_VALUE) return color;
        int amount = Math.min(22, (depth - 1) * 3);
        int target = switch (style) {
            case MODERN -> 0x567d89;
            case ANCIENT -> 0x607b76;
            case ANIME -> 0x3f93c7;
        };
        return mix(color, target, amount);
    }

    private static int applyHandDrawnShoreline(int color, int strength, StyleKind style) {
        if (strength < 30) return color;
        int amount = Math.min(62, 18 + strength / 2);
        int ink = switch (style) {
            case MODERN -> 0x425c54;
            case ANCIENT -> 0x4b3825;
            case ANIME -> 0x365f72;
        };
        return mix(color, ink, amount);
    }

    private static int applyRelief(
            int color, int relief, int left, int up, StyleKind style, boolean handDrawn) {
        int gradient = Math.max(Math.abs(relief - left), Math.abs(relief - up));
        int step = style == StyleKind.ANCIENT ? 8 : style == StyleKind.ANIME ? 10 : 12;
        boolean contour = Math.floorDiv(relief, step) != Math.floorDiv(left, step)
                || Math.floorDiv(relief, step) != Math.floorDiv(up, step);
        return switch (style) {
            case MODERN -> {
                int result = gradient >= 7 ? mix(color, 0x50655a, handDrawn ? 22 : 12) : color;
                yield contour ? mix(result, 0x667c70, handDrawn ? 18 : 8) : result;
            }
            case ANCIENT -> {
                int result = gradient >= 5 ? mix(color, 0x5b452f, handDrawn ? 34 : 24) : color;
                yield contour ? mix(result, 0x4c3826, handDrawn ? 42 : 30) : result;
            }
            case ANIME -> {
                int result = gradient >= 7 ? mix(color, 0x3c5967, handDrawn ? 26 : 14) : color;
                yield contour ? mix(result, 0x304c5c, handDrawn ? 34 : 18) : result;
            }
        };
    }

    private static int applyAbstractRelief(
            int color, int relief, int range, StyleKind style) {
        if (range <= 5) return color;
        int amount = Math.min(24, 4 + range);
        return switch (style) {
            case MODERN -> mix(color, 0x5d7065, amount / 3);
            case ANCIENT -> mix(color, 0x59422d, Math.min(22, amount));
            case ANIME -> mix(color, 0x395766, Math.min(18, amount));
        };
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

    private static int saturate(int rgb, int percent) {
        int r = rgb >>> 16 & 255;
        int g = rgb >>> 8 & 255;
        int b = rgb & 255;
        int gray = (r * 30 + g * 59 + b * 11) / 100;
        int nr = clamp(r + (r - gray) * percent / 100);
        int ng = clamp(g + (g - gray) * percent / 100);
        int nb = clamp(b + (b - gray) * percent / 100);
        return nr << 16 | ng << 8 | nb;
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
