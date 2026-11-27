package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SatelliteSurfaceRenderer {
    private static final int VARIANTS = 4;
    private static final String WATER_STILL = "minecraft:block/water_still";
    private static final String LAVA_STILL = "minecraft:block/lava_still";

    private record Layer(int rgb, int tintIndex) {}
    private record Appearance(Layer[] layers, int untintedRgb) {}

    private static final Int2ObjectOpenHashMap<Appearance[]> STATE_CACHE = new Int2ObjectOpenHashMap<>();
    private static final Map<String, Integer> SPRITE_CACHE = new HashMap<>();
    private static final long RENDER_PIPELINE_VERSION = 6L;
    private static volatile long renderSignature;

    private SatelliteSurfaceRenderer() {}

    static void beginMapSession() {
        STATE_CACHE.clear();
        SPRITE_CACHE.clear();
        renderSignature = computeRenderSignature();
    }

    static long renderSignature() {
        long current = renderSignature;
        if (current != 0L) return current;
        current = computeRenderSignature();
        renderSignature = current;
        return current;
    }

    private static long computeRenderSignature() {
        long hash = 0xcbf29ce484222325L;
        hash = fnv64(hash, Long.toString(RENDER_PIPELINE_VERSION));
        try {
            for (String id : Minecraft.getInstance()
                    .getResourcePackRepository().getSelectedIds()) {
                hash = fnv64(hash, id);
                hash ^= 0xffL;
                hash *= 0x100000001b3L;
            }
        } catch (RuntimeException ignored) {
            hash = fnv64(hash, "resource-packs-unavailable");
        }
        return hash == 0L ? 1L : hash;
    }

    private static long fnv64(long hash, String value) {
        if (value == null) return hash;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    static SurfaceTile render(SurfaceTile tile) {
        if (!tile.hasSurfaceFacts()) return tile;
        return tile.withRgb(renderPixels(tile, 1));
    }

    static int[] renderPixels(SurfaceTile tile, int stride) {
        Minecraft minecraft = Minecraft.getInstance();
        Registry<Biome> biomes = minecraft.level == null ? null
                : MapGameAccess.registry(minecraft.level.registryAccess(), Registries.BIOME);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        java.util.function.IntUnaryOperator heightAt = tile::reliefHeightAt;
        int baseX = tile.key().x() * SurfaceTile.SIZE;
        int baseZ = tile.key().z() * SurfaceTile.SIZE;
        int edge = SurfaceTile.SIZE / stride;
        int[] rgb = new int[edge * edge];
        for (int z = 0; z < edge; z++) {
            for (int x = 0; x < edge; x++) {
                int startX = x * stride;
                int startZ = z * stride;
                if (stride == 1) {
                    rgb[z * edge + x] = shadedPixel(minecraft, biomes, pos, tile, heightAt, baseX, baseZ, x, z);
                } else {
                    int low = stride / 4;
                    int high = stride / 2 + low;
                    int a = shadedPixel(minecraft, biomes, pos, tile, heightAt, baseX, baseZ, startX + low, startZ + low);
                    int b = shadedPixel(minecraft, biomes, pos, tile, heightAt, baseX, baseZ, startX + high, startZ + low);
                    int c = shadedPixel(minecraft, biomes, pos, tile, heightAt, baseX, baseZ, startX + low, startZ + high);
                    int d = shadedPixel(minecraft, biomes, pos, tile, heightAt, baseX, baseZ, startX + high, startZ + high);
                    rgb[z * edge + x] = average(a, b, c, d);
                }
            }
        }
        return rgb;
    }

    /** Samples the same full-resolution color as renderPixels(tile, 1) on the client thread. */
    static java.util.function.IntUnaryOperator colorSampler(SurfaceTile tile) {
        Minecraft minecraft = Minecraft.getInstance();
        Registry<Biome> biomes = minecraft.level == null ? null
                : MapGameAccess.registry(minecraft.level.registryAccess(), Registries.BIOME);
        var pos = new BlockPos.MutableBlockPos();
        java.util.function.IntUnaryOperator heightAt = tile::reliefHeightAt;
        int baseX = tile.key().x() * SurfaceTile.SIZE;
        int baseZ = tile.key().z() * SurfaceTile.SIZE;
        return index -> shadedPixel(minecraft, biomes, pos, tile, heightAt, baseX, baseZ,
                index % SurfaceTile.SIZE, index / SurfaceTile.SIZE);
    }

    private static int shadedPixel(
            Minecraft minecraft, Registry<Biome> biomes, BlockPos.MutableBlockPos pos,
            SurfaceTile tile, java.util.function.IntUnaryOperator heightAt,
            int baseX, int baseZ, int x, int z) {
        int index = z * SurfaceTile.SIZE + x;
        int stateId = tile.stateIdAt(index);
        int rgb = 0;
        if (stateId >= 0) {
            BlockState state = Block.stateById(stateId);
            if (state != null) {
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                int y = tile.surfaceHeightAt(index);
                pos.set(worldX, y == SurfaceTile.UNKNOWN_HEIGHT ? 0 : y, worldZ);
                int biomeId = tile.biomeIdAt(index);
                Biome biome = biomes != null && biomeId >= 0 && biomeId < biomes.size() ? biomes.byId(biomeId) : null;
                rgb = colorFor(minecraft, stateId, state, biome, pos, tile.waterDepthAt(index), worldX, worldZ);
            }
        }
        return SurfaceColors.shadePixel(rgb, index, x, z, heightAt);
    }

    private static int average(int a, int b, int c, int d) {
        int r = ((a >>> 16 & 255) + (b >>> 16 & 255) + (c >>> 16 & 255) + (d >>> 16 & 255)) / 4;
        int g = ((a >>> 8 & 255) + (b >>> 8 & 255) + (c >>> 8 & 255) + (d >>> 8 & 255)) / 4;
        int blue = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) / 4;
        return r << 16 | g << 8 | blue;
    }

    private static int colorFor(
            Minecraft minecraft,
            int stateId,
            BlockState state,
            Biome biome,
            BlockPos pos,
            int waterDepth,
            int worldX,
            int worldZ) {

        if (state.is(Blocks.WATER)) {
            int texture = spriteAverage(WATER_STILL);
            int tint = biome == null ? 0x3f76e4 : biome.getWaterColor();
            int color = multiply(texture, tint);
            return shadeWater(color, waterDepth, worldX, worldZ);
        }
        if (state.is(Blocks.LAVA)) {
            int texture = spriteAverage(LAVA_STILL);
            return texture == 0 ? 0xff6a00 : texture;
        }

        Appearance appearance = appearanceFor(minecraft, stateId, state, pos);
        if (appearance.untintedRgb >= 0) return appearance.untintedRgb;
        if (appearance.layers.length == 0) {
            return fallbackMapColor(state, pos, waterDepth, worldX, worldZ);
        }

        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;

        for (Layer layer : appearance.layers) {
            int color = layer.rgb;
            if (color == 0) continue;

            if (layer.tintIndex >= 0) {
                int tint;
                try {
                    tint = MapClientAccess.tint(minecraft, state, biome, pos, layer.tintIndex);
                } catch (RuntimeException unsupported) {
                    tint = -1;
                }
                if (tint != -1) color = multiply(color, tint);
            }

            red += (color >>> 16) & 255;
            green += (color >>> 8) & 255;
            blue += color & 255;
            count++;
        }

        if (count == 0) return fallbackMapColor(state, pos, waterDepth, worldX, worldZ);
        return ((int) (red / count) << 16)
                | ((int) (green / count) << 8)
                | (int) (blue / count);
    }

    private static Appearance appearanceFor(
            Minecraft minecraft, int stateId, BlockState state, BlockPos pos) {
        Appearance[] variants = STATE_CACHE.get(stateId);
        if (variants == null) {
            variants = new Appearance[VARIANTS];
            STATE_CACHE.put(stateId, variants);
        }
        int variant = (int) (state.getSeed(pos) & (VARIANTS - 1));
        Appearance cached = variants[variant];
        if (cached != null) return cached;

        List<Layer> layers = new ArrayList<>();
        for (MapClientAccess.ModelLayer layer : MapClientAccess.topLayers(
                minecraft, state, variant * 0x9e3779b97f4a7c15L)) {
            int color = spriteAverage(layer.sprite());
            if (color != 0) layers.add(new Layer(color, layer.tintIndex()));
        }

        if (layers.isEmpty()) {
            int color = spriteAverage(MapClientAccess.particleSprite(minecraft, state));
            if (color != 0) layers.add(new Layer(color, -1));
        }

        Appearance next = new Appearance(layers.toArray(Layer[]::new), untintedColor(layers));
        variants[variant] = next;
        return next;
    }

    private static int untintedColor(List<Layer> layers) {
        if (layers.isEmpty()) return -1;
        long red = 0;
        long green = 0;
        long blue = 0;
        for (Layer layer : layers) {
            if (layer.tintIndex >= 0) return -1;
            red += layer.rgb >>> 16 & 255;
            green += layer.rgb >>> 8 & 255;
            blue += layer.rgb & 255;
        }
        int count = layers.size();
        return ((int) (red / count) << 16) | ((int) (green / count) << 8) | (int) (blue / count);
    }

    private static int spriteAverage(String sprite) {
        if (sprite == null) return 0;
        Integer cached = SPRITE_CACHE.get(sprite);
        if (cached != null) return cached;

        var id = MapGameAccess.parseId(sprite);
        if (id == null) return 0;
        var texture = MapGameAccess.id(id.getNamespace(), "textures/" + id.getPath() + ".png");
        int average = 0;

        try (InputStream input = Minecraft.getInstance().getResourceManager().open(texture);
             NativeImage image = NativeImage.read(input)) {
            long red = 0;
            long green = 0;
            long blue = 0;
            long alphaWeight = 0;

            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int abgr = MapClientAccess.readAbgr(image, x, y);
                    int alpha = (abgr >>> 24);
                    if (alpha == 0) continue;
                    red += (long) (abgr & 255) * alpha;
                    green += (long) ((abgr >>> 8) & 255) * alpha;
                    blue += (long) ((abgr >>> 16) & 255) * alpha;
                    alphaWeight += alpha;
                }
            }

            if (alphaWeight > 0) {
                average = ((int) (red / alphaWeight) << 16)
                        | ((int) (green / alphaWeight) << 8)
                        | (int) (blue / alphaWeight);
            }
        } catch (IOException | RuntimeException ignored) {
            average = 0;
        }

        SPRITE_CACHE.put(sprite, average);
        return average;
    }

    private static int fallbackMapColor(
            BlockState state, BlockPos pos, int waterDepth, int worldX, int worldZ) {
        MapColor mapColor;
        try {
            mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, pos);
        } catch (RuntimeException unsupported) {
            mapColor = MapColor.STONE;
        }
        if (mapColor == MapColor.NONE) return 0;
        return SurfaceColors.surfaceColor(mapColor, waterDepth, worldX, worldZ);
    }

    private static int shadeWater(int rgb, int depth, int worldX, int worldZ) {
        if (rgb == 0) return 0x3f76e4;

        double depthShade = Math.max(0, depth) * 0.1
                + (((worldX + worldZ) & 1) == 0 ? 0.0 : 0.2);
        double factor;
        if (depthShade < 0.5) factor = 1.12;
        else if (depthShade > 0.9) factor = 0.72;
        else factor = 0.92;

        int r = Math.min(255, (int) (((rgb >>> 16) & 255) * factor));
        int g = Math.min(255, (int) (((rgb >>> 8) & 255) * factor));
        int b = Math.min(255, (int) ((rgb & 255) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int multiply(int base, int tint) {
        int r = ((base >>> 16) & 255) * ((tint >>> 16) & 255) / 255;
        int g = ((base >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
        int b = (base & 255) * (tint & 255) / 255;
        return (r << 16) | (g << 8) | b;
    }

}
