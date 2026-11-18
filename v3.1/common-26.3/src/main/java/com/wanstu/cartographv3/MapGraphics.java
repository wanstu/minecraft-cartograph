package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicLong;

final class MapGraphics {
    private static final Identifier ICONS =
            Identifier.fromNamespaceAndPath("cartograph_v3", "textures/gui/map-icons.png");

    final GuiGraphicsExtractor nativeGraphics;

    MapGraphics(GuiGraphicsExtractor graphics) { nativeGraphics = graphics; }

    void push() { nativeGraphics.pose().pushMatrix(); }
    void pop() { nativeGraphics.pose().popMatrix(); }
    void translate(float x, float y) { nativeGraphics.pose().translate(x, y); }
    void scale(float factor) { nativeGraphics.pose().scale(factor, factor); }
    void rotate(float degrees) { nativeGraphics.pose().rotate((float) Math.toRadians(degrees)); }
    void enableBlend() { /* The selected GUI pipelines provide blending. */ }
    void flush() { /* GUI render states are submitted together by Minecraft. */ }

    void fill(int x0, int y0, int x1, int y1, int color) {
        nativeGraphics.fill(x0, y0, x1, y1, color);
    }

    void renderOutline(int x, int y, int width, int height, int color) {
        nativeGraphics.outline(x, y, width, height, color);
    }

    void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        nativeGraphics.text(font, text, x, y, color, shadow);
    }

    void enableScissor(int x0, int y0, int x1, int y1) {
        push();
        try {
            nativeGraphics.pose().identity();
            nativeGraphics.enableScissor(x0, y0, x1, y1);
        } finally {
            pop();
        }
    }

    void disableScissor() { nativeGraphics.disableScissor(); }

    void fractionalFill(float x0, float y0, float x1, float y1, int color) {
        push();
        try {
            translate(x0, y0);
            nativeGraphics.pose().scale(x1 - x0, y1 - y0);
            fill(0, 0, 1, 1, color);
        } finally {
            pop();
        }
    }

    void icon(int x, int y, int size, int u, int v, int argb) {
        nativeGraphics.blit(RenderPipelines.GUI_TEXTURED, ICONS, x, y,
                (float) u, (float) v, size, size, 32, 32, 256, 256, argb);
    }

    static void writeAbgr(NativeImage image, int x, int y, int pixel) {
        image.setPixelABGR(x, y, pixel);
    }

    static final class Texture implements AutoCloseable {
        private static final AtomicLong IDS = new AtomicLong();

        private final TextureManager manager = Minecraft.getInstance().getTextureManager();
        private final DynamicTexture texture;
        private final Identifier id;
        private final int size;

        Texture(int size) {
            this.size = size;
            id = Identifier.fromNamespaceAndPath("cartograph_v3", "dynamic/map_atlas_" + IDS.incrementAndGet());
            texture = new DynamicTexture(id::toString, size, size, true);
            manager.register(id, texture);
        }

        NativeImage getPixels() { return texture.getPixels(); }
        void upload() { texture.upload(); }

        void upload(int x, int y, int width, int height) {
            try (var slice = new NativeImage(width, height, false)) {
                texture.getPixels().copyRect(slice, x, y, 0, 0, width, height, false, false);
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                        texture.getTexture(), slice, 0, 0, x, y);
            }
        }

        void draw(MapGraphics target, int x, int y, int width, int height) {
            target.nativeGraphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y,
                    0f, 0f, width, height, size, size, size, size, 0xffffffff);
        }

        @Override public void close() { manager.release(id); }
    }
}
