package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Draws a bounded set of client GPU atlases made from the shared surface facts. */
final class InGameMapTiles implements AutoCloseable {
    static final int CHUNK = 16;
    private static final int CACHE_REGIONS_PER_ATLAS = 2;
    private static final int REGION_CHUNKS = CartographBackendApi.REGION_CHUNKS * CACHE_REGIONS_PER_ATLAS;
    private static final int REGION_SIZE = CHUNK * REGION_CHUNKS;
    private static final int REGION_TILES = REGION_CHUNKS * REGION_CHUNKS;
    private static final int MAX_ATLASES = 48;
    private static final int MAX_PAINTED_TILES = 96;
    private static final long CPU_BUDGET_NS = 4_000_000L;

    private record Region(int x, int z) {}

    private static final class Atlas {
        final SurfaceTile[] rendered = new SurfaceTile[REGION_TILES];
        final DynamicTexture texture;
        final ResourceLocation id;
        int next = REGION_TILES;
        long revision = Long.MIN_VALUE;
        boolean hasPixels;

        Atlas(TextureManager textures) {
            texture = new DynamicTexture(REGION_SIZE, REGION_SIZE, true);
            id = textures.register("cartograph_native_map", texture);
        }
    }

    private final TextureManager textures = Minecraft.getInstance().getTextureManager();
    private final LinkedHashMap<Region, Atlas> atlases = new LinkedHashMap<>(16, .75f, true);
    private final CartographBackendApi backend;
    private String dimension = "";
    private long signature;

    InGameMapTiles(CartographBackendApi backend) {
        this.backend = backend;
    }

    void update(InGameMapCamera camera) {
        String dim = backend.dimension();
        long appearance = SatelliteSurfaceRenderer.renderSignature();
        if (!dim.equals(dimension) || appearance != signature) {
            close();
            dimension = dim;
            signature = appearance;
        }
        if (dim.isBlank()) return;

        List<Region> visible = new ArrayList<>();
        for (int z = region(camera.worldZ(camera.top())); z <= region(camera.worldZ(camera.bottom())); z++) {
            for (int x = region(camera.worldX(0)); x <= region(camera.worldX(camera.width())); x++) {
                Region key = new Region(x, z);
                if (atlases.containsKey(key) || hasTiles(dim, key)) visible.add(key);
            }
        }
        int middleX = region(camera.worldX(camera.middleX()));
        int middleZ = region(camera.worldZ(camera.middleY()));
        visible.sort(Comparator.comparingInt(key -> Math.abs(key.x - middleX) + Math.abs(key.z - middleZ)));
        if (visible.size() > MAX_ATLASES) visible = visible.subList(0, MAX_ATLASES);
        // Mark already visible atlases as recent before allocating anything.
        for (Region key : visible) atlases.get(key);

        int remaining = MAX_PAINTED_TILES;
        long deadline = System.nanoTime() + CPU_BUDGET_NS;
        for (Region key : visible) {
            if (remaining == 0 || System.nanoTime() >= deadline) break;
            Atlas atlas = atlases.get(key);
            if (atlas == null) {
                if (atlases.size() == MAX_ATLASES) textures.release(atlases.pollFirstEntry().getValue().id);
                atlas = new Atlas(textures);
                atlases.put(key, atlas);
            }
            if (atlas.next == REGION_TILES) {
                long current = revision(dim, key);
                if (atlas.revision == current) continue;
                atlas.revision = current;
                atlas.next = 0;
            }

            boolean firstUpload = !atlas.hasPixels;
            int minX = REGION_CHUNKS;
            int minZ = REGION_CHUNKS;
            int maxX = -1;
            int maxZ = -1;
            while (atlas.next < REGION_TILES && remaining > 0 && System.nanoTime() < deadline) {
                int index = atlas.next++;
                int localX = index % REGION_CHUNKS;
                int localZ = index / REGION_CHUNKS;
                SurfaceTile tile = backend.tile(new ChunkKey(dim,
                        key.x * REGION_CHUNKS + localX, key.z * REGION_CHUNKS + localZ));
                if (tile != null && !tile.hasSurfaceFacts()) tile = null;
                if (tile == atlas.rendered[index]) continue;

                paint(atlas.texture.getPixels(), localX, localZ, tile);
                atlas.rendered[index] = tile;
                atlas.hasPixels |= tile != null;
                minX = Math.min(minX, localX);
                minZ = Math.min(minZ, localZ);
                maxX = Math.max(maxX, localX);
                maxZ = Math.max(maxZ, localZ);
                remaining--;
            }
            if (maxX < minX) continue;

            if (firstUpload) {
                // Initialize the whole texture, including transparent gaps.
                atlas.texture.upload();
            } else {
                uploadChangedTiles(atlas.texture, minX, minZ, maxX, maxZ);
            }
        }
    }

