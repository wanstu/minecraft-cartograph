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

    private static final ResourceLocation PLAYER_ARROW =
            texture("player/arrow_mono");
    private static final ResourceLocation PLAYER_CROSS =
            texture("player/cross_mono");

    private MapIconAtlas() {}

    static void drawUi(
            GuiGraphics graphics, UiIcon icon,
            int centerX, int centerY, int size, int rgb) {
        draw(graphics, icon.texture, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawPlayerArrow(
            GuiGraphics graphics, int centerX, int centerY, int size, int rgb) {
        draw(graphics, PLAYER_ARROW, centerX, centerY, size + 3, 0x101317, 0.95f);
        draw(graphics, PLAYER_ARROW, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawPlayerCross(
            GuiGraphics graphics, int centerX, int centerY, int size, int rgb) {
        draw(graphics, PLAYER_CROSS, centerX, centerY, size + 3, 0x101317, 0.95f);
        draw(graphics, PLAYER_CROSS, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawMarker(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size, int rgb,
            boolean hovered, boolean htmlPalette) {
        String base = "marker/" + icon.name().toLowerCase(Locale.ROOT);
        ResourceLocation texture = texture(base + (htmlPalette ? "" : "_mono"));

        if (hovered) {
            ResourceLocation halo = texture(base + "_mono");
            draw(graphics, halo, centerX, centerY, size + 4, 0xffffff, 0.72f);
        }

        if (htmlPalette) {
            draw(graphics, texture, centerX, centerY, size, 0xffffff, 1.0f);
        } else {
            draw(graphics, texture, centerX, centerY, size, rgb, 1.0f);
        }
    }

    static void drawMarker(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size, int rgb, boolean hovered) {
        drawMarker(graphics, icon, centerX, centerY, size, rgb, hovered, false);
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
