package com.wanstu.cartograph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** First playable full-screen map: real paid surface pixels and safe page browsing. */
public final class AtlasScreen extends Screen {
    private int pageX, pageZ;
    private int size = 288;
    private int retryTicks;
    private boolean first;

    public AtlasScreen() { super(Component.translatable("screen.cartograph.atlas")); }

    @Override
    protected void init() {
        super.init();
        if (!first && minecraft != null && minecraft.player != null) {
            pageX = Math.floorDiv(minecraft.player.getBlockX(), 128);
            pageZ = Math.floorDiv(minecraft.player.getBlockZ(), 128);
            first = true;
        }
        refresh();
    }

    private void refresh() {
        retryTicks = 0;
        CartographClient.requestPage(pageX, pageZ);
    }

    @Override
    public void tick() {
        if (++retryTicks >= 200) refresh(); // 10s surface refresh, no new fee on paid chunks.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int centerX = width / 2, centerY = height / 2;
        int max = Math.min(width - 64, height - 100);
        int shown = Math.min(size, Math.max(96, max));
        int left = centerX - shown / 2, top = centerY - shown / 2;
        g.fill(0, 0, width, height, 0xf0182522);
        g.fill(left - 10, top - 10, left + shown + 10, top + shown + 10, 0xffad986e);
        g.fill(left - 5, top - 5, left + shown + 5, top + shown + 5, 0xffe8d9b9);
        boolean map = CartographClient.hasTextureFor(pageX, pageZ);
        MapNetwork.Page current = CartographClient.lastPage();
        if (map && current != null && current.inRange()) {
            float scale = shown / 128.0f;
            g.pose().pushPose();
            g.pose().translate(left, top, 0);
            g.pose().scale(scale, scale, 1.0f);
            g.blit(CartographClient.textureId(), 0, 0, 0, 0, 128, 128, 128, 128);
            g.pose().popPose();
            CartographClient.drawPlayer(g, left, top, shown, pageX, pageZ);
        } else {
            g.fill(left, top, left + shown, top + shown, 0xffe3d5b4);
            g.drawCenteredString(font, current != null && map && !current.inRange()
                    ? "尚未添加此地图页" : "正在读取已保存的区块…", centerX, centerY, 0xff564832);
        }
        g.drawCenteredString(font, title, centerX, top - 30, 0xffe9d9b6);
        g.drawCenteredString(font, "地图页 (" + pageX + ", " + pageZ + ") · 128 × 128 方块",
                centerX, top + shown + 18, 0xffd4c29e);
        g.drawCenteredString(font, "方向键 切换页面   ·   滚轮 缩放   ·   Esc 关闭",
                centerX, top + shown + 34, 0xffb9c9b9);
        if (current != null && map)
            g.drawCenteredString(font, String.format("墨水 %.1f   颜料 %.1f   发光墨水 %.1f",
                    current.inkUnits()/10.0, current.pigmentUnits()/10.0, current.glowUnits()/10.0),
                    centerX, top + shown + 50, 0xffcbba91);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case 263 -> pageX--; // left arrow
            case 262 -> pageX++; // right
            case 265 -> pageZ--; // up
            case 264 -> pageZ++; // down
            default -> { return super.keyPressed(key, scan, mods); }
        }
        refresh();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double deltaX, double deltaY) {
        size = Math.max(128, Math.min(640, size + (int) Math.signum(deltaY) * 32));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
