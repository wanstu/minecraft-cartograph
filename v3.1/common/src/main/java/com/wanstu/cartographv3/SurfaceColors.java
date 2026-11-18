package com.wanstu.cartographv3;

import net.minecraft.world.level.material.MapColor;

final class SurfaceColors {
    static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    private SurfaceColors() {}

    static int surfaceColor(MapColor color, int waterDepth, int worldX, int worldZ) {
        if (color != MapColor.WATER) return color.col & 0x00ffffff;

        double depthShade = Math.max(0, waterDepth) * 0.1
                + (((worldX + worldZ) & 1) == 0 ? 0.0 : 0.2);
        MapColor.Brightness brightness;
        if (depthShade < 0.5) {
            brightness = MapColor.Brightness.HIGH;
        } else if (depthShade > 0.9) {
            brightness = MapColor.Brightness.LOW;
        } else {
            brightness = MapColor.Brightness.NORMAL;
        }
        return MapGameAccess.shade(color, brightness) & 0x00ffffff;
    }

    static int[] shade(int[] rgb, short[] heights) {
        int[] out = rgb.clone();
        java.util.function.IntUnaryOperator heightAt = i -> heights[i];
        for (int z = 0; z < SurfaceTile.SIZE; z++) {
            for (int x = 0; x < SurfaceTile.SIZE; x++) {
                int index = z * SurfaceTile.SIZE + x;
                out[index] = shadePixel(rgb[index], index, x, z, heightAt);
            }
        }
        return out;
    }

    static int shadePixel(int color, int index, int x, int z, java.util.function.IntUnaryOperator heightAt) {
        int center = heightAt.applyAsInt(index);
        if (center == UNKNOWN_HEIGHT) return 0;

        int west = x == 0 ? UNKNOWN_HEIGHT : heightAt.applyAsInt(index - 1);
        int east = x == SurfaceTile.SIZE - 1 ? UNKNOWN_HEIGHT : heightAt.applyAsInt(index + 1);
        int north = z == 0 ? UNKNOWN_HEIGHT : heightAt.applyAsInt(index - SurfaceTile.SIZE);
        int south = z == SurfaceTile.SIZE - 1 ? UNKNOWN_HEIGHT : heightAt.applyAsInt(index + SurfaceTile.SIZE);
        double slopeX = axisSlope(center, west, east);
        double slopeZ = axisSlope(center, north, south);
        double delta = Double.isNaN(slopeX) ? (Double.isNaN(slopeZ) ? 0.0 : slopeZ)
                : (Double.isNaN(slopeZ) ? slopeX : (slopeX + slopeZ) * 0.5);
        double factor = Math.max(0.78, Math.min(1.20, 1.0 + delta * 0.035));
        int r = Math.min(255, (int) (((color >>> 16) & 255) * factor));
        int g = Math.min(255, (int) (((color >>> 8) & 255) * factor));
        int b = Math.min(255, (int) ((color & 255) * factor));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static double axisSlope(int center, int previous, int next) {
        boolean previousKnown = previous != UNKNOWN_HEIGHT;
        boolean nextKnown = next != UNKNOWN_HEIGHT;
        if (previousKnown && nextKnown) return (next - previous) * 0.5;
        if (previousKnown) return center - previous;
        if (nextKnown) return next - center;
        return Double.NaN;
    }
}
