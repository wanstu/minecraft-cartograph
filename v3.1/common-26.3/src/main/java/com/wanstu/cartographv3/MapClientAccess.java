package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import com.mojang.blaze3d.platform.InputConstants;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class MapClientAccess {
    record ModelLayer(String sprite, int tintIndex) {}

    private static final TintContext TINT_CONTEXT = new TintContext();

    private MapClientAccess() {}

    static KeyMapping openKey() {
        return new KeyMapping("key.cartograph_v3.open", InputConstants.KEY_M,
                KeyMapping.Category.register(MapGameAccess.id("cartograph_v3", "map")));
    }

    static boolean hasOverlay(Minecraft client) { return client.gui.overlay() != null; }
    static Screen screen(Minecraft client) { return client.gui.screen(); }
    static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }
    static void openUri(URI uri) { Blaze3D.openUri(uri); }
    static int readAbgr(NativeImage image, int x, int y) {
        int argb = image.getPixel(x, y);
        return (argb & 0xff00ff00) | ((argb >>> 16) & 255) | ((argb & 255) << 16);
    }

    static List<ModelLayer> topLayers(Minecraft client, BlockState state, long seed) {
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(seed), parts);
        List<ModelLayer> layers = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (var quad : part.getQuads(Direction.UP)) {
                var material = quad.materialInfo();
                layers.add(new ModelLayer(material.sprite().contents().name().toString(),
                        material.tintIndex()));
            }
            for (var quad : part.getQuads(null)) {
                if (quad.direction() != Direction.UP) continue;
                var material = quad.materialInfo();
                layers.add(new ModelLayer(material.sprite().contents().name().toString(),
                        material.tintIndex()));
            }
        }
        return layers;
    }

    static String particleSprite(Minecraft client, BlockState state) {
        var sprite = client.getModelManager().getBlockStateModelSet().get(state).particleMaterial().sprite();
        return sprite == null ? null : sprite.contents().name().toString();
    }

    static int tint(Minecraft client, BlockState state, Biome biome, BlockPos pos, int index) {
        TINT_CONTEXT.state = state;
        TINT_CONTEXT.biome = biome;
        return client.getBlockColors().getTintSource(state, index).colorInWorld(state, TINT_CONTEXT, pos);
    }

    private static final class TintContext implements BlockAndTintGetter {
        private BlockState state;
        private Biome biome;

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return biome == null ? 0xffffff : resolver.getColor(biome, pos.getX(), pos.getZ());
        }

        @Override public CardinalLighting cardinalLighting() { return CardinalLighting.DEFAULT; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return state; }
        @Override public FluidState getFluidState(BlockPos pos) {
            return state == null ? Fluids.EMPTY.defaultFluidState() : state.getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }
}
