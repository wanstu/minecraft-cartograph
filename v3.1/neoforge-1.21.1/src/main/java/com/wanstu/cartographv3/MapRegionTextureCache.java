package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Frontend-only derived texture cache.
 *
 * The authoritative backend supplies SurfaceTile facts. The active theme's
 * MapStyleRenderer turns facts into disposable pixels here.
 */
final class MapRegionTextureCache implements AutoCloseable {
    private static final int REGION_CHUNKS = 16;
    private static final int REGION_BLOCKS = REGION_CHUNKS * SurfaceTile.SIZE;
    private static final int BACKGROUND = 0xff111417;
    private static final int MIN_RETAINED_REGIONS = 192;
    private static final int OFFSCREEN_SPARE_REGIONS = 48;

    private record RegionKey(String dimension, int x, int z) {}

    private final CartographBackendApi backend;
    private MapStyleRenderer style;

    private static final class RegionTexture {
        final ChunkKey[] keys = new ChunkKey[REGION_CHUNKS * REGION_CHUNKS];
        final SurfaceTile[] tiles = new SurfaceTile[keys.length];
        final long[] fingerprints = new long[keys.length];
        final DynamicTexture texture;
        final ResourceLocation location;
        boolean blur;
        long lastRevision = Long.MIN_VALUE;
        long lastStyleSignature = Long.MIN_VALUE;

        RegionTexture(String dimension, int regionX, int regionZ) {
            for (int localZ = 0; localZ < REGION_CHUNKS; localZ++) {
                for (int localX = 0; localX < REGION_CHUNKS; localX++) {
                    int index = localZ * REGION_CHUNKS + localX;
                    keys[index] = new ChunkKey(
                            dimension,
                            regionX * REGION_CHUNKS + localX,
                            regionZ * REGION_CHUNKS + localZ);
                    fingerprints[index] = Long.MIN_VALUE;
                }
            }

            texture = new DynamicTexture(REGION_BLOCKS, REGION_BLOCKS, true);
            clearImage(texture.getPixels());
            location = Minecraft.getInstance().getTextureManager()
                    .register("cartograph_v31_theme_region", texture);
        }

        void sync(
                CartographBackendApi backend,
                MapStyleRenderer style,
                long revision) {
            long styleSignature = style.signature();
            boolean styleChanged = lastStyleSignature != styleSignature;
            if (!styleChanged && lastRevision == revision) return;

            lastRevision = revision;
            lastStyleSignature = styleSignature;

            NativeImage image = texture.getPixels();
            boolean dirty = false;

            for (int index = 0; index < keys.length; index++) {
                SurfaceTile next = backend.tile(keys[index]);
                long fingerprint = next == null
                        ? Long.MIN_VALUE
                        : next.fingerprint64();

                if (!styleChanged
                        && next == tiles[index]
                        && fingerprints[index] == fingerprint) {
                    continue;
                }

                tiles[index] = next;
                fingerprints[index] = fingerprint;
                writeTile(image, index, next, style);
                dirty = true;
            }

            if (dirty) texture.upload();
        }

        void setBlur(boolean nextBlur) {
            if (blur == nextBlur) return;
            blur = nextBlur;
            texture.setFilter(nextBlur, false);
        }

        private static void clearImage(NativeImage image) {
            int background = FastColor.ABGR32.fromArgb32(BACKGROUND);
            for (int z = 0; z < REGION_BLOCKS; z++) {
                for (int x = 0; x < REGION_BLOCKS; x++) {
                    image.setPixelRGBA(x, z, background);
                }
            }
        }

        private static void writeTile(
                NativeImage image, int index,
                SurfaceTile tile,
                MapStyleRenderer style) {
            int localChunkX = index % REGION_CHUNKS;
            int localChunkZ = index / REGION_CHUNKS;
            int startX = localChunkX * SurfaceTile.SIZE;
            int startZ = localChunkZ * SurfaceTile.SIZE;
            int background = FastColor.ABGR32.fromArgb32(BACKGROUND);

            int[] pixels = tile == null
                    ? null
                    : style.renderTile(tile);

            for (int localZ = 0; localZ < SurfaceTile.SIZE; localZ++) {
                for (int localX = 0; localX < SurfaceTile.SIZE; localX++) {
                    int pixelIndex = localZ * SurfaceTile.SIZE + localX;
                    int rgb = pixels == null || pixelIndex >= pixels.length
                            ? 0
                            : pixels[pixelIndex];
                    int argb = rgb == 0
                            ? BACKGROUND
                            : (0xff000000 | (rgb & 0x00ffffff));
                    image.setPixelRGBA(
                            startX + localX,
                            startZ + localZ,
                            rgb == 0
                                    ? background
                                    : FastColor.ABGR32.fromArgb32(argb));
                }
            }
        }

