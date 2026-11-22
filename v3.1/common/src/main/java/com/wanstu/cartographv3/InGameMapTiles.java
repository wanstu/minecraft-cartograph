package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Draws cached surface facts at the resolution appropriate for the current view. */
final class InGameMapTiles implements AutoCloseable {
    static final int CHUNK = 16;
    private static final int CACHE_REGIONS_PER_ATLAS = 2;
    private static final int REGION_CHUNKS = CartographBackendApi.REGION_CHUNKS * CACHE_REGIONS_PER_ATLAS;
    private static final int REGION_SIZE = CHUNK * REGION_CHUNKS;
    private static final int REGION_TILES = REGION_CHUNKS * REGION_CHUNKS;
    private static final long RETAINED_TEXELS = 48L * REGION_SIZE * REGION_SIZE;
    private static final int MAX_PAINTED_TILES = 96;
    private static final long CPU_BUDGET_NS = 4_000_000L;

    private record Region(int x, int z) {}
    private record TextureKey(Region region, int stride) {}
    private record DrawTile(Region region, Atlas atlas) {}

    private static final class Atlas {
        final SurfaceTile[] rendered = new SurfaceTile[REGION_TILES];
        final MapGraphics.Texture texture;
        final int size;
        int next = REGION_TILES;
        long revision = Long.MIN_VALUE;
        boolean hasPixels;
        boolean complete;

        Atlas(int stride) {
            size = REGION_SIZE / stride;
            texture = new MapGraphics.Texture(size);
        }

        long texels() { return (long) size * size; }
    }

    private final LinkedHashMap<TextureKey, Atlas> atlases = new LinkedHashMap<>(16, .75f, true);
    private final CartographBackendApi backend;
    private List<DrawTile> frame = List.of();
    private long allocatedTexels;
    private String dimension = "";
    private long signature;

    InGameMapTiles(CartographBackendApi backend) {
        this.backend = backend;
    }

