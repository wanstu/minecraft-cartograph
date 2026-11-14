package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MapScreenV3 extends Screen {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.screen");
    private double centerX;
    private double centerZ;
    private double zoom = 2.0;
    private boolean dragging;

    public MapScreenV3() {
        super(Component.literal("Cartograph V3"));
    }

    @Override
    protected void init() {
        MapClientCache.ensureWorld(minecraft.level);
        if (minecraft.player != null) {
            // Initial viewport only. No player marker or player-position layer exists.
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        }
        LOG.info("V3_MAP_OPEN dimension={} center={},{} zoom={}",
                dimension(), Math.floor(centerX), Math.floor(centerZ), zoom);
        requestVisible();
    }

    @Override
    public void tick() {
        MapClientCache.tick();
        requestVisible();
    }

    private String dimension() {
        return minecraft.level == null ? "" :
                minecraft.level.dimension().location().toString();
    }

    private void requestVisible() {
        if (minecraft.level == null) return;
        String dim = dimension();

        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minChunkX = Math.floorDiv((int) Math.floor(centerX - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv((int) Math.floor(centerX + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + halfBlocksZ), 16) + 1;

        List<ChunkKey> visible = new ArrayList<>();
        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            for (int x = minChunkX; x <= maxChunkX; x++) {
                ChunkKey key = new ChunkKey(dim, x, z);
                if (!MapClientCache.hasOrPending(key)) visible.add(key);
            }
        }

        int centerChunkX = Math.floorDiv((int) Math.floor(centerX), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(centerZ), 16);
        visible.sort(Comparator.comparingInt(key ->
                Math.abs(key.x() - centerChunkX) + Math.abs(key.z() - centerChunkZ)));

        // Request every visible chunk independently. There is no page queue.
        for (ChunkKey key : visible) MapClientCache.request(key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xff111417);
        if (minecraft.level == null) return;

        String dim = dimension();
        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minChunkX = Math.floorDiv((int) Math.floor(centerX - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv((int) Math.floor(centerX + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + halfBlocksZ), 16) + 1;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                SurfaceTile tile = MapClientCache.tile(new ChunkKey(dim, chunkX, chunkZ));
                if (tile == null) continue;

                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        int color = tile.colorAt(localX, localZ);
                        if ((color >>> 24) == 0) continue;

                        double worldX0 = chunkX * 16.0 + localX;
                        double worldZ0 = chunkZ * 16.0 + localZ;
                        int x0 = (int) Math.floor(width * 0.5 + (worldX0 - centerX) * zoom);
                        int y0 = (int) Math.floor(height * 0.5 + (worldZ0 - centerZ) * zoom);
                        int x1 = (int) Math.ceil(width * 0.5 + (worldX0 + 1 - centerX) * zoom);
                        int y1 = (int) Math.ceil(height * 0.5 + (worldZ0 + 1 - centerZ) * zoom);
                        if (x1 <= 0 || y1 <= 0 || x0 >= width || y0 >= height) continue;

                        graphics.fill(x0, y0, x1, y1, color);
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            centerX -= dragX / zoom;
            centerZ -= dragY / zoom;
            if (Boolean.getBoolean("cartograph.v3.qaInput"))
                LOG.info("V3_QA_DRAG center={},{} delta={},{} zoom={}",
                        centerX, centerZ, dragX, dragY, zoom);
            requestVisible();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        if (scrollY == 0) return true;

        double worldAtMouseX = centerX + (mouseX - width * 0.5) / zoom;
        double worldAtMouseZ = centerZ + (mouseY - height * 0.5) / zoom;
        double next = Math.max(0.5, Math.min(8.0,
                zoom * (scrollY > 0 ? 1.35 : 1.0 / 1.35)));
        centerX = worldAtMouseX - (mouseX - width * 0.5) / next;
        centerZ = worldAtMouseZ - (mouseY - height * 0.5) / next;
        zoom = next;
        if (Boolean.getBoolean("cartograph.v3.qaInput"))
            LOG.info("V3_QA_SCROLL center={},{} zoom={}", centerX, centerZ, zoom);
        requestVisible();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_M || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
