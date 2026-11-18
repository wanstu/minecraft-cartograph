package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
    public final void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        drawFrame(new MapGraphics(graphics), delta);
    }

    @Override
    public final void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        // The map canvas draws the backdrop beneath the widgets.
    }

    final void drawWidgets(MapGraphics graphics, int x, int y, float delta) {
        super.extractRenderState(graphics.nativeGraphics, x, y, delta);
    }

    @Override
    public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return mapClick(event.x(), event.y(), mapButton(event), (x, y) ->
                super.mouseClicked(new MouseButtonEvent(x, y, event.buttonInfo()), doubleClick));
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return mapDrag(event.x(), event.y(), mapButton(event), dx, dy, (x, y, sx, sy) ->
                super.mouseDragged(new MouseButtonEvent(x, y, event.buttonInfo()), sx, sy));
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent event) {
        return mapRelease(event.x(), event.y(), mapButton(event), (x, y) ->
                super.mouseReleased(new MouseButtonEvent(x, y, event.buttonInfo())));
    }

    private static int mapButton(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) return 0;
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) return 1;
        return -1;
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return mapScroll(x, y, vertical);
    }
}
