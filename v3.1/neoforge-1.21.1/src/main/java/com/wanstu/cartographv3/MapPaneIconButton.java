package com.wanstu.cartographv3;

import net.ethrocky.pane.core.Component;
import net.ethrocky.pane.core.Rect;
import net.ethrocky.pane.core.Size;
import net.ethrocky.pane.core.UiEvent;
import net.ethrocky.pane.core.style.Theme;
import net.ethrocky.pane.render.PaneRenderer;

import java.util.function.BooleanSupplier;

/**
 * High-fidelity top-bar button built as a Pane component.
 * Pane still owns layout, hover and input; Cartograph supplies its own icon art.
 */
final class MapPaneIconButton extends Component {
    private final MapIconAtlas.UiIcon icon;
    private final String label;
    private final Runnable action;
    private final BooleanSupplier active;
    private final int preferredWidth;
    private final int preferredHeight;
    private boolean pressed;

    MapPaneIconButton(
            MapIconAtlas.UiIcon icon,
            String label,
            int preferredWidth,
            int preferredHeight,
            Runnable action,
            BooleanSupplier active) {
        this.icon = icon;
        this.label = label == null ? "" : label;
        this.preferredWidth = preferredWidth;
        this.preferredHeight = preferredHeight;
        this.action = action == null ? () -> {} : action;
        this.active = active == null ? () -> false : active;
    }

    @Override
    public Size measure(TextMeasurer tm) {
        int textW = label.isBlank() ? 0 : tm.width(label) + 16;
        return new Size(
                Math.max(preferredWidth, textW),
                Math.max(preferredHeight, tm.height() + 26));
    }

    @Override
    protected boolean handleMouse(UiEvent.Mouse e) {
        switch (e.kind()) {
            case DOWN -> {
                if (e.button() == 0 && e.inside(bounds)) {
                    pressed = true;
                    return true;
                }
            }
            case UP -> {
                if (pressed) {
                    pressed = false;
                    if (e.button() == 0 && e.inside(bounds)) action.run();
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }

    @Override
    protected void paintSelf(PaneRenderer renderer, Theme theme) {
        boolean selected = active.getAsBoolean();
        if (pressed) {
            renderer.fill(bounds, 0xff24323a);
        } else if (hovered || selected) {
            renderer.fill(bounds, selected ? 0x1fffffff : 0x12ffffff);
        }

        if (renderer instanceof MapPaneRenderer r) {
            int iconY = label.isBlank()
                    ? bounds.y() + bounds.h() / 2
                    : bounds.y() + Math.max(15, bounds.h() / 2 - 7);
            r.drawUiIcon(
                    icon,
                    bounds.x() + bounds.w() / 2,
                    iconY,
                    label.isBlank() ? 20 : 22,
                    selected ? 0xffffffff : 0xffd8e0e4);
        }

        if (!label.isBlank()) {
            int tx = bounds.x() + (bounds.w() - renderer.width(label)) / 2;
            int ty = bounds.bottom() - renderer.height() - 6;
            renderer.text(label, tx, ty, selected ? 0xffffffff : 0xffcbd4d9);
        }

        if (selected) {
            renderer.fill(
                    new Rect(bounds.x(), bounds.bottom() - 3, bounds.w(), 3),
                    theme.accent());
        }
    }
}
