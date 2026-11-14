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
        MapClientCache.beginMapSession();
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

        String dim = dimension();
        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minChunkX = Math.floorDiv((int) Math.floor(centerX - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv((int) Math.floor(centerX + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + halfBlocksZ), 16) + 1;

        VisibleTiles tiles = VisibleTiles.load(
                dim, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        if (zoom < 1.0) {
            renderDownsampled(graphics, tiles);
        } else {
            renderBlocks(graphics, tiles);
        }
    }

    private void renderBlocks(GuiGraphics graphics, VisibleTiles tiles) {
        for (int chunkZ = tiles.minChunkZ(); chunkZ <= tiles.maxChunkZ(); chunkZ++) {
            for (int chunkX = tiles.minChunkX(); chunkX <= tiles.maxChunkX(); chunkX++) {
                SurfaceTile tile = tiles.tileAtChunk(chunkX, chunkZ);
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

    private void renderDownsampled(GuiGraphics graphics, VisibleTiles tiles) {
        double halfWidth = width * 0.5;
        double halfHeight = height * 0.5;
        double sampleOffset = 0.25 / zoom;

        for (int screenY = 0; screenY < height; screenY++) {
            double worldCenterZ = centerZ + (screenY + 0.5 - halfHeight) / zoom;
            double sampleZ0 = worldCenterZ - sampleOffset;
            double sampleZ1 = worldCenterZ + sampleOffset;

            int runStart = -1;
            int runColor = 0;
            for (int screenX = 0; screenX < width; screenX++) {
                double worldCenterX = centerX + (screenX + 0.5 - halfWidth) / zoom;
                double sampleX0 = worldCenterX - sampleOffset;
                double sampleX1 = worldCenterX + sampleOffset;

                int color = average(
                        tiles.colorAtWorld(sampleX0, sampleZ0),
                        tiles.colorAtWorld(sampleX1, sampleZ0),
                        tiles.colorAtWorld(sampleX0, sampleZ1),
                        tiles.colorAtWorld(sampleX1, sampleZ1));

                if ((color >>> 24) == 0) {
                    if (runStart >= 0) {
                        graphics.fill(runStart, screenY, screenX, screenY + 1, runColor);
                        runStart = -1;
                    }
                    continue;
                }

                if (runStart < 0) {
                    runStart = screenX;
                    runColor = color;
                } else if (color != runColor) {
                    graphics.fill(runStart, screenY, screenX, screenY + 1, runColor);
                    runStart = screenX;
                    runColor = color;
                }
            }
            if (runStart >= 0) {
                graphics.fill(runStart, screenY, width, screenY + 1, runColor);
            }
        }
    }

    private static int average(int c0, int c1, int c2, int c3) {
        int count = 0;
        int red = 0;
        int green = 0;
        int blue = 0;

        if ((c0 >>> 24) != 0) {
            red += (c0 >>> 16) & 255;
            green += (c0 >>> 8) & 255;
            blue += c0 & 255;
            count++;
        }
        if ((c1 >>> 24) != 0) {
            red += (c1 >>> 16) & 255;
            green += (c1 >>> 8) & 255;
            blue += c1 & 255;
            count++;
        }
        if ((c2 >>> 24) != 0) {
            red += (c2 >>> 16) & 255;
            green += (c2 >>> 8) & 255;
            blue += c2 & 255;
            count++;
        }
        if ((c3 >>> 24) != 0) {
            red += (c3 >>> 16) & 255;
            green += (c3 >>> 8) & 255;
            blue += c3 & 255;
            count++;
        }

        if (count == 0) return 0;
        red = (red + count / 2) / count;
        green = (green + count / 2) / count;
        blue = (blue + count / 2) / count;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private record VisibleTiles(int minChunkX, int minChunkZ,
                                int columns, int rows, SurfaceTile[] tiles) {
        static VisibleTiles load(String dimension,
                                 int minChunkX, int maxChunkX,
                                 int minChunkZ, int maxChunkZ) {
            int columns = maxChunkX - minChunkX + 1;
            int rows = maxChunkZ - minChunkZ + 1;
            SurfaceTile[] tiles = new SurfaceTile[columns * rows];
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    int index = (chunkZ - minChunkZ) * columns + (chunkX - minChunkX);
                    tiles[index] = MapClientCache.tile(
                            new ChunkKey(dimension, chunkX, chunkZ));
                }
            }
            return new VisibleTiles(minChunkX, minChunkZ, columns, rows, tiles);
        }

        int maxChunkX() {
            return minChunkX + columns - 1;
        }

        int maxChunkZ() {
            return minChunkZ + rows - 1;
        }

        SurfaceTile tileAtChunk(int chunkX, int chunkZ) {
            int x = chunkX - minChunkX;
            int z = chunkZ - minChunkZ;
            if (x < 0 || x >= columns || z < 0 || z >= rows) return null;
            return tiles[z * columns + x];
        }

        int colorAtWorld(double worldX, double worldZ) {
            int blockX = (int) Math.floor(worldX);
            int blockZ = (int) Math.floor(worldZ);
            int chunkX = Math.floorDiv(blockX, 16);
            int chunkZ = Math.floorDiv(blockZ, 16);
            SurfaceTile tile = tileAtChunk(chunkX, chunkZ);
            if (tile == null) return 0;
            return tile.colorAt(Math.floorMod(blockX, 16), Math.floorMod(blockZ, 16));
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
