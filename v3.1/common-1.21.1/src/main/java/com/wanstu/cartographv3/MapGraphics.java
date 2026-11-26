package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

final class MapGraphics {
    private static final ResourceLocation ICONS =
            ResourceLocation.fromNamespaceAndPath("cartograph_v3", "textures/gui/map-icons.png");

    final GuiGraphics nativeGraphics;
    private boolean pendingFills;

    MapGraphics(GuiGraphics graphics) { nativeGraphics = graphics; }

    void push() { nativeGraphics.pose().pushPose(); }
    void pop() { nativeGraphics.pose().popPose(); }
    void translate(float x, float y) { nativeGraphics.pose().translate(x, y, 0); }
    void scale(float factor) { nativeGraphics.pose().scale(factor, factor, 1); }
    void rotate(float degrees) { nativeGraphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees)); }
    void enableBlend() {
        flushFills();
        RenderSystem.enableBlend();
    }

    void flush() {
        nativeGraphics.flush();
        pendingFills = false;
    }

    void flushFills() {
        if (pendingFills) flush();
    }

    void fill(int x0, int y0, int x1, int y1, int color) {
        // Use the same winding as GuiGraphics.fill and submit consecutive quads together.
        fractionalFill(Math.max(x0, x1), Math.max(y0, y1),
                Math.min(x0, x1), Math.min(y0, y1), color);
    }

    void renderOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color);
        fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y + 1, x + 1, y + height - 1, color);
        fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    void drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        flushFills();
        nativeGraphics.drawString(font, text, x, y, color, shadow);
    }

    void enableScissor(int x0, int y0, int x1, int y1) {
        flushFills();
        nativeGraphics.enableScissor(x0, y0, x1, y1);
    }

    void disableScissor() {
        flushFills();
        nativeGraphics.disableScissor();
    }

    void fractionalFill(float x0, float y0, float x1, float y1, int color) {
        var buffer = nativeGraphics.bufferSource().getBuffer(RenderType.gui());
        var pose = nativeGraphics.pose().last().pose();
        buffer.addVertex(pose, x0, y0, 0).setColor(color);
        buffer.addVertex(pose, x0, y1, 0).setColor(color);
        buffer.addVertex(pose, x1, y1, 0).setColor(color);
        buffer.addVertex(pose, x1, y0, 0).setColor(color);
        pendingFills = true;
    }

    void icon(int x, int y, int size, int u, int v, int argb) {
        flushFills();
        nativeGraphics.setColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f,
                (argb & 255) / 255f, (argb >>> 24) / 255f);
        nativeGraphics.blit(ICONS, x, y, size, size, (float) u, (float) v, 32, 32, 256, 256);
        nativeGraphics.setColor(1, 1, 1, 1);
    }

    static void writeAbgr(NativeImage image, int x, int y, int pixel) {
        image.setPixelRGBA(x, y, pixel);
    }

    static final class Texture implements AutoCloseable {
        private final TextureManager manager = Minecraft.getInstance().getTextureManager();
        private final DynamicTexture texture;
        private final ResourceLocation id;
        private final int size;

        Texture(int size) {
            this.size = size;
            texture = new DynamicTexture(size, size, true);
            id = manager.register("cartograph_native_map", texture);
        }

        NativeImage getPixels() { return texture.getPixels(); }
        void upload() { texture.upload(); }

        void upload(int x, int y, int width, int height) {
            texture.bind();
            texture.getPixels().upload(0, x, y, x, y, width, height, false, false);
        }

        void draw(MapGraphics target, int x, int y, int width, int height) {
            target.flushFills();
            target.nativeGraphics.blit(id, x, y, width, height, 0f, 0f, size, size, size, size);
        }

        @Override public void close() { manager.release(id); }
    }
}
