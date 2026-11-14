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

    private double[] downsampleX0 = new double[0];
    private double[] downsampleX1 = new double[0];
    private int[] downsampleFirstX = new int[0];
    private int[] downsampleLastX = new int[0];
    private int downsampleWidth = -1;
    private double downsampleCenterX = Double.NaN;
    private double downsampleZoom = Double.NaN;

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
                        int x0 = screenX(worldX0);
                        int y0 = screenY(worldZ0);
                        int x1 = screenX(worldX0 + 1);
                        int y1 = screenY(worldZ0 + 1);
                        if (x1 <= 0 || y1 <= 0 || x0 >= width || y0 >= height) continue;

                        graphics.fill(x0, y0, x1, y1, color);
                    }
                }
            }
        }
    }

    private int screenX(double worldX) {
        return (int) Math.floor(width * 0.5 + (worldX - centerX) * zoom);
    }

    private int screenY(double worldZ) {
        return (int) Math.floor(height * 0.5 + (worldZ - centerZ) * zoom);
    }

    private void renderDownsampled(GuiGraphics graphics, VisibleTiles tiles) {
        prepareDownsampleXCoverage();
        double halfHeight = height * 0.5;

        for (int screenY = 0; screenY < height; screenY++) {
            double worldZ0 = centerZ + (screenY - halfHeight) / zoom;
            double worldZ1 = centerZ + (screenY + 1.0 - halfHeight) / zoom;
            int firstZ = (int) Math.floor(worldZ0);
            int lastZ = Math.max(firstZ, (int) Math.floor(Math.nextDown(worldZ1)));

            int runStart = -1;
            int runColor = 0;
            for (int screenX = 0; screenX < width; screenX++) {
                int color = areaAverage(
                        tiles,
                        downsampleX0[screenX], downsampleX1[screenX],
                        downsampleFirstX[screenX], downsampleLastX[screenX],
                        worldZ0, worldZ1, firstZ, lastZ);

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

    private void prepareDownsampleXCoverage() {
        if (downsampleWidth != width) {
            downsampleX0 = new double[width];
            downsampleX1 = new double[width];
            downsampleFirstX = new int[width];
            downsampleLastX = new int[width];
            downsampleWidth = width;
            downsampleCenterX = Double.NaN;
            downsampleZoom = Double.NaN;
        }
        if (Double.compare(downsampleCenterX, centerX) == 0
                && Double.compare(downsampleZoom, zoom) == 0) {
            return;
        }

        double halfWidth = width * 0.5;
        for (int screenX = 0; screenX < width; screenX++) {
            double worldX0 = centerX + (screenX - halfWidth) / zoom;
            double worldX1 = centerX + (screenX + 1.0 - halfWidth) / zoom;
            downsampleX0[screenX] = worldX0;
            downsampleX1[screenX] = worldX1;
            int firstX = (int) Math.floor(worldX0);
            downsampleFirstX[screenX] = firstX;
            downsampleLastX[screenX] = Math.max(
                    firstX, (int) Math.floor(Math.nextDown(worldX1)));
        }
        downsampleCenterX = centerX;
        downsampleZoom = zoom;
    }

    private static int areaAverage(VisibleTiles tiles,
                                   double worldX0, double worldX1,
                                   int firstX, int lastX,
                                   double worldZ0, double worldZ1,
                                   int firstZ, int lastZ) {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        double knownArea = 0.0;

        for (int blockZ = firstZ; blockZ <= lastZ; blockZ++) {
            double zWeight = overlap(worldZ0, worldZ1, blockZ);
            if (zWeight <= 0.0) continue;

            for (int blockX = firstX; blockX <= lastX; blockX++) {
                double xWeight = overlap(worldX0, worldX1, blockX);
                if (xWeight <= 0.0) continue;

                double area = xWeight * zWeight;
                int color = tiles.colorAtBlock(blockX, blockZ);
                if ((color >>> 24) == 0) continue;

                red += ((color >>> 16) & 255) * area;
                green += ((color >>> 8) & 255) * area;
                blue += (color & 255) * area;
                knownArea += area;
            }
        }

        if (knownArea <= 0.0) return 0;

        double totalArea = (worldX1 - worldX0) * (worldZ1 - worldZ0);
        double missingArea = Math.max(0.0, totalArea - knownArea);

        // Unknown map data contributes the map background by exact covered area.
        // A one-block river/road therefore cannot disappear merely because four
        // fixed sample points happened to miss it.
        red += 0x11 * missingArea;
        green += 0x14 * missingArea;
        blue += 0x17 * missingArea;

        int r = clampColor((int) Math.round(red / totalArea));
        int g = clampColor((int) Math.round(green / totalArea));
        int b = clampColor((int) Math.round(blue / totalArea));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static double overlap(double start, double end, int block) {
        return Math.max(0.0, Math.min(end, block + 1.0) - Math.max(start, block));
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
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

        int colorAtBlock(int blockX, int blockZ) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            SurfaceTile tile = tileAtChunk(chunkX, chunkZ);
            if (tile == null) return 0;
            return tile.colorAt(blockX & 15, blockZ & 15);
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
