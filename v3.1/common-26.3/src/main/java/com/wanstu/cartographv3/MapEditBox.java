package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import com.wanstu.cartographv3.InGameMapUi.Box;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

final class MapEditBox extends EditBox {
    private final float scale;

    MapEditBox(Font font, Box box, float scale, Component narration) {
        super(font, box.x() + 4, box.y() + Math.round((box.h() - font.lineHeight * scale) / 2),
                Math.max(1, (int) ((box.w() - 8) / scale)),
                Math.max(1, (int) ((box.h() - 4) / scale)), narration);
        this.scale = scale;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(new MouseButtonEvent(getX() + (event.x() - getX()) / scale,
                getY() + (event.y() - getY()) / scale, event.buttonInfo()), doubleClick);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (scale == 1) {
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        try {
            pose.translate(getX(), getY());
            pose.scale(scale, scale);
            pose.translate(-getX(), -getY());
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
        } finally {
            pose.popMatrix();
        }
    }
}