    void update(InGameMapCamera camera) {
        String dim = backend.dimension();
        long appearance = MapPresentationRenderer.renderSignature();
        if (!dim.equals(dimension) || appearance != signature) {
            close();
            dimension = dim;
            signature = appearance;
        }
        if (dim.isBlank()) return;

        List<Region> visible = new ArrayList<>();
        int minX = region(camera.worldX(0));
        int maxX = region(camera.worldX(camera.width()));
        int minZ = region(camera.worldZ(camera.top()));
        int maxZ = region(camera.worldZ(camera.bottom()));
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                Region key = new Region(x, z);
                if (hasTiles(dim, key)) visible.add(key);
            }
        }
        int middleX = region(camera.worldX(camera.middleX()));
        int middleZ = region(camera.worldZ(camera.middleY()));
        visible.sort(Comparator.comparingInt(key -> Math.abs(key.x - middleX) + Math.abs(key.z - middleZ)));

        int stride = overviewStride(camera.zoom(), visible.size());
        int remaining = MAX_PAINTED_TILES * Math.max(1, stride * stride / 4);
        long deadline = System.nanoTime() + CPU_BUDGET_NS;
        for (Region region : visible) {
            if (remaining == 0 || System.nanoTime() >= deadline) break;
            TextureKey key = new TextureKey(region, stride);
            Atlas atlas = atlases.get(key);
            if (atlas == null) {
                atlas = new Atlas(stride);
                atlases.put(key, atlas);
                allocatedTexels += atlas.texels();
            }
            remaining -= updateAtlas(dim, region, atlas, stride, remaining, deadline);
        }

        List<DrawTile> drawing = new ArrayList<>(visible.size());
        Set<TextureKey> keep = new HashSet<>();
        for (Region region : visible) {
            TextureKey key = new TextureKey(region, stride);
            Atlas atlas = atlases.get(key);
            if (atlas != null) keep.add(key);
            if (atlas == null || !atlas.complete) {
                // Keep displaying cached terrain while another resolution is prepared.
                for (int alternate = 1; alternate <= CHUNK; alternate *= 2) {
                    if (alternate == stride) continue;
                    TextureKey fallback = new TextureKey(region, alternate);
                    Atlas candidate = atlases.get(fallback);
                    if (candidate == null || !candidate.complete || !candidate.hasPixels) continue;
                    keep.add(fallback);
                    atlas = candidate;
                    break;
                }
            }
            if (atlas != null && atlas.hasPixels) drawing.add(new DrawTile(region, atlas));
        }
        frame = drawing;
        evictOffscreen(keep);
    }

    private static int overviewStride(double zoom, int regions) {
        int stride = 1;
        while (stride < CHUNK) {
            int size = REGION_SIZE / stride;
            if (zoom * stride * 2 > 1.0 && (long) regions * size * size <= RETAINED_TEXELS) break;
            stride *= 2;
        }
        return stride;
    }

    private int updateAtlas(
            String dimension, Region key, Atlas atlas, int stride, int remaining, long deadline) {
        if (atlas.next == REGION_TILES) {
            long current = revision(dimension, key);
            if (atlas.revision == current) return 0;
            atlas.revision = current;
            atlas.next = 0;
        }

        int edge = CHUNK / stride;
        boolean firstUpload = !atlas.hasPixels;
        int minX = REGION_CHUNKS;
        int minZ = REGION_CHUNKS;
        int maxX = -1;
        int maxZ = -1;
        int painted = 0;
        while (atlas.next < REGION_TILES && painted < remaining && System.nanoTime() < deadline) {
            int index = atlas.next++;
            int localX = index % REGION_CHUNKS;
            int localZ = index / REGION_CHUNKS;
            SurfaceTile tile = backend.tile(new ChunkKey(dimension,
                    key.x * REGION_CHUNKS + localX, key.z * REGION_CHUNKS + localZ));
            if (tile != null && !tile.hasSurfaceFacts()) tile = null;
            if (tile == atlas.rendered[index]) continue;

            paint(atlas.texture.getPixels(), localX, localZ, tile, stride, edge);
            atlas.rendered[index] = tile;
            atlas.hasPixels |= tile != null;
            minX = Math.min(minX, localX);
            minZ = Math.min(minZ, localZ);
            maxX = Math.max(maxX, localX);
            maxZ = Math.max(maxZ, localZ);
            painted++;
        }
        atlas.complete |= atlas.next == REGION_TILES;
        if (painted == 0) return 0;

        if (firstUpload) {
            // Initialize transparent regions on the GPU before uploading individual updates.
            atlas.texture.upload();
        } else {
            int x = minX * edge;
            int z = minZ * edge;
            int width = (maxX - minX + 1) * edge;
            int height = (maxZ - minZ + 1) * edge;
            atlas.texture.upload(x, z, width, height);
        }
        return painted;
    }

    private void evictOffscreen(Set<TextureKey> keep) {
        var entries = atlases.entrySet().iterator();
        while (allocatedTexels > RETAINED_TEXELS && entries.hasNext()) {
            var entry = entries.next();
            if (keep.contains(entry.getKey())) continue;
            Atlas discarded = entry.getValue();
            discarded.texture.close();
            allocatedTexels -= discarded.texels();
            entries.remove();
        }
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

    private static void paint(NativeImage image, int x, int z, SurfaceTile tile, int stride, int edge) {
        int[] colors = tile == null ? null : MapPresentationRenderer.renderPixels(tile, stride);
        for (int row = 0; row < edge; row++) {
            for (int column = 0; column < edge; column++) {
                int pixel = colors == null ? 0 : opaqueAbgr(colors[row * edge + column]);
                MapGraphics.writeAbgr(image, x * edge + column, z * edge + row, pixel);
            }
        }
    }

    static int opaqueAbgr(int color) {
        return 0xff000000 | (color & 255) << 16 | color & 0xff00 | color >>> 16 & 255;
    }

    void draw(MapGraphics graphics, InGameMapCamera camera) {
        for (DrawTile item : frame) {
            Region region = item.region;
            Atlas atlas = item.atlas;
            int x = Math.round((float) camera.screenX((long) region.x * REGION_SIZE));
            int y = Math.round((float) camera.screenY((long) region.z * REGION_SIZE));
            int right = Math.round((float) camera.screenX(((long) region.x + 1) * REGION_SIZE));
            int bottom = Math.round((float) camera.screenY(((long) region.z + 1) * REGION_SIZE));
            if (right <= 0 || x >= camera.width() || bottom <= camera.top() || y >= camera.bottom()) continue;
            atlas.texture.draw(graphics, x, y, right - x, bottom - y);
        }
    }

    private static int region(double coordinate) {
        return (int) Math.floor(coordinate / REGION_SIZE);
    }

    @Override
    public void close() {
        for (Atlas atlas : atlases.values()) atlas.texture.close();
        atlases.clear();
        frame = List.of();
        allocatedTexels = 0;
    }
}
