package com.wanstu.cartographv3;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

final class MapIconAtlas {
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
            this.texture = texture("ui/" + name);
        }
    }

    private static final ResourceLocation PLAYER_ARROW = texture("player/arrow");
    private static final ResourceLocation PIN = texture("marker/pin");
    private static final ResourceLocation VILLAGE = texture("marker/village");
    private static final ResourceLocation MANSION = texture("marker/mansion");
    private static final ResourceLocation TEMPLE = texture("marker/temple");
    private static final ResourceLocation OUTPOST = texture("marker/outpost");
    private static final ResourceLocation MINE = texture("marker/mine");
    private static final ResourceLocation PORTAL = texture("marker/portal");
    private static final ResourceLocation SHIP = texture("marker/ship");
    private static final ResourceLocation CITY = texture("marker/city");
    private static final ResourceLocation FORTRESS = texture("marker/fortress");
    private static final ResourceLocation HOME = texture("marker/home");
    private static final ResourceLocation FLAG = texture("marker/flag");
    private static final ResourceLocation CAMP = texture("marker/camp");
    private static final ResourceLocation FARM = texture("marker/farm");
    private static final ResourceLocation SHOP = texture("marker/shop");
    private static final ResourceLocation RAIL = texture("marker/rail");
    private static final ResourceLocation HARBOR = texture("marker/harbor");
    private static final ResourceLocation FACTORY = texture("marker/factory");
    private static final ResourceLocation TOWER = texture("marker/tower");
    private static final ResourceLocation CAVE = texture("marker/cave");
    private static final ResourceLocation MOUNTAIN = texture("marker/mountain");
    private static final ResourceLocation TREE = texture("marker/tree");
    private static final ResourceLocation WATER = texture("marker/water");
    private static final ResourceLocation DANGER = texture("marker/danger");
    private static final ResourceLocation INFO = texture("marker/info");
    private static final ResourceLocation FOOD = texture("marker/food");
    private static final ResourceLocation STAR = texture("marker/star");

    private MapIconAtlas() {}

    static void drawUi(GuiGraphics graphics, UiIcon icon, int centerX, int centerY, int size, int rgb) {
        draw(graphics, icon.texture, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawPlayerArrow(
            GuiGraphics graphics, int centerX, int centerY, int size, int rgb) {
        draw(graphics, PLAYER_ARROW, centerX, centerY, size + 3, 0x111315, 0.95f);
        draw(graphics, PLAYER_ARROW, centerX, centerY, size, rgb, 1.0f);
    }

    static void drawMarker(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int centerX, int centerY, int size, int rgb, boolean hovered) {
        ResourceLocation texture = switch (icon) {
            case PIN -> PIN;
            case VILLAGE -> VILLAGE;
            case MANSION -> MANSION;
            case TEMPLE -> TEMPLE;
            case OUTPOST -> OUTPOST;
            case MINE -> MINE;
            case PORTAL -> PORTAL;
            case SHIP -> SHIP;
            case CITY -> CITY;
            case FORTRESS -> FORTRESS;
            case HOME -> HOME;
            case FLAG -> FLAG;
            case CAMP -> CAMP;
            case FARM -> FARM;
            case SHOP -> SHOP;
            case RAIL -> RAIL;
            case HARBOR -> HARBOR;
            case FACTORY -> FACTORY;
            case TOWER -> TOWER;
            case CAVE -> CAVE;
            case MOUNTAIN -> MOUNTAIN;
            case TREE -> TREE;
            case WATER -> WATER;
            case DANGER -> DANGER;
            case INFO -> INFO;
            case FOOD -> FOOD;
            case STAR -> STAR;
        };
        if (hovered) {
            draw(graphics, texture, centerX, centerY, size + 4, 0xffffff, 0.95f);
        }
        draw(graphics, texture, centerX, centerY, size, rgb, 1.0f);
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
                32, 32,
                32, 32);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                CartographV3Mod.MOD_ID,
                "textures/gui/icons/" + name + ".png");
    }
}
