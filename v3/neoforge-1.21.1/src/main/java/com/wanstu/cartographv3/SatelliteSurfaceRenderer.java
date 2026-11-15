package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SatelliteSurfaceRenderer {
    private static final int VARIANTS = 4;
    private static final ResourceLocation WATER_STILL =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/water_still");
    private static final ResourceLocation LAVA_STILL =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/lava_still");

    private record Layer(int rgb, int tintIndex) {}
    private record Appearance(Layer[] layers) {}

    private static final Map<Integer, Appearance[]> STATE_CACHE = new HashMap<>();
    private static final Map<ResourceLocation, Integer> SPRITE_CACHE = new HashMap<>();
    private static final TintContext TINT_CONTEXT = new TintContext();
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

        Minecraft minecraft = Minecraft.getInstance();
        Registry<Biome> biomeRegistry = minecraft.level == null ? null
                : minecraft.level.registryAccess().registryOrThrow(Registries.BIOME);

        int[] rgb = new int[SurfaceTile.PIXELS];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int chunkBaseX = tile.key().x() * SurfaceTile.SIZE;
        int chunkBaseZ = tile.key().z() * SurfaceTile.SIZE;

        for (int z = 0; z < SurfaceTile.SIZE; z++) {
            for (int x = 0; x < SurfaceTile.SIZE; x++) {
                int index = z * SurfaceTile.SIZE + x;
                int stateId = tile.stateIdAt(index);
                if (stateId < 0) continue;

                BlockState state = Block.stateById(stateId);
                if (state == null) continue;

                int worldX = chunkBaseX + x;
                int worldZ = chunkBaseZ + z;
                int surfaceY = tile.surfaceHeightAt(index);
                if (surfaceY == SurfaceTile.UNKNOWN_HEIGHT) surfaceY = 0;
                pos.set(worldX, surfaceY, worldZ);

                Biome biome = null;
                int biomeId = tile.biomeIdAt(index);
                if (biomeRegistry != null && biomeId >= 0 && biomeId < biomeRegistry.size()) {
                    biome = biomeRegistry.byId(biomeId);
                }

                rgb[index] = colorFor(
                        minecraft, stateId, state, biome, pos,
                        tile.waterDepthAt(index), worldX, worldZ);
            }
        }

        return tile.withRgb(SurfaceColors.shade(rgb, tile.reliefHeights()));
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
        if (appearance.layers.length == 0) {
            return fallbackMapColor(state, pos, waterDepth, worldX, worldZ);
        }

        TINT_CONTEXT.set(state, biome);
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
                    tint = minecraft.getBlockColors().getColor(
                            state, TINT_CONTEXT, pos, layer.tintIndex);
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
        Appearance[] variants = STATE_CACHE.computeIfAbsent(
                stateId, ignored -> new Appearance[VARIANTS]);
        int variant = (int) (state.getSeed(pos) & (VARIANTS - 1));
        Appearance cached = variants[variant];
        if (cached != null) return cached;

        BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
        RandomSource random = RandomSource.create(variant * 0x9e3779b97f4a7c15L);
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(state, Direction.UP, random));
        random.setSeed(variant * 0x9e3779b97f4a7c15L);
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            if (quad.getDirection() == Direction.UP) quads.add(quad);
        }

        List<Layer> layers = new ArrayList<>();
        for (BakedQuad quad : quads) {
            int color = spriteAverage(quad.getSprite().contents().name());
            if (color != 0) {
                layers.add(new Layer(color, quad.isTinted() ? quad.getTintIndex() : -1));
            }
        }

        if (layers.isEmpty()) {
            TextureAtlasSprite particle = model.getParticleIcon();
            int color = particle == null ? 0 : spriteAverage(particle.contents().name());
            if (color != 0) layers.add(new Layer(color, -1));
        }

        Appearance next = new Appearance(layers.toArray(Layer[]::new));
        variants[variant] = next;
        return next;
    }

    private static int spriteAverage(ResourceLocation sprite) {
        Integer cached = SPRITE_CACHE.get(sprite);
        if (cached != null) return cached;

        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        int average = 0;

        try (InputStream input = Minecraft.getInstance().getResourceManager().open(texture);
             NativeImage image = NativeImage.read(input)) {
            long red = 0;
            long green = 0;
            long blue = 0;
            long alphaWeight = 0;

            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    int alpha = FastColor.ABGR32.alpha(abgr);
                    if (alpha == 0) continue;
                    red += (long) FastColor.ABGR32.red(abgr) * alpha;
                    green += (long) FastColor.ABGR32.green(abgr) * alpha;
                    blue += (long) FastColor.ABGR32.blue(abgr) * alpha;
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

    private static final class TintContext implements BlockAndTintGetter {
        private BlockState state;
        private Biome biome;

        void set(BlockState state, Biome biome) {
            this.state = state;
            this.biome = biome;
        }

        @Override
        public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
            return biome == null ? 0xffffff
                    : resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override public float getShade(Direction direction, boolean shade) { return 1.0f; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return state; }
        @Override public FluidState getFluidState(BlockPos pos) {
            return state == null ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()
                    : state.getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
