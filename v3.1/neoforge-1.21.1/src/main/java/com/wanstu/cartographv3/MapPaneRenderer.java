package com.wanstu.cartographv3;

import net.ethrocky.pane.core.Rect;
import net.ethrocky.pane.render.PaneRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Pane renderer used by Cartograph V3.1.
 *
 * Pane normally works in Minecraft GUI units. Cartograph instead defines its
 * layout in screen-pixel-like logical units so the UI does not inflate when the
 * player changes Minecraft's GUI Scale.
 *
 * userUiScale controls geometry only. userFontScale controls text only.
 */
final class MapPaneRenderer implements PaneRenderer {
    private static final double BASE_FONT_SCALE = 1.35;

    private final GuiGraphics graphics;
    private final Font font;
    private final double userUiScale;
    private final double renderScale;
    private final double textScale;

    MapPaneRenderer(
            GuiGraphics graphics, Font font,
            double userUiScale, double userFontScale) {
        this.graphics = graphics;
        this.font = font;
        this.userUiScale = Math.max(0.1, userUiScale);

        double minecraftGuiScale =
                Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        this.renderScale = this.userUiScale / minecraftGuiScale;

        // The outer UI transform must not implicitly resize text: compensate
        // for userUiScale, then apply only the dedicated font setting.
        this.textScale = BASE_FONT_SCALE
                * Math.max(0.1, userFontScale)
                / this.userUiScale;
    }

    static double currentRenderScale() {
        double minecraftGuiScale =
                Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
        return MapUiSettings.uiScaleFactor() / minecraftGuiScale;
    }

    void begin() {
        graphics.pose().pushPose();
        graphics.pose().scale((float) renderScale, (float) renderScale, 1.0f);
    }

    void end() {
        graphics.pose().popPose();
    }

    int logicalWidth(int guiWidth) {
        return Math.max(1, (int) Math.floor(guiWidth / renderScale));
    }

    int logicalHeight(int guiHeight) {
        return Math.max(1, (int) Math.floor(guiHeight / renderScale));
    }

    double logicalX(double guiX) {
        return guiX / renderScale;
    }

    double logicalY(double guiY) {
        return guiY / renderScale;
    }

    GuiGraphics graphics() {
        return graphics;
    }

    void drawUiIcon(
            MapIconAtlas.UiIcon icon,
            int centerX, int centerY, int size, int rgb) {
        MapIconAtlas.drawUi(graphics, icon, centerX, centerY, size, rgb);
    }

    @Override
    public void fill(Rect r, int argb) {
        graphics.fill(r.x(), r.y(), r.right(), r.bottom(), argb);
    }

    @Override
    public void border(Rect r, int argb, int width) {
        int w = Math.max(1, width);
        graphics.fill(r.x(), r.y(), r.right(), r.y() + w, argb);
        graphics.fill(r.x(), r.bottom() - w, r.right(), r.bottom(), argb);
        graphics.fill(r.x(), r.y() + w, r.x() + w, r.bottom() - w, argb);
        graphics.fill(r.right() - w, r.y() + w, r.right(), r.bottom() - w, argb);
    }

    @Override
    public void text(String s, int x, int y, int argb) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0f);
        graphics.pose().scale((float) textScale, (float) textScale, 1.0f);
        graphics.drawString(font, s, 0, 0, argb, false);
        graphics.pose().popPose();
    }

    @Override
    public void gradientV(Rect r, int topArgb, int bottomArgb) {
        graphics.fillGradient(
                r.x(), r.y(), r.right(), r.bottom(), topArgb, bottomArgb);
    }

    @Override
    public void pushClip(Rect r) {
        // Scissor coordinates use Minecraft GUI units and ignore the pose stack.
        int x1 = (int) Math.floor(r.x() * renderScale);
        int y1 = (int) Math.floor(r.y() * renderScale);
        int x2 = (int) Math.ceil(r.right() * renderScale);
        int y2 = (int) Math.ceil(r.bottom() * renderScale);
        graphics.enableScissor(x1, y1, x2, y2);
    }

    @Override
    public void popClip() {
        graphics.disableScissor();
    }

    @Override
    public int width(String s) {
        return Math.max(0, (int) Math.ceil(font.width(s) * textScale));
    }

    @Override
    public int height() {
        return Math.max(1, (int) Math.ceil(font.lineHeight * textScale));
    }
}
