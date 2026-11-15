package com.wanstu.cartographv3;

import net.ethrocky.pane.core.Component;
import net.ethrocky.pane.core.Focus;
import net.ethrocky.pane.core.Modals;
import net.ethrocky.pane.core.PaneKeys;
import net.ethrocky.pane.core.Rect;
import net.ethrocky.pane.core.Size;
import net.ethrocky.pane.core.UiEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * V3.1 screen host.
 *
 * This class intentionally contains no map rendering, marker rendering or
 * widget layout. It only bridges Minecraft Screen lifecycle/input to the active
 * CartographThemeFrontend.
 */
public final class MapScreenV31 extends Screen {
    private final CartographBackendApi backend;
    private final CartographThemeFrontend frontend;
    private final Component root;

    public MapScreenV31() {
        super(net.minecraft.network.chat.Component.literal("Cartograph V3.1"));
        backend = new MinecraftCartographBackend();
        frontend = new NativeCartographThemeFrontend(backend, this::onClose);
        root = frontend.root();
    }

    @Override
    protected void init() {
        backend.open();
        frontend.tick();
    }

    @Override
    public void tick() {
        backend.tick();
        frontend.tick();
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick) {
        MapPaneRenderer renderer = new MapPaneRenderer(
                graphics,
                font,
                MapUiSettings.uiScaleFactor(),
                MapUiSettings.fontScaleFactor());

        int logicalW = renderer.logicalWidth(width);
        int logicalH = renderer.logicalHeight(height);
        double logicalMouseX = renderer.logicalX(mouseX);
        double logicalMouseY = renderer.logicalY(mouseY);

        root.layout(new Rect(0, 0, logicalW, logicalH), renderer);
        root.onMouse(UiEvent.Mouse.move(logicalMouseX, logicalMouseY));

        renderer.begin();
        try {
            root.paint(renderer, frontend.paneTheme());

            Component modal = Modals.current();
            if (modal != null) {
                renderer.fill(
                        new Rect(0, 0, logicalW, logicalH),
                        0x88000000);
                Size size = modal.measure(renderer);
                int modalW = Math.min(
                        size.w(),
                        Math.max(120, logicalW - 24));
                int modalH = Math.min(
                        size.h(),
                        Math.max(80, logicalH - 24));
                modal.layout(
                        new Rect(
                                (logicalW - modalW) / 2,
                                (logicalH - modalH) / 2,
                                modalW,
                                modalH),
                        renderer);
                modal.onMouse(
                        UiEvent.Mouse.move(
                                logicalMouseX,
                                logicalMouseY));
                modal.paint(renderer, frontend.paneTheme());
            }
        } finally {
            renderer.end();
        }
    }

    @Override
    public void mouseMoved(double x, double y) {
        double scale = MapPaneRenderer.currentRenderScale();
        double lx = x / scale;
        double ly = y / scale;

        Component modal = Modals.current();
        if (modal != null) {
            modal.onMouse(UiEvent.Mouse.move(lx, ly));
        } else {
            root.onMouse(UiEvent.Mouse.move(lx, ly));
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        double scale = MapPaneRenderer.currentRenderScale();
        double lx = x / scale;
        double ly = y / scale;

        Component focus = Focus.current();
        if (focus != null && !focus.bounds().contains(lx, ly)) {
            Focus.clear();
        }

        Component modal = Modals.current();
        if (modal != null) {
            if (modal.bounds().contains(lx, ly)) {
                modal.onMouse(
                        UiEvent.Mouse.down(
                                lx, ly, button, modifiers()));
            }
            return true;
        }

        if (root.onMouse(
                UiEvent.Mouse.down(
                        lx, ly, button, modifiers()))) {
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        double scale = MapPaneRenderer.currentRenderScale();
        double lx = x / scale;
        double ly = y / scale;

        Component modal = Modals.current();
        if (modal != null) {
            modal.onMouse(
                    UiEvent.Mouse.up(
                            lx, ly, button, modifiers()));
            return true;
        }

        if (root.onMouse(
                UiEvent.Mouse.up(
                        lx, ly, button, modifiers()))) {
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(
            double x,
            double y,
            int button,
            double dragX,
            double dragY) {
        double scale = MapPaneRenderer.currentRenderScale();
        double lx = x / scale;
        double ly = y / scale;

        Component modal = Modals.current();
        if (modal != null) {
            if (modal.onMouse(UiEvent.Mouse.move(lx, ly))) {
                return true;
            }
            return true;
        }

        if (root.onMouse(UiEvent.Mouse.move(lx, ly))) {
            return true;
        }
        return super.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(
            double x,
            double y,
            double horizontal,
            double vertical) {
        double scale = MapPaneRenderer.currentRenderScale();
        double lx = x / scale;
        double ly = y / scale;

        Component modal = Modals.current();
        if (modal != null) {
            modal.onMouse(UiEvent.Mouse.scroll(lx, ly, vertical));
            return true;
        }

        if (root.onMouse(
                UiEvent.Mouse.scroll(lx, ly, vertical))) {
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(
            int keyCode,
            int scanCode,
            int modifiers) {
        Component modal = Modals.current();
        Component focus = Focus.current();

        if (focus != null) {
            boolean consumed = focus.onKey(
                    new UiEvent.Key(keyCode, modifiers));
            if (!consumed && keyCode == PaneKeys.TAB) {
                Focus.traverse(
                        focus.root(),
                        (modifiers & UiEvent.SHIFT) != 0);
            } else if (!consumed
                    && keyCode == PaneKeys.ESCAPE
                    && modal != null) {
                Modals.close();
            }
            return true;
        }

        if (modal != null) {
            if (keyCode == PaneKeys.ESCAPE) {
                Modals.close();
            } else if (keyCode == PaneKeys.TAB) {
                Focus.traverse(
                        modal,
                        (modifiers & UiEvent.SHIFT) != 0);
            } else {
                modal.onKey(
                        new UiEvent.Key(keyCode, modifiers));
            }
            return true;
        }

        if (CartographV3Client.matchesOpenKey(keyCode, scanCode)) {
            onClose();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        Component focus = Focus.current();
        if (focus != null) {
            focus.onChar(new UiEvent.Char(codePoint));
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        frontend.close();
        backend.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int modifiers() {
        int result = 0;
        if (hasShiftDown()) result |= UiEvent.SHIFT;
        if (hasControlDown()) result |= UiEvent.CTRL;
        if (hasAltDown()) result |= UiEvent.ALT;
        return result;
    }
}