        void close() {
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }

    private final LinkedHashMap<RegionKey, RegionTexture> regions =
            new LinkedHashMap<>(64, 0.75f, true);

    MapRegionTextureCache(
            CartographBackendApi backend,
            MapStyleRenderer style) {
        this.backend = backend;
        this.style = style;
    }

    void setStyle(MapStyleRenderer style) {
        if (style == null || this.style == style) return;
        this.style = style;
        for (RegionTexture region : regions.values()) {
            region.lastStyleSignature = Long.MIN_VALUE;
        }
    }

    void render(
            GuiGraphics graphics,
            String dimension,
            double centerX, double centerZ, double zoom,
            int x, int y, int width, int height) {
        if (width <= 0 || height <= 0 || zoom <= 0.0) return;

        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minRegionX = Math.floorDiv(
                (int) Math.floor(centerX - halfBlocksX), REGION_BLOCKS);
        int maxRegionX = Math.floorDiv(
                (int) Math.floor(centerX + halfBlocksX), REGION_BLOCKS);
        int minRegionZ = Math.floorDiv(
                (int) Math.floor(centerZ - halfBlocksZ), REGION_BLOCKS);
        int maxRegionZ = Math.floorDiv(
                (int) Math.floor(centerZ + halfBlocksZ), REGION_BLOCKS);

        boolean blur = zoom < 1.0;
        Set<RegionKey> usedThisFrame = new HashSet<>();

        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                    RegionKey key = new RegionKey(dimension, regionX, regionZ);
                    boolean hasTiles =
                            backend.hasTilesInRegion(dimension, regionX, regionZ);
                    RegionTexture region = regions.get(key);

                    if (!hasTiles) {
                        if (region != null) {
                            regions.remove(key);
                            region.close();
                        }
                        continue;
                    }

                    if (region == null) {
                        region = new RegionTexture(dimension, regionX, regionZ);
                        regions.put(key, region);
                    }

                    usedThisFrame.add(key);
                    region.sync(
                            backend,
                            style,
                            backend.regionRevision(dimension, regionX, regionZ));
                    region.setBlur(blur);

                    double worldX0 = regionX * (double) REGION_BLOCKS;
                    double worldZ0 = regionZ * (double) REGION_BLOCKS;
                    int x0 = x + (int) Math.floor(
                            width * 0.5 + (worldX0 - centerX) * zoom);
                    int y0 = y + (int) Math.floor(
                            height * 0.5 + (worldZ0 - centerZ) * zoom);
                    int x1 = x + (int) Math.floor(
                            width * 0.5
                                    + (worldX0 + REGION_BLOCKS - centerX) * zoom);
                    int y1 = y + (int) Math.floor(
                            height * 0.5
                                    + (worldZ0 + REGION_BLOCKS - centerZ) * zoom);

                    if (x1 <= x || y1 <= y
                            || x0 >= x + width || y0 >= y + height) {
                        continue;
                    }

                    graphics.blit(
                            region.location,
                            x0, y0,
                            x1 - x0, y1 - y0,
                            0.0f, 0.0f,
                            REGION_BLOCKS, REGION_BLOCKS,
                            REGION_BLOCKS, REGION_BLOCKS);
            }
        }

        pruneOffscreen(usedThisFrame);
    }

    private void pruneOffscreen(Set<RegionKey> usedThisFrame) {
        int target = Math.max(
                MIN_RETAINED_REGIONS,
                usedThisFrame.size() + OFFSCREEN_SPARE_REGIONS);
        if (regions.size() <= target) return;

        Iterator<Map.Entry<RegionKey, RegionTexture>> iterator =
                regions.entrySet().iterator();
        while (regions.size() > target && iterator.hasNext()) {
            Map.Entry<RegionKey, RegionTexture> entry = iterator.next();
            if (usedThisFrame.contains(entry.getKey())) continue;
            entry.getValue().close();
            iterator.remove();
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
