package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

final class MapHifiUi {
    /*
     * The HTML prototype is authored in screen pixels. Minecraft GuiGraphics is
     * authored in GUI-scaled units, so high-fidelity UI is rendered in a
     * physical-pixel coordinate space (see beginPixelSpace/endPixelSpace).
     */
    static final int HEADER_H = 62;
    static final int PANEL_HEADER_H = 50;
    static final int MODAL_HEADER_H = 54;

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

    static double guiScale() {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return scale > 0.0 ? scale : 1.0;
    }

    static int toPixel(double guiCoordinate) {
        return (int) Math.round(guiCoordinate * guiScale());
    }

    static double toPixelD(double guiCoordinate) {
        return guiCoordinate * guiScale();
    }

    static double toGuiD(double pixelCoordinate) {
        return pixelCoordinate / guiScale();
    }

    static int pixelWidth(int guiWidth) {
        return toPixel(guiWidth);
    }

    static int pixelHeight(int guiHeight) {
        return toPixel(guiHeight);
    }

    static int headerGui() {
        return Math.max(1, (int) Math.ceil(HEADER_H / guiScale()));
    }

    static void beginPixelSpace(GuiGraphics g) {
        float scale = (float) (1.0 / guiScale());
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0f);
    }

    static void endPixelSpace(GuiGraphics g) {
        g.pose().popPose();
    }

    static void enablePixelScissor(
            GuiGraphics g, int left, int top, int right, int bottom) {
        int l = (int) Math.floor(toGuiD(left));
        int t = (int) Math.floor(toGuiD(top));
        int r = (int) Math.ceil(toGuiD(right));
        int b = (int) Math.ceil(toGuiD(bottom));
        g.enableScissor(l, t, r, b);
    }

    static void disablePixelScissor(GuiGraphics g) {
        g.disableScissor();
    }

    static void text(
            GuiGraphics g, Font font, String text,
            int x, int y, int color, float pixelSize) {
        float scale = Math.max(0.1f, pixelSize / Math.max(1, font.lineHeight));
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    static void text(
            GuiGraphics g, Font font, FormattedCharSequence text,
            int x, int y, int color, float pixelSize) {
        float scale = Math.max(0.1f, pixelSize / Math.max(1, font.lineHeight));
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    static int textWidth(Font font, String text, float pixelSize) {
        float scale = Math.max(0.1f, pixelSize / Math.max(1, font.lineHeight));
        return Math.round(font.width(text) * scale);
    }

    static void centeredText(
            GuiGraphics g, Font font, String text,
            int centerX, int y, int color, float pixelSize) {
        text(g, font, text,
                centerX - textWidth(font, text, pixelSize) / 2,
                y, color, pixelSize);
    }

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
        g.fill(0, HEADER_H, width, height, 0x4f05080b);
    }

    static void modal(GuiGraphics g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, 0xff141e24);
        border(g, x, y, w, h, BORDER);
    }

    static void panelHeader(
            GuiGraphics g, Font font, int x, int y, int w,
            MapIconAtlas.UiIcon icon, String title, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + PANEL_HEADER_H, 0xff172128);
        g.fill(x, y + PANEL_HEADER_H - 1, x + w, y + PANEL_HEADER_H, 0xff43505a);
        MapIconAtlas.drawUi(g, icon, x + 25, y + 25, 22, 0xffeef2f4);
        text(g, font, title, x + 44, y + 16, TEXT, 16);
        close(g, x + w - 44, y + 10, mouseX, mouseY);
    }

    static void modalHeader(
            GuiGraphics g, Font font, int x, int y, int w,
            MapIconAtlas.UiIcon icon, String title, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + MODAL_HEADER_H, 0xff172128);
        g.fill(x, y + MODAL_HEADER_H - 1, x + w, y + MODAL_HEADER_H, 0xff43505a);
        MapIconAtlas.drawUi(g, icon, x + 25, y + 27, 22, 0xffeef2f4);
        text(g, font, title, x + 44, y + 17, TEXT, 18);
        close(g, x + w - 44, y + 12, mouseX, mouseY);
    }

    static boolean closeHit(double mx, double my, int x, int y, int w) {
        return inside(mx, my, x + w - 44, y + 10, 30, 30);
    }

    static boolean modalCloseHit(double mx, double my, int x, int y, int w) {
        return inside(mx, my, x + w - 44, y + 12, 30, 30);
    }

    static void close(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, 30, 30);
        if (hover) g.fill(x, y, x + 30, y + 30, 0xff2f3a42);
        MapIconAtlas.drawUi(g, MapIconAtlas.UiIcon.CLOSE, x + 15, y + 15, 18,
                hover ? 0xffffffff : 0xffd9e0e4);
    }

    static void nav(
            GuiGraphics g, Font font, int x, int y, int w, int h,
            MapIconAtlas.UiIcon icon, String text, boolean active,
            int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        if (active || hover) {
            g.fill(x, y, x + w, y + h, active ? 0x18ffffff : 0x0dffffff);
        }
        if (active) g.fill(x, y + h - 3, x + w, y + h, ACCENT);
        MapIconAtlas.drawUi(g, icon, x + w / 2, y + 21, 22,
                active ? 0xffffffff : 0xffd8e0e4);
        centeredText(g, font, text, x + w / 2, y + 38,
                active ? 0xffffffff : 0xffcbd4d9, 12);
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
        float size = 14;
        centeredText(g, font, text, x + w / 2,
                y + Math.max(4, (h - Math.round(size)) / 2),
                0xffffffff, size);
    }

    static void tab(
            GuiGraphics g, Font font, int x, int y, int w, int h,
            String text, boolean active, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        g.fill(x, y, x + w, y + h,
                active ? 0xff33414b : hover ? 0xff28343c : 0xff131c22);
        border(g, x, y, w, h, active ? 0xff526470 : 0xff34424a);
        if (active) g.fill(x, y + h - 3, x + w, y + h, 0xff68b5f7);
        centeredText(g, font, text, x + w / 2,
                y + Math.max(4, (h - 13) / 2),
                active ? 0xffffffff : 0xffb9c4ca, 13);
    }

    static void switchBox(
            GuiGraphics g, int x, int y, boolean on, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, 43, 24);
        int bg = on ? (hover ? 0xff5aabf1 : 0xff4da1ef)
                : (hover ? 0xff727f87 : 0xff66727a);
        g.fill(x, y, x + 43, y + 24, bg);
        int knobX = on ? x + 22 : x + 3;
        g.fill(knobX, y + 3, knobX + 18, y + 21, 0xfff4f6f7);
    }

    static void checkbox(
            GuiGraphics g, int x, int y, boolean on, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, 19, 19);
        g.fill(x, y, x + 19, y + 19,
                on ? ACCENT : hover ? 0xff35434c : 0xff10171b);
        border(g, x, y, 19, 19, on ? 0xffe7bd42 : 0xff7c8991);
        if (on) {
            g.fill(x + 4, y + 9, x + 8, y + 13, 0xff273038);
            g.fill(x + 7, y + 12, x + 15, y + 14, 0xff273038);
        }
    }

    static void card(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xff172126);
        border(g, x, y, w, h, 0xff3e4b54);
    }

    static void divider(GuiGraphics g, int x1, int y, int x2) {
        g.fill(x1, y, x2, y + 1, 0xff3e4b54);
    }

    static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    static void shadow(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 6, y + 8, x + w + 10, y + h + 12, 0x52000000);
    }

    static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }
}
