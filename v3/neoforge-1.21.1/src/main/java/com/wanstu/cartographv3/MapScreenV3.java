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
    private boolean initialized;
    private final MapRegionTextureCache regionTextures = new MapRegionTextureCache();

    private int lastRequestMinChunkX = Integer.MIN_VALUE;
    private int lastRequestMaxChunkX = Integer.MIN_VALUE;
    private int lastRequestMinChunkZ = Integer.MIN_VALUE;
    private int lastRequestMaxChunkZ = Integer.MIN_VALUE;
    private int requestTicks;

    public MapScreenV3() {
        super(Component.literal("Cartograph V3"));
    }

    @Override
    protected void init() {
        MapClientCache.ensureWorld(minecraft.level);
        if (!initialized) {
            MapClientCache.beginMapSession();
            if (minecraft.player != null) {
                // Initial viewport only. No player marker or player-position layer exists.
                centerX = minecraft.player.getX();
                centerZ = minecraft.player.getZ();
            }
            initialized = true;
            LOG.info("V3_MAP_OPEN dimension={} center={},{} zoom={}",
                    dimension(), Math.floor(centerX), Math.floor(centerZ), zoom);
        }
        requestVisible(true);
    }

    @Override
    public void tick() {
        MapClientCache.tick();
        requestTicks++;
        if (!dragging) {
            requestVisible(requestTicks % 20 == 0);
        }
    }

    private String dimension() {
        return minecraft.level == null ? "" :
                minecraft.level.dimension().location().toString();
    }

    private void requestVisible(boolean force) {
        if (minecraft.level == null) return;
        String dim = dimension();

        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minChunkX = Math.floorDiv((int) Math.floor(centerX - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv((int) Math.floor(centerX + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + halfBlocksZ), 16) + 1;

        if (!force
                && minChunkX == lastRequestMinChunkX
                && maxChunkX == lastRequestMaxChunkX
                && minChunkZ == lastRequestMinChunkZ
                && maxChunkZ == lastRequestMaxChunkZ) {
            return;
        }
        lastRequestMinChunkX = minChunkX;
        lastRequestMaxChunkX = maxChunkX;
        lastRequestMinChunkZ = minChunkZ;
        lastRequestMaxChunkZ = maxChunkZ;

        List<ChunkKey> visible = new ArrayList<>();
        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            for (int x = minChunkX; x <= maxChunkX; x++) {
                ChunkKey key = new ChunkKey(dim, x, z);
                if (MapClientCache.needsRequest(key)) visible.add(key);
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
        regionTextures.render(
                graphics, dimension(), centerX, centerZ, zoom, width, height);
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
            requestVisible(false);
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
        requestVisible(false);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CartographV3Client.matchesOpenKey(keyCode, scanCode)
                || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        regionTextures.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
