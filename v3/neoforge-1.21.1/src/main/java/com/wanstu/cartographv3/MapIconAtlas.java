package com.wanstu.cartographv3;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

final class MapIconAtlas {
    private static final int TEXTURE_SIZE = 64;

    enum UiIcon {
        MENU("menu"),
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
        draw(graphics, icon.texture, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawPlayerArrow(
            GuiGraphics graphics, int centerX, int centerY, int size,
            int primary, int accent, int line) {
        drawChannels(
                graphics, "player/arrow",
                centerX, centerY, size,
                primary, accent, line);
    }

    static void drawPlayerCross(
            GuiGraphics graphics, int centerX, int centerY, int size,
            int primary, int accent, int line) {
        drawChannels(
                graphics, "player/cross",
                centerX, centerY, size,
                primary, accent, line);
    }

    static void drawMarker(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size,
            int primary, int accent, int line,
            boolean hovered) {
        int drawSize = hovered ? size + 1 : size;
        drawChannels(
                graphics,
                "marker/" + icon.name().toLowerCase(Locale.ROOT),
                centerX, centerY, drawSize,
                primary, accent, line);
    }

    static void drawMarkerPreview(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size) {
        drawMarker(
                graphics, icon, centerX, centerY, size,
                MapMarkerStore.DEFAULT_PRIMARY_COLOR,
                MapMarkerStore.DEFAULT_ACCENT_COLOR,
                MapMarkerStore.DEFAULT_LINE_COLOR,
                false);
    }

    private static void drawChannels(
            GuiGraphics graphics, String base,
            int centerX, int centerY, int size,
            int primary, int accent, int line) {
        draw(graphics, texture(base + "_base"), centerX, centerY, size, 0xffffff, 1.0f);
        draw(graphics, texture(base + "_primary"), centerX, centerY, size, primary, 1.0f);
        draw(graphics, texture(base + "_accent"), centerX, centerY, size, accent, 1.0f);
        draw(graphics, texture(base + "_line"), centerX, centerY, size, line, 1.0f);
    }

    private static void draw(
            GuiGraphics graphics, ResourceLocation texture,
            int centerX, int centerY, int size, int rgb, float alpha) {
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
                TEXTURE_SIZE, TEXTURE_SIZE,
                TEXTURE_SIZE, TEXTURE_SIZE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                CartographV3Mod.MOD_ID,
                "textures/gui/icons/" + name + ".png");
    }
}
