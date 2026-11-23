package com.wanstu.cartographv3;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private record TextureKey(Region region, int stride, long signature) {}
    private record DrawTile(Region region, Atlas atlas) {}
    private record PersistentRegionKey(Region region, long signature) {}

    private static final class Atlas {
        final long[] fingerprints = new long[REGION_TILES];
        final boolean[] seenFacts = new boolean[REGION_TILES];
        final int[] pixels;
        final MapGraphics.Texture texture;
        final int size;
        final long signature;
        int next = REGION_TILES;
        long revision = Long.MIN_VALUE;
        boolean hasPixels;
        boolean complete;
        boolean dirtyForSave;

        Atlas(int stride, long signature, PersistentRenderedAtlasStore.Snapshot restored) {
            this.signature = signature;
            size = REGION_SIZE / stride;
            texture = new MapGraphics.Texture(size);
            pixels = new int[size * size];

            if (restored != null) {
                System.arraycopy(restored.fingerprints(), 0, fingerprints, 0, REGION_TILES);
                System.arraycopy(restored.pixels(), 0, pixels, 0, pixels.length);
                NativeImage image = texture.getPixels();
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int pixel = pixels[y * size + x];
                        MapGraphics.writeAbgr(image, x, y, pixel);
                        hasPixels |= pixel != 0;
                    }
                }
                texture.upload();
                complete = true;
            }
        }

        long texels() { return (long) size * size; }
    }

    private final LinkedHashMap<TextureKey, Atlas> atlases = new LinkedHashMap<>(16, .75f, true);
    private final Map<PersistentRegionKey, Boolean> persistentRegionPresence = new HashMap<>();
    private final CartographBackendApi backend;
    private List<DrawTile> frame = List.of();
    private long allocatedTexels;
    private String dimension = "";
    private long targetSignature;

    InGameMapTiles(CartographBackendApi backend) {
        this.backend = backend;
    }

    void update(InGameMapCamera camera) {
        String dim = backend.dimension();
        long appearance = MapPresentationRenderer.renderSignature();
        if (!dim.equals(dimension)) {
            close();
            dimension = dim;
            targetSignature = appearance;
        } else if (targetSignature != appearance) {
            targetSignature = appearance;
            // Never keep drawing the previous presentation after the user has
            // selected another drawing mode/style.
            frame = List.of();
        }
        if (dim.isBlank()) return;

        UUID worldId = backend.worldId();
        List<Region> visible = new ArrayList<>();
        int minX = region(camera.worldX(0));
        int maxX = region(camera.worldX(camera.width()));
        int minZ = region(camera.worldZ(camera.top()));
        int maxZ = region(camera.worldZ(camera.bottom()));
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                Region key = new Region(x, z);
                if (hasTiles(dim, key)
                        || hasInMemoryAtlas(key, targetSignature)
                        || hasPersistentAtlas(worldId, dim, key, targetSignature)) {
                    visible.add(key);
                }
            }
        }
        int middleX = region(camera.worldX(camera.middleX()));
        int middleZ = region(camera.worldZ(camera.middleY()));
        visible.sort(Comparator.comparingInt(key -> Math.abs(key.x - middleX) + Math.abs(key.z - middleZ)));

        int stride = overviewStride(camera.zoom(), visible.size());
        int remaining = MAX_PAINTED_TILES * Math.max(1, stride * stride / 4);
        long deadline = System.nanoTime() + CPU_BUDGET_NS;

        Set<TextureKey> keep = new HashSet<>();
        for (Region region : visible) {
            TextureKey key = new TextureKey(region, stride, targetSignature);
            Atlas atlas = atlases.get(key);
            if (atlas == null) {
                atlas = createAtlas(worldId, dim, region, stride, targetSignature);
                atlases.put(key, atlas);
                allocatedTexels += atlas.texels();
            }
            keep.add(key);
            if (remaining > 0 && System.nanoTime() < deadline) {
                remaining -= updateAtlas(worldId, dim, region, atlas, stride, remaining, deadline);
            }
        }

        List<DrawTile> drawing = new ArrayList<>(visible.size());
        for (Region region : visible) {
            Atlas atlas = findDisplayAtlas(
                    worldId, dim, region, stride, targetSignature, keep);
            if (atlas != null && atlas.hasPixels) drawing.add(new DrawTile(region, atlas));
        }

        frame = drawing;
        evictOffscreen(keep);
    }

    private Atlas createAtlas(
            UUID worldId, String dimension, Region region, int stride, long signature) {
        int size = REGION_SIZE / stride;
        PersistentRenderedAtlasStore.Snapshot restored = PersistentRenderedAtlasStore.load(
                worldId, dimension, signature, region.x, region.z, stride, size, REGION_TILES)
                .orElse(null);
        if (restored != null) {
            persistentRegionPresence.put(new PersistentRegionKey(region, signature), true);
        }
        return new Atlas(stride, signature, restored);
    }

    private Atlas findDisplayAtlas(
            UUID worldId,
            String dimension,
            Region region,
            int stride,
            long signature,
            Set<TextureKey> keep) {
        TextureKey exact = new TextureKey(region, stride, signature);
        Atlas atlas = atlases.get(exact);
        if (atlas != null && atlas.complete && atlas.hasPixels) {
            keep.add(exact);
            return atlas;
        }

        // A complete atlas of the same target presentation is preferable to a
        // partially generated exact-stride atlas. Never cross signatures here.
        for (int alternate = 1; alternate <= CHUNK; alternate *= 2) {
            if (alternate == stride) continue;
            TextureKey key = new TextureKey(region, alternate, signature);
            Atlas candidate = atlases.get(key);
            if (candidate == null && worldId != null) {
                int size = REGION_SIZE / alternate;
                PersistentRenderedAtlasStore.Snapshot restored = PersistentRenderedAtlasStore.load(
                        worldId, dimension, signature,
                        region.x, region.z, alternate, size, REGION_TILES).orElse(null);
                if (restored != null) {
                    candidate = new Atlas(alternate, signature, restored);
                    atlases.put(key, candidate);
                    allocatedTexels += candidate.texels();
                    persistentRegionPresence.put(
                            new PersistentRegionKey(region, signature), true);
                }
            }
            if (candidate == null || !candidate.complete || !candidate.hasPixels) continue;
            keep.add(key);
            return candidate;
        }

        // If no complete target atlas exists yet, progressively reveal only
        // the current target presentation. This may be incomplete, but it can
        // never show pixels from a previous drawing mode/style.
        if (atlas != null && atlas.hasPixels) {
            keep.add(exact);
            return atlas;
        }
        return null;
    }

    private boolean hasInMemoryAtlas(Region region, long signature) {
        for (TextureKey key : atlases.keySet()) {
            if (key.region.equals(region) && key.signature == signature) return true;
        }
        return false;
    }

    private boolean hasPersistentAtlas(
            UUID worldId, String dimension, Region region, long signature) {
        if (worldId == null) return false;
        PersistentRegionKey key = new PersistentRegionKey(region, signature);
        return persistentRegionPresence.computeIfAbsent(key,
                ignored -> PersistentRenderedAtlasStore.existsAnyStride(
                        worldId, dimension, signature, region.x, region.z));
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
            UUID worldId,
            String dimension,
            Region key,
            Atlas atlas,
            int stride,
            int remaining,
            long deadline) {
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
            ChunkKey tileKey = new ChunkKey(
                    dimension,
                    key.x * REGION_CHUNKS + localX,
                    key.z * REGION_CHUNKS + localZ);
            SurfaceTile tile = backend.tile(tileKey);
            if (tile != null && !tile.hasSurfaceFacts()) tile = null;

            if (tile == null) {
                // A disk-restored atlas is allowed to stay visible before its
                // corresponding facts have been restored this session.
                if (!atlas.seenFacts[index]) continue;
                if (atlas.fingerprints[index] == 0L) continue;
                paint(atlas, localX, localZ, null, stride, edge);
                atlas.fingerprints[index] = 0L;
                atlas.dirtyForSave = true;
            } else {
                atlas.seenFacts[index] = true;
                long fingerprint = MapPresentationRenderer.dependencyFingerprint(
                        tile, backend::tile);
                if (atlas.fingerprints[index] == fingerprint) continue;
                paint(atlas, localX, localZ, tile, stride, edge);
                atlas.fingerprints[index] = fingerprint;
                atlas.hasPixels = true;
                atlas.dirtyForSave = true;
            }

            minX = Math.min(minX, localX);
            minZ = Math.min(minZ, localZ);
            maxX = Math.max(maxX, localX);
            maxZ = Math.max(maxZ, localZ);
            painted++;
        }

        atlas.complete |= atlas.next == REGION_TILES;
        if (painted > 0) {
            if (firstUpload) {
                atlas.texture.upload();
            } else {
                int x = minX * edge;
                int z = minZ * edge;
                int width = (maxX - minX + 1) * edge;
                int height = (maxZ - minZ + 1) * edge;
                atlas.texture.upload(x, z, width, height);
            }
        }

        if (atlas.next == REGION_TILES && atlas.dirtyForSave && atlas.hasPixels && worldId != null) {
            PersistentRenderedAtlasStore.save(
                    worldId, dimension, atlas.signature,
                    key.x, key.z, stride, atlas.size,
                    atlas.fingerprints, atlas.pixels);
            persistentRegionPresence.put(
                    new PersistentRegionKey(key, atlas.signature), true);
            atlas.dirtyForSave = false;
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
        // Hand-drawn relief consumes a one-chunk halo. Include adjacent
        // backend regions only for that mode; satellite rendering stays
        // strictly chunk-local and should not rescan an atlas for neighbor-only
        // changes.
        int halo = CartographSettings.drawingMode() == MapDrawingMode.HAND_DRAWN ? 1 : 0;
        for (int dz = -halo; dz < CACHE_REGIONS_PER_ATLAS + halo; dz++) {
            for (int dx = -halo; dx < CACHE_REGIONS_PER_ATLAS + halo; dx++) {
                newest = Math.max(newest, backend.regionRevision(dimension, x + dx, z + dz));
            }
        }
        return newest;
    }

    private void paint(
            Atlas atlas, int x, int z, SurfaceTile tile, int stride, int edge) {
        int[] colors = tile == null ? null
                : MapPresentationRenderer.renderPixels(tile, stride, backend::tile);
        NativeImage image = atlas.texture.getPixels();
        for (int row = 0; row < edge; row++) {
            for (int column = 0; column < edge; column++) {
                int pixel = colors == null ? 0 : opaqueAbgr(colors[row * edge + column]);
                int pixelX = x * edge + column;
                int pixelZ = z * edge + row;
                atlas.pixels[pixelZ * atlas.size + pixelX] = pixel;
                MapGraphics.writeAbgr(image, pixelX, pixelZ, pixel);
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
        PersistentRenderedAtlasStore.flush();
        for (Atlas atlas : atlases.values()) atlas.texture.close();
        atlases.clear();
        persistentRegionPresence.clear();
        frame = List.of();
        allocatedTexels = 0;
        dimension = "";
        targetSignature = 0L;
    }
}
