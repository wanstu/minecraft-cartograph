package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

final class MapHifiUi {
    static final int HEADER_H = 36;
    static final int ACCENT = 0xffffd34e;
    static final int BG = 0xff10191e;
    static final int PANEL = 0xf2182229;
    static final int PANEL_ALT = 0xf21d282f;
    static final int BORDER = 0xff40505a;
    static final int TEXT = 0xffeef2f4;
    static final int MUTED = 0xff95a2aa;
    static final int DANGER = 0xffe06767;
    static final int PRIMARY = 0xff4c9ee8;

    private MapHifiUi() {}

    static void topbar(GuiGraphics g, int width) {
        g.fill(0, 0, width, HEADER_H, 0xf410191e);
        g.fill(0, HEADER_H - 1, width, HEADER_H, BORDER);
    }

    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, PANEL);
        border(g, x, y, w, h, BORDER);
    }

    static void modalShade(GuiGraphics g, int width, int height) {
        g.fill(0, HEADER_H, width, height, 0x8a000000);
    }

    static void modal(GuiGraphics g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, 0xff141e24);
        border(g, x, y, w, h, BORDER);
    }

    static void panelHeader(
            GuiGraphics g, Font font, int x, int y, int w,
            MapIconAtlas.UiIcon icon, String title, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + 31, 0xff172128);
        g.fill(x, y + 30, x + w, y + 31, 0xff35434c);
        MapIconAtlas.drawUi(g, icon, x + 15, y + 15, 13, 0xffeef2f4);
        g.drawString(font, title, x + 28, y + 11, TEXT, false);
        close(g, x + w - 24, y + 5, mouseX, mouseY);
    }

    static boolean closeHit(double mx, double my, int x, int y, int w) {
        return inside(mx, my, x + w - 24, y + 5, 18, 18);
    }

    static void close(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, 18, 18);
        if (hover) g.fill(x, y, x + 18, y + 18, 0xff2f3a42);
        MapIconAtlas.drawUi(g, MapIconAtlas.UiIcon.CLOSE, x + 9, y + 9, 11,
                hover ? 0xffffffff : 0xffd9e0e4);
    }

    static void nav(
            GuiGraphics g, Font font, int x, int y, int w, int h,
            MapIconAtlas.UiIcon icon, String text, boolean active,
            int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        if (active || hover) g.fill(x, y, x + w, y + h, active ? 0x18ffffff : 0x0dffffff);
        if (active) g.fill(x, y + h - 2, x + w, y + h, ACCENT);
        MapIconAtlas.drawUi(g, icon, x + w / 2, y + 10, 12,
                active ? 0xffffffff : 0xffd8e0e4);
        int tw = font.width(text);
        g.drawString(font, text, x + (w - tw) / 2, y + 20,
                active ? 0xffffffff : 0xffcbd4d9, false);
    }

    static void button(
            GuiGraphics g, Font font, int x, int y, int w, int h,
            String text, boolean primary, boolean danger,
            int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        int bg;
        if (danger) bg = hover ? 0xff813f44 : 0xff66353a;
        else if (primary) bg = hover ? 0xff5aa9ef : PRIMARY;
        else bg = hover ? 0xff34424b : 0xff26323a;
        g.fill(x, y, x + w, y + h, bg);
        border(g, x, y, w, h, hover ? 0xff61717b : BORDER);
        g.drawCenteredString(font, text, x + w / 2, y + Math.max(4, (h - font.lineHeight) / 2),
                0xffffffff);
    }

    static void tab(
            GuiGraphics g, Font font, int x, int y, int w, int h,
            String text, boolean active, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        g.fill(x, y, x + w, y + h,
                active ? 0xff33414b : hover ? 0xff28343c : 0xff131c22);
        border(g, x, y, w, h, active ? 0xff526470 : 0xff34424a);
        if (active) g.fill(x, y + h - 2, x + w, y + h, ACCENT);
        g.drawCenteredString(font, text, x + w / 2,
                y + Math.max(4, (h - font.lineHeight) / 2),
                active ? 0xffffffff : 0xffb9c4ca);
    }

    static void switchBox(
            GuiGraphics g, int x, int y, boolean on, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, 28, 15);
        int bg = on ? (hover ? 0xffe4bd43 : 0xffd8ae32)
                : (hover ? 0xff59666e : 0xff465159);
        g.fill(x, y, x + 28, y + 15, bg);
        int knobX = on ? x + 16 : x + 2;
        g.fill(knobX, y + 2, knobX + 11, y + 13, 0xfff4f6f7);
    }

    static void checkbox(
            GuiGraphics g, int x, int y, boolean on, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, 14, 14);
        g.fill(x, y, x + 14, y + 14,
                on ? ACCENT : hover ? 0xff35434c : 0xff182229);
        border(g, x, y, 14, 14, on ? 0xffffdf73 : 0xff59666e);
        if (on) {
            g.fill(x + 3, y + 7, x + 6, y + 10, 0xff273038);
            g.fill(x + 5, y + 9, x + 11, y + 11, 0xff273038);
        }
    }

    static void card(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xff182229);
        border(g, x, y, w, h, 0xff35434c);
    }

    static void divider(GuiGraphics g, int x1, int y, int x2) {
        g.fill(x1, y, x2, y + 1, 0xff35434c);
    }

    static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    static void shadow(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 3, y + 4, x + w + 5, y + h + 6, 0x52000000);
    }

    static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }
}