    private static void uploadChangedTiles(
            DynamicTexture texture, int minX, int minZ, int maxX, int maxZ) {
        int x = minX * CHUNK;
        int z = minZ * CHUNK;
        int width = (maxX - minX + 1) * CHUNK;
        int height = (maxZ - minZ + 1) * CHUNK;
        texture.bind();
        texture.getPixels().upload(0, x, z, x, z, width, height, false, false);
    }

    private boolean hasTiles(String dimension, Region atlas) {
        int x = atlas.x * CACHE_REGIONS_PER_ATLAS;
        int z = atlas.z * CACHE_REGIONS_PER_ATLAS;
        for (int dz = 0; dz < CACHE_REGIONS_PER_ATLAS; dz++) {
            for (int dx = 0; dx < CACHE_REGIONS_PER_ATLAS; dx++) {
                if (backend.hasTilesInRegion(dimension, x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    private long revision(String dimension, Region atlas) {
        int x = atlas.x * CACHE_REGIONS_PER_ATLAS;
        int z = atlas.z * CACHE_REGIONS_PER_ATLAS;
        long newest = 0;
        for (int dz = 0; dz < CACHE_REGIONS_PER_ATLAS; dz++) {
            for (int dx = 0; dx < CACHE_REGIONS_PER_ATLAS; dx++) {
                newest = Math.max(newest, backend.regionRevision(dimension, x + dx, z + dz));
            }
        }
        return newest;
    }

    private static void paint(NativeImage image, int x, int z, SurfaceTile tile) {
        int[] colors = tile == null ? null : SatelliteSurfaceRenderer.render(tile).rgb();
        for (int row = 0; row < CHUNK; row++) {
            for (int column = 0; column < CHUNK; column++) {
                int pixel = colors == null ? 0 : opaqueAbgr(colors[row * CHUNK + column]);
                image.setPixelRGBA(x * CHUNK + column, z * CHUNK + row, pixel);
            }
        }
    }

    static int opaqueAbgr(int color) {
        return 0xff000000 | (color & 255) << 16 | color & 0xff00 | color >>> 16 & 255;
    }

    void draw(GuiGraphics graphics, InGameMapCamera camera) {
        for (var entry : atlases.entrySet()) {
            Region region = entry.getKey();
            Atlas atlas = entry.getValue();
            if (!atlas.hasPixels) continue;

            int x = Math.round((float) camera.screenX((long) region.x * REGION_SIZE));
            int y = Math.round((float) camera.screenY((long) region.z * REGION_SIZE));
            int right = Math.round((float) camera.screenX(((long) region.x + 1) * REGION_SIZE));
            int bottom = Math.round((float) camera.screenY(((long) region.z + 1) * REGION_SIZE));
            if (right <= 0 || x >= camera.width() || bottom <= camera.top() || y >= camera.bottom()) continue;
            graphics.blit(atlas.id, x, y, right - x, bottom - y,
                    0f, 0f, REGION_SIZE, REGION_SIZE, REGION_SIZE, REGION_SIZE);
        }
    }

    private static int region(double coordinate) {
        return (int) Math.floor(coordinate / REGION_SIZE);
    }

    @Override
    public void close() {
        for (Atlas atlas : atlases.values()) textures.release(atlas.id);
        atlases.clear();
    }
}
