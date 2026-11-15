package com.wanstu.cartographv3;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class MapIconAtlas {
    private static final int LEGACY_TEXTURE_SIZE = 64;
    private static final int MONO_TEXTURE_SIZE = 256;
    private static final Set<ResourceLocation> SMOOTH_TEXTURES = new HashSet<>();

    enum UiIcon {
        MENU("menu"),
        MAP("map"),
        ABOUT("about"),
        COPY("copy"),
        MARKERS("markers"),
        LAYERS("layers"),
        SETTINGS("settings"),
        LOCATE("locate"),
        ADD("add"),
        TELEPORT("teleport"),
        EDIT("edit"),
        TRASH("trash"),
        RESTORE("restore"),
        RESET("reset"),
        CLOSE("close");

        private final ResourceLocation texture;

        UiIcon(String name) {
            this.texture = texture("ui/" + name + "_mono");
        }
    }

    private MapIconAtlas() {}

    static void drawUi(
            GuiGraphics graphics, UiIcon icon,
            int centerX, int centerY, int size, int rgb) {
        drawMono(graphics, icon.texture, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawPlayerArrow(
            GuiGraphics graphics, int centerX, int centerY, int size,
            int primary, int accent, int border) {
        drawMono(
                graphics, texture("player/arrow_mono"),
                centerX, centerY, size, accent, 1.0f);
    }

    static void drawPlayerCross(
            GuiGraphics graphics, int centerX, int centerY, int size,
            int primary, int accent, int border) {
        drawMono(
                graphics, texture("player/cross_mono"),
                centerX, centerY, size, accent, 1.0f);
    }

    static void drawPlayerRing(
            GuiGraphics graphics, int centerX, int centerY, int diameter,
            int rgb, float alpha) {
        drawMono(
                graphics, texture("player/ring_mono"),
                centerX, centerY, diameter, rgb, alpha);
    }

    static void drawMarker(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size,
            int primary, int accent, int border,
            boolean hovered) {
        int drawSize = hovered ? size + 1 : size;
        drawMarkerMono(graphics, icon, centerX, centerY, drawSize, accent);
    }

    static void drawMarkerMono(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size, int rgb) {
        drawMono(
                graphics,
                texture("marker/" + icon.name().toLowerCase(Locale.ROOT) + "_mono"),
                centerX, centerY, size, rgb, 1.0f);
    }

    static void drawMarkerPreview(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size) {
        drawMarkerMono(graphics, icon, centerX, centerY, size, 0xffeef2f4);
    }

    /**
     * Retained only for any still-unmigrated legacy UI resources. V3.1 marker
     * and player artwork uses the high-resolution mono path above.
     */
    @SuppressWarnings("unused")
    private static void drawChannels(
            GuiGraphics graphics, String base,
            int centerX, int centerY, int size,
            int primary, int accent, int border) {
        ResourceLocation silhouette = texture(base + "_silhouette");
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oy == 0) continue;
                drawLegacy(graphics, silhouette,
                        centerX + ox, centerY + oy, size, border, 1.0f);
            }
        }
        drawLegacy(graphics, texture(base + "_base"),
                centerX, centerY, size, 0xffffff, 1.0f);
        drawLegacy(graphics, texture(base + "_primary"),
                centerX, centerY, size, primary, 1.0f);
        drawLegacy(graphics, texture(base + "_accent"),
                centerX, centerY, size, accent, 1.0f);
    }

    private static void drawMono(
            GuiGraphics graphics, ResourceLocation texture,
            int centerX, int centerY, int size, int rgb, float alpha) {
        prepareSmooth(texture);
        draw(graphics, texture, centerX, centerY, size,
                rgb, alpha, MONO_TEXTURE_SIZE);
    }

    private static void drawLegacy(
            GuiGraphics graphics, ResourceLocation texture,
            int centerX, int centerY, int size, int rgb, float alpha) {
        draw(graphics, texture, centerX, centerY, size,
                rgb, alpha, LEGACY_TEXTURE_SIZE);
    }

    private static void prepareSmooth(ResourceLocation texture) {
        if (!SMOOTH_TEXTURES.add(texture)) return;
        Minecraft.getInstance()
                .getTextureManager()
                .getTexture(texture)
                .setFilter(true, false);
    }

    private static void draw(
            GuiGraphics graphics, ResourceLocation texture,
            int centerX, int centerY, int size, int rgb, float alpha,
            int textureSize) {
        int normalized = rgb & 0x00ffffff;
        float r = ((normalized >>> 16) & 255) / 255.0f;
        float g = ((normalized >>> 8) & 255) / 255.0f;
        float b = (normalized & 255) / 255.0f;

        RenderSystem.setShaderColor(r, g, b, alpha);
        graphics.blit(
                texture,
                centerX - size / 2,
                centerY - size / 2,
                size, size,
                0.0f, 0.0f,
                textureSize, textureSize,
                textureSize, textureSize);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                CartographV3Mod.MOD_ID,
                "textures/gui/icons/" + name + ".png");
    }
}
