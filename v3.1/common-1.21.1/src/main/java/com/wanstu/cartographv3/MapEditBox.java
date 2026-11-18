package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import com.wanstu.cartographv3.InGameMapUi.Box;

import net.minecraft.client.gui.GuiGraphics;

final class MapEditBox extends EditBox {
    private final float scale;

    MapEditBox(Font font, Box box, float scale, Component narration) {
        super(font, box.x() + 4, box.y() + Math.round((box.h() - font.lineHeight * scale) / 2),
                Math.max(1, (int) ((box.w() - 8) / scale)),
                Math.max(1, (int) ((box.h() - 4) / scale)), narration);
        this.scale = scale;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return super.mouseClicked(getX() + (x - getX()) / scale,
                getY() + (y - getY()) / scale, button);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (scale == 1) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            return;
        }
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(getX(), getY(), 0);
            pose.scale(scale, scale, 1);
            pose.translate(-getX(), -getY(), 0);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
        } finally {
            pose.popPose();
        }
    }
}
