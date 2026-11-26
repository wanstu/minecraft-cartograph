package com.wanstu.cartographv3;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class AtlasScreenBase extends Screen {
    @FunctionalInterface interface ChildClick { boolean handle(double x, double y); }
    @FunctionalInterface interface ChildDrag { boolean handle(double x, double y, double dx, double dy); }

    AtlasScreenBase(Component title) { super(title); }

    abstract void drawFrame(MapGraphics graphics, float delta);
    abstract boolean mapClick(double x, double y, int button, ChildClick child);
    abstract boolean mapDrag(double x, double y, int button, double dx, double dy, ChildDrag child);
    abstract boolean mapRelease(double x, double y, int button, ChildClick child);
    abstract boolean mapScroll(double x, double y, double scrollY);

    @Override
    public final void render(GuiGraphics graphics, int x, int y, float delta) {
        MapGraphics frame = new MapGraphics(graphics);
        try {
            drawFrame(frame, delta);
        } finally {
            frame.flushPending();
        }
    }

    @Override
    public final void renderBackground(GuiGraphics graphics, int x, int y, float delta) {
        // The map canvas draws the backdrop beneath the widgets.
    }

    final void drawWidgets(MapGraphics graphics, int x, int y, float delta) {
        graphics.flushPending();
        super.render(graphics.nativeGraphics, x, y, delta);
    }

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        return mapClick(x, y, button, (sx, sy) -> super.mouseClicked(sx, sy, button));
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return mapDrag(x, y, button, dx, dy,
                (sx, sy, sdx, sdy) -> super.mouseDragged(sx, sy, button, sdx, sdy));
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return mapRelease(x, y, button, (sx, sy) -> super.mouseReleased(sx, sy, button));
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return mapScroll(x, y, vertical);
    }
}
