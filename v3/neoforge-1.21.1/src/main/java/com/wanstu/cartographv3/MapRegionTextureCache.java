package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

import java.util.HashMap;
import java.util.Map;

final class MapRegionTextureCache implements AutoCloseable {
    private static final int REGION_CHUNKS = 16;
    private static final int REGION_BLOCKS = REGION_CHUNKS * SurfaceTile.SIZE;
    private static final int BACKGROUND = 0xff111417;

    private record RegionKey(String dimension, int x, int z) {}

    private static final class RegionTexture {
        final ChunkKey[] keys = new ChunkKey[REGION_CHUNKS * REGION_CHUNKS];
        final SurfaceTile[] tiles = new SurfaceTile[keys.length];
        final DynamicTexture texture;
        final ResourceLocation location;
        boolean blur;
        long lastRevision = Long.MIN_VALUE;

        RegionTexture(String dimension, int regionX, int regionZ) {
            for (int localZ = 0; localZ < REGION_CHUNKS; localZ++) {
                for (int localX = 0; localX < REGION_CHUNKS; localX++) {
                    int index = localZ * REGION_CHUNKS + localX;
                    keys[index] = new ChunkKey(
                            dimension,
                            regionX * REGION_CHUNKS + localX,
                            regionZ * REGION_CHUNKS + localZ);
                }
            }

            texture = new DynamicTexture(REGION_BLOCKS, REGION_BLOCKS, true);
            NativeImage image = texture.getPixels();
            int background = FastColor.ABGR32.fromArgb32(BACKGROUND);
            for (int z = 0; z < REGION_BLOCKS; z++) {
                for (int x = 0; x < REGION_BLOCKS; x++) {
                    image.setPixelRGBA(x, z, background);
                }
            }
            texture.upload();
            location = Minecraft.getInstance().getTextureManager()
                    .register("cartograph_v3_region", texture);
        }

        void sync(long revision) {
            if (lastRevision == revision) return;
            lastRevision = revision;

            NativeImage image = texture.getPixels();
            boolean dirty = false;

            for (int index = 0; index < keys.length; index++) {
                SurfaceTile next = MapClientCache.tile(keys[index]);
                if (next == tiles[index]) continue;
                tiles[index] = next;
                writeTile(image, index, next);
                dirty = true;
            }

            if (dirty) texture.upload();
        }

        void setBlur(boolean nextBlur) {
            if (blur == nextBlur) return;
            blur = nextBlur;
            texture.setFilter(nextBlur, false);
        }

        private static void writeTile(NativeImage image, int index, SurfaceTile tile) {
            int localChunkX = index % REGION_CHUNKS;
            int localChunkZ = index / REGION_CHUNKS;
            int startX = localChunkX * SurfaceTile.SIZE;
            int startZ = localChunkZ * SurfaceTile.SIZE;
            int background = FastColor.ABGR32.fromArgb32(BACKGROUND);

            for (int localZ = 0; localZ < SurfaceTile.SIZE; localZ++) {
                for (int localX = 0; localX < SurfaceTile.SIZE; localX++) {
                    int argb = tile == null ? BACKGROUND : tile.colorAt(localX, localZ);
                    if ((argb >>> 24) == 0) argb = BACKGROUND;
                    image.setPixelRGBA(
                            startX + localX,
                            startZ + localZ,
                            tile == null ? background : FastColor.ABGR32.fromArgb32(argb));
                }
            }
        }

        void close() {
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }

    private final Map<RegionKey, RegionTexture> regions = new HashMap<>();

    void render(GuiGraphics graphics, String dimension,
                double centerX, double centerZ, double zoom,
                int screenWidth, int screenHeight) {
        double halfBlocksX = screenWidth / (2.0 * zoom);
        double halfBlocksZ = screenHeight / (2.0 * zoom);
        int minRegionX = Math.floorDiv(
                (int) Math.floor(centerX - halfBlocksX), REGION_BLOCKS);
        int maxRegionX = Math.floorDiv(
                (int) Math.floor(centerX + halfBlocksX), REGION_BLOCKS);
        int minRegionZ = Math.floorDiv(
                (int) Math.floor(centerZ - halfBlocksZ), REGION_BLOCKS);
        int maxRegionZ = Math.floorDiv(
                (int) Math.floor(centerZ + halfBlocksZ), REGION_BLOCKS);
        boolean blur = zoom < 1.0;

        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                RegionKey key = new RegionKey(dimension, regionX, regionZ);
                RegionTexture region = regions.get(key);
                if (region == null) {
                    region = new RegionTexture(dimension, regionX, regionZ);
                    regions.put(key, region);
                }
                region.sync(MapClientCache.tileRevision());
                region.setBlur(blur);

                double worldX0 = regionX * (double) REGION_BLOCKS;
                double worldZ0 = regionZ * (double) REGION_BLOCKS;
                int x0 = (int) Math.floor(screenWidth * 0.5 + (worldX0 - centerX) * zoom);
                int y0 = (int) Math.floor(screenHeight * 0.5 + (worldZ0 - centerZ) * zoom);
                int x1 = (int) Math.floor(screenWidth * 0.5
                        + (worldX0 + REGION_BLOCKS - centerX) * zoom);
                int y1 = (int) Math.floor(screenHeight * 0.5
                        + (worldZ0 + REGION_BLOCKS - centerZ) * zoom);

                if (x1 <= 0 || y1 <= 0 || x0 >= screenWidth || y0 >= screenHeight) continue;
                graphics.blit(region.location, x0, y0, x1 - x0, y1 - y0,
                        0.0f, 0.0f,
                        REGION_BLOCKS, REGION_BLOCKS,
                        REGION_BLOCKS, REGION_BLOCKS);
            }
        }
    }

    @Override
    public void close() {
        for (RegionTexture region : regions.values()) {
            region.close();
        }
        regions.clear();
    }
}
