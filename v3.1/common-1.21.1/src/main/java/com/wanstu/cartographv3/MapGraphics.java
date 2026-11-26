package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

final class MapGraphics {
    private static final ResourceLocation ICONS =
            ResourceLocation.fromNamespaceAndPath("cartograph_v3", "textures/gui/map-icons.png");

    static final ResourceLocation ICON_SHADER =
            ResourceLocation.fromNamespaceAndPath("cartograph_v3", "map_icon");
    private static ShaderInstance iconShader;

    final GuiGraphics nativeGraphics;
    private boolean pendingFills;
    private BufferBuilder iconBatch;

    MapGraphics(GuiGraphics graphics) { nativeGraphics = graphics; }

    static void setIconShader(ShaderInstance shader) { iconShader = shader; }

    void push() { nativeGraphics.pose().pushPose(); }
    void pop() { nativeGraphics.pose().popPose(); }
    void translate(float x, float y) { nativeGraphics.pose().translate(x, y, 0); }
    void scale(float factor) { nativeGraphics.pose().scale(factor, factor, 1); }
    void rotate(float degrees) { nativeGraphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees)); }
    void enableBlend() {
        flushPending();
        RenderSystem.enableBlend();
    }

    void flush() {
        flushIcons();
        nativeGraphics.flush();
        pendingFills = false;
    }

    void flushPending() {
        if (pendingFills) flush();
        else flushIcons();
    }

    private void flushIcons() {
        if (iconBatch == null) return;
        var vertices = iconBatch;
        iconBatch = null;
        RenderSystem.setShaderTexture(0, ICONS);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShader(() -> iconShader);
        BufferUploader.drawWithShader(vertices.buildOrThrow());
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
        flushPending();
        nativeGraphics.drawString(font, text, x, y, color, shadow);
    }

    void enableScissor(int x0, int y0, int x1, int y1) {
        flushPending();
        nativeGraphics.enableScissor(x0, y0, x1, y1);
    }

    void disableScissor() {
        flushPending();
        nativeGraphics.disableScissor();
    }

    void fractionalFill(float x0, float y0, float x1, float y1, int color) {
        flushIcons();
        var buffer = nativeGraphics.bufferSource().getBuffer(RenderType.gui());
        var pose = nativeGraphics.pose().last().pose();
        buffer.addVertex(pose, x0, y0, 0).setColor(color);
        buffer.addVertex(pose, x0, y1, 0).setColor(color);
        buffer.addVertex(pose, x1, y1, 0).setColor(color);
        buffer.addVertex(pose, x1, y0, 0).setColor(color);
        pendingFills = true;
    }

    void icon(int x, int y, int size, int u, int v, int argb) {
        if (pendingFills) flush();
        if (iconShader == null) {
            drawImmediateIcon(x, y, size, u, v, argb);
            return;
        }
        if (iconBatch == null) {
            iconBatch = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        }
        var pose = nativeGraphics.pose().last().pose();
        float u0 = u / 256f, u1 = (u + 32) / 256f;
        float v0 = v / 256f, v1 = (v + 32) / 256f;
        iconBatch.addVertex(pose, x, y, 0).setUv(u0, v0).setColor(argb);
        iconBatch.addVertex(pose, x, y + size, 0).setUv(u0, v1).setColor(argb);
        iconBatch.addVertex(pose, x + size, y + size, 0).setUv(u1, v1).setColor(argb);
        iconBatch.addVertex(pose, x + size, y, 0).setUv(u1, v0).setColor(argb);
    }

    private void drawImmediateIcon(int x, int y, int size, int u, int v, int argb) {
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
            target.flushPending();
            target.nativeGraphics.blit(id, x, y, width, height, 0f, 0f, size, size, size, size);
        }

        @Override public void close() { manager.release(id); }
    }
}
