package com.wanstu.cartograph;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;

final class AtlasChrome {
    static final int PANEL = 0xf5203228, OUTLINE = 0xff7c8469, SHADOW = 0x4413221b;
    static final int GOLD = 0xffead4a1, CREAM = 0xfff2ead5, MUTED = 0xffb6c6b5;
    static final int CONTROL = 0xff364e40, HOVER = 0xff47614f, INSET = 0xff192b22;
    private AtlasChrome() {}

    static void rounded(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int dy = 0; dy < r; dy++) {
            double offset = r - (dy + .5);
            int inset = (int) Math.floor(r - Math.sqrt(r * r - offset * offset));
            g.fill(x + inset, y + dy, x + w - inset, y + dy + 1, color);
            g.fill(x + inset, y + h - dy - 1, x + w - inset, y + h - dy, color);
        }
    }

    static void card(GuiGraphics g, int x, int y, int w, int h, int radius) {
        rounded(g, x + 1, y + 3, w, h, radius, SHADOW);
        rounded(g, x, y, w, h, radius, OUTLINE);
        rounded(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), PANEL);
    }

    static void line(GuiGraphics g, double x, double y, double x1, double y1, int color, int weight) {
        double length = Math.hypot(x1 - x, y1 - y);
        if (length < .1) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(y1 - y, x1 - x)));
        g.fill(0, -weight / 2, (int) Math.ceil(length), (weight + 1) / 2, color);
        g.pose().popPose();
    }

    static void arrow(GuiGraphics g, double x, double y, double rotation, double scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) rotation));
        g.pose().scale((float) scale, (float) scale, 1);
        for (int row = 0; row <= 12; row++) {
            int half = (row * 5 + 2) / 11;
            g.fill(-half - 1, row - 8, half + 2, row - 6, 0xe023352c);
        }
        for (int row = 0; row <= 11; row++) {
            int half = (row * 5 + 2) / 11;
            int notch = Math.max(0, row - 8);
            int yy = row - 8;
            if (notch == 0) g.fill(-half, yy, half + 1, yy + 1, 0xfffff8e4);
            else {
                g.fill(-half, yy, -notch + 1, yy + 1, 0xfffff8e4);
                g.fill(notch, yy, half + 1, yy + 1, 0xfffff8e4);
            }
        }
        g.pose().popPose();
    }
}
