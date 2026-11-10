package com.wanstu.cartograph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class HudAdjustScreen extends Screen {
    private final AtlasScreen parent;
    private final ClientMapSettings cfg = ClientMapSettings.get();
    private final double oldX = cfg.hudX, oldY = cfg.hudY;
    private final int oldSize = cfg.hudSize;
    private boolean moving, resizing;
    private double startX, startY, originalX, originalY;
    private int originalSize;

    HudAdjustScreen(AtlasScreen parent) {
        super(Component.literal("调整小地图位置与大小"));
        this.parent = parent;
    }

    @Override protected void init() {
        addRenderableWidget(new AdjustButton("取消", 10, false));
        addRenderableWidget(new AdjustButton("完成", 80, true));
    }

    private void finish(boolean save) {
        if (save) cfg.save();
        else { cfg.hudX = oldX; cfg.hudY = oldY; cfg.hudSize = oldSize; }
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        // Draw EXACTLY the same float HUD as when the atlas is put away.
        // No giant top/bottom dialogs, and no first-person book underneath.
        AtlasHud.draw(g, true);
        AtlasChrome.rounded(g, 8, 8, Math.min(246, width - 16), 27, 4, AtlasChrome.PANEL);
        g.drawString(font, "拖动地图移动 · 拖动右下角改变大小", 15, 18, AtlasChrome.CREAM, false);
        super.render(g, mx, my, partial);
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float partial) { }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) return true;
        if (button != 0) return true;
        AtlasHud.Layout a = AtlasHud.layout(width, height);
        int rx = a.x() + a.size(), ry = a.y() + a.size();
        resizing = x >= rx - 10 && x < rx + 6 && y >= ry - 10 && y < ry + 6;
        moving = !resizing && a.inside(x, y);
        startX = x; startY = y; originalX = a.x(); originalY = a.y(); originalSize = cfg.hudSize;
        return true;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 0) return true;
        AtlasHud.Layout a = AtlasHud.layout(width, height);
        if (resizing) {
            double delta = ((x - startX) + (y - startY)) * .5 * minecraft.getWindow().getGuiScale();
            cfg.hudSize = (int) Math.clamp(Math.round(originalSize + delta), 80, 360);
            a = AtlasHud.layout(width, height);
        }
        if (moving || resizing) {
            double left = originalX + (moving ? x - startX : 0), top = originalY + (moving ? y - startY : 0);
            cfg.hudX = a.travelX() == 0 ? 0 : Math.clamp((left - AtlasHud.margin()) / a.travelX(), 0, 1);
            cfg.hudY = a.travelY() == 0 ? 0 : Math.clamp((top - AtlasHud.margin()) / a.travelY(), 0, 1);
        }
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        moving = false; resizing = false;
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            finish(true);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private final class AdjustButton extends Button {
        private final boolean primary;

        AdjustButton(String label, int x, boolean primary) {
            super(x, HudAdjustScreen.this.height - 28, 64, 19, Component.literal(label),
                    b -> finish(primary), DEFAULT_NARRATION);
            this.primary = primary;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            int background = primary ? AtlasChrome.GOLD : isHoveredOrFocused() ? AtlasChrome.HOVER : AtlasChrome.CONTROL;
            AtlasChrome.rounded(g, getX(), getY(), getWidth(), getHeight(), 4, background);
            g.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + 6,
                    primary ? AtlasChrome.INSET : AtlasChrome.CREAM);
        }
    }

    @Override public void onClose() { finish(false); }
    @Override public boolean isPauseScreen() { return false; }
}
