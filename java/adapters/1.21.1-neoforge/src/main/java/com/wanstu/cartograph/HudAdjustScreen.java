package com.wanstu.cartograph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * WYSIWYG for TWO different display surfaces: floating minimap and held
 * antique atlas. Both preview branches invoke exactly the same rendering
 * and layout used by CartographClient.hud in ordinary play.
 */
final class HudAdjustScreen extends Screen {
    enum Mode { FLOAT, HELD }

    private final AtlasScreen parent;
    private final ClientMapSettings cfg = ClientMapSettings.get();
    private final double oldX = cfg.hudX, oldY = cfg.hudY;
    private final int oldSize = cfg.hudSize;
    private final double oldHeldX = cfg.heldX, oldHeldY = cfg.heldY;
    private final int oldHeldSize = cfg.heldSize;
    private Mode mode;
    private boolean moving, resizing, finished;
    private double startX, startY, originalX, originalY;
    private int originalSize;

    HudAdjustScreen(AtlasScreen parent, Mode initial) {
        super(Component.literal("地图位置与大小"));
        this.parent = parent;
        this.mode = initial;
    }

    @Override protected void init() {
        addRenderableWidget(new ActionButton("悬浮图", 8, 8, 48, 19,
                () -> switchMode(Mode.FLOAT), Mode.FLOAT, false));
        addRenderableWidget(new ActionButton("手持图", 60, 8, 48, 19,
                () -> switchMode(Mode.HELD), Mode.HELD, false));
        addRenderableWidget(new ActionButton("取消", 115, 8, 40, 19,
                () -> finish(false), null, false));
        addRenderableWidget(new ActionButton("完成", 159, 8, 40, 19,
                () -> finish(true), null, true));
    }

    private void switchMode(Mode next) {
        mode = next;
        moving = false;
        resizing = false;
    }

    private boolean held() { return mode == Mode.HELD; }

    // In held preview, leave the actual first-person book visible. Hiding it
    // here gave users a false impression of the in-game visual footprint.
    boolean previewingFloating() { return !held(); }

    private AtlasHud.Layout layout() {
        return held() ? AtlasHud.heldLayout(width, height) : AtlasHud.layout(width, height);
    }

    private void finish(boolean save) {
        if (finished) return;
        finished = true;
        if (!save) {
            cfg.hudX = oldX;
            cfg.hudY = oldY;
            cfg.hudSize = oldSize;
            cfg.heldX = oldHeldX;
            cfg.heldY = oldHeldY;
            cfg.heldSize = oldHeldSize;
        } else cfg.save();
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        // This matches the in-game displayed map mode, including antique
        // border, visibility overlays, zoom and physical-to-GUI scaling.
        if (held()) AtlasHud.drawHeld(g, true);
        else AtlasHud.draw(g, true);

        // Compact controls, no full-width tutorial banner and no giant
        // confirmation bar covering the view or the book.
        AtlasChrome.rounded(g, 6, 5, Math.min(width - 12, 197), 28, 4, AtlasChrome.PANEL);
        super.render(g, mx, my, partial);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float partial) { }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0) return true;
        AtlasHud.Layout a = layout();
        int rx = a.x() + a.size(), ry = a.y() + a.size();
        resizing = x >= rx - 10 && x < rx + 6 && y >= ry - 10 && y < ry + 6;
        moving = !resizing && a.inside(x, y);
        startX = x; startY = y;
        originalX = a.x(); originalY = a.y();
        originalSize = held() ? cfg.heldSize : cfg.hudSize;
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 0) return true;
        AtlasHud.Layout a = layout();
        if (resizing) {
            double delta = ((x - startX) + (y - startY)) * .5
                    * minecraft.getWindow().getGuiScale();
            int value = (int) Math.round(originalSize + delta);
            if (held()) cfg.heldSize = Math.clamp(value, 72, 240);
            else cfg.hudSize = Math.clamp(value, 80, 360);
            a = layout();
        }
        if (moving || resizing) {
            double left = originalX + (moving ? x - startX : 0);
            double top = originalY + (moving ? y - startY : 0);
            double px = a.travelX() == 0 ? 0 : Math.clamp(
                    (left - AtlasHud.margin()) / a.travelX(), 0, 1);
            double py = a.travelY() == 0 ? 0 : Math.clamp(
                    (top - AtlasHud.margin()) / a.travelY(), 0, 1);
            if (held()) {
                cfg.heldX = px;
                cfg.heldY = py;
            } else {
                cfg.hudX = px;
                cfg.hudY = py;
            }
        }
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        moving = false; resizing = false;
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            finish(true); return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            finish(false); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private final class ActionButton extends Button {
        private final Mode selectedMode;
        private final boolean primary;

        ActionButton(String label, int x, int y, int width, int height, Runnable action,
                     Mode selectedMode, boolean primary) {
            super(x, y, width, height, Component.literal(label),
                    b -> action.run(), DEFAULT_NARRATION);
            this.selectedMode = selectedMode;
            this.primary = primary;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            boolean activeMode = selectedMode != null && mode == selectedMode;
            int bg = primary || activeMode ? AtlasChrome.GOLD
                    : isHoveredOrFocused() ? AtlasChrome.HOVER : AtlasChrome.CONTROL;
            AtlasChrome.rounded(g, getX(), getY(), getWidth(), getHeight(), 3, bg);
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2,
                    getY() + 5, primary || activeMode ? AtlasChrome.INSET : AtlasChrome.CREAM);
        }
    }

    @Override public void onClose() { finish(false); }
    @Override public boolean isPauseScreen() { return false; }
}
