package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class MapClientAccess {
    record ModelLayer(String sprite, int tintIndex) {}

    private static final TintContext TINT_CONTEXT = new TintContext();

    private MapClientAccess() {}

    static KeyMapping openKey() {
        return new KeyMapping("key.cartograph_v3.open", GLFW.GLFW_KEY_M, "key.categories.cartograph_v3");
    }

    static Screen screen(Minecraft client) { return client.screen; }
    static void setScreen(Minecraft client, Screen screen) { client.setScreen(screen); }
    static void openUri(URI uri) { Util.getPlatform().openUri(uri); }
    static int readAbgr(NativeImage image, int x, int y) { return image.getPixelRGBA(x, y); }

    static List<ModelLayer> topLayers(Minecraft client, BlockState state, long seed) {
        var model = client.getBlockRenderer().getBlockModel(state);
        var random = RandomSource.create(seed);
        var quads = new ArrayList<>(model.getQuads(state, Direction.UP, random));
        random.setSeed(seed);
        for (var quad : model.getQuads(state, null, random)) {
            if (quad.getDirection() == Direction.UP) quads.add(quad);
        }
        List<ModelLayer> layers = new ArrayList<>();
        for (var quad : quads) {
            layers.add(new ModelLayer(quad.getSprite().contents().name().toString(),
                    quad.isTinted() ? quad.getTintIndex() : -1));
        }
        return layers;
    }

    static String particleSprite(Minecraft client, BlockState state) {
        var sprite = client.getBlockRenderer().getBlockModel(state).getParticleIcon();
        return sprite == null ? null : sprite.contents().name().toString();
    }

    static int tint(Minecraft client, BlockState state, Biome biome, BlockPos pos, int index) {
        TINT_CONTEXT.state = state;
        TINT_CONTEXT.biome = biome;
        return client.getBlockColors().getColor(state, TINT_CONTEXT, pos, index);
    }

    private static final class TintContext implements BlockAndTintGetter {
        private BlockState state;
        private Biome biome;

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return biome == null ? 0xffffff : resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override public float getShade(Direction direction, boolean shade) { return 1.0f; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return state; }
        @Override public FluidState getFluidState(BlockPos pos) {
            return state == null ? Fluids.EMPTY.defaultFluidState() : state.getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
