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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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
    private static final int MAX_PENDING_ATLAS_READS = 4;
    private static final ExecutorService ATLAS_READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v31-rendered-atlas-reader");
        thread.setDaemon(true);
        return thread;
    });

    private record Region(int x, int z) {}
    private record TextureKey(Region region, int stride, long signature) {}
    private record DrawTile(Region region, Atlas atlas) {}

    private static final class Atlas {
        final long[] fingerprints;
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
            if (restored == null) {
                fingerprints = new long[REGION_TILES];
                pixels = new int[size * size];
                return;
            }

            fingerprints = restored.fingerprints();
            pixels = restored.pixels();
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

        long texels() { return (long) size * size; }
    }

    private final LinkedHashMap<TextureKey, Atlas> atlases = new LinkedHashMap<>(16, .75f, true);
    private final Map<TextureKey, CompletableFuture<Optional<PersistentRenderedAtlasStore.Snapshot>>> reads = new HashMap<>();
    private final Set<TextureKey> diskMisses = new HashSet<>();
    private final CartographBackendApi backend;
    private List<DrawTile> frame = List.of();
    private long allocatedTexels;
    private String dimension = "";
    private long targetSignature;
    private int createdThisFrame;
    private long frameDeadline;

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
        createdThisFrame = 0;
        int minX = region(camera.worldX(0));
        int maxX = region(camera.worldX(camera.width()));
        int minZ = region(camera.worldZ(camera.top()));
        int maxZ = region(camera.worldZ(camera.bottom()));
        // Mark regions that can be painted from facts already in memory.
        Map<Region, Boolean> candidates = new HashMap<>();
        for (var cached : backend.cachedRegionsInBounds(dim,
                minX * CACHE_REGIONS_PER_ATLAS, (maxX + 1) * CACHE_REGIONS_PER_ATLAS - 1,
                minZ * CACHE_REGIONS_PER_ATLAS, (maxZ + 1) * CACHE_REGIONS_PER_ATLAS - 1)) {
            candidates.put(new Region(Math.floorDiv(cached.x(), CACHE_REGIONS_PER_ATLAS),
                    Math.floorDiv(cached.z(), CACHE_REGIONS_PER_ATLAS)), true);
        }
        for (TextureKey key : atlases.keySet()) {
            Region region = key.region();
            if (key.signature() == targetSignature && region.x >= minX && region.x <= maxX
                    && region.z >= minZ && region.z <= maxZ) candidates.putIfAbsent(region, false);
        }
        for (var region : PersistentRenderedAtlasStore.regionsInBounds(
                worldId, dim, targetSignature, minX, maxX, minZ, maxZ)) {
            candidates.putIfAbsent(new Region(region.x(), region.z()), false);
        }
        List<Region> visible = new ArrayList<>(candidates.keySet());
        int middleX = region(camera.worldX(camera.middleX()));
        int middleZ = region(camera.worldZ(camera.middleY()));
        visible.sort(Comparator.comparingInt(key -> Math.abs(key.x - middleX) + Math.abs(key.z - middleZ)));

        int stride = overviewStride(camera.zoom(), visible.size());
        reads.entrySet().removeIf(entry -> {
            TextureKey key = entry.getKey();
            Atlas exact = atlases.get(new TextureKey(key.region(), stride, targetSignature));
            boolean discard = key.signature() != targetSignature || !candidates.containsKey(key.region())
                    || (key.stride() != stride && exact != null && exact.complete && exact.hasPixels);
            if (discard) entry.getValue().cancel(false);
            return discard;
        });
        int remaining = MAX_PAINTED_TILES * Math.max(1, stride * stride / 4);
        long deadline = System.nanoTime() + CPU_BUDGET_NS;
        frameDeadline = deadline;

        Set<TextureKey> keep = new HashSet<>();
        for (Region region : visible) {
            TextureKey key = new TextureKey(region, stride, targetSignature);
            Atlas atlas = atlases.get(key);
            if (atlas == null) {
                atlas = createAtlas(worldId, dim, key, candidates.get(region));
                if (atlas == null) continue;
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

    private Atlas createAtlas(UUID worldId, String dimension, TextureKey key, boolean hasCachedFacts) {
        if (createdThisFrame >= 1 || System.nanoTime() >= frameDeadline) return null;
        Optional<PersistentRenderedAtlasStore.Snapshot> restored = loadSnapshot(worldId, dimension, key);
        // Leave the creation slot available for an existing alternate resolution.
        if (restored == null || (restored.isEmpty() && !hasCachedFacts)) return null;
        createdThisFrame++;
        return new Atlas(key.stride(), key.signature(), restored.orElse(null));
    }

    private Optional<PersistentRenderedAtlasStore.Snapshot> loadSnapshot(
            UUID worldId, String dimension, TextureKey key) {
        if (worldId == null || diskMisses.contains(key)) return Optional.empty();
        var future = reads.get(key);
        if (future == null) {
            if (reads.size() >= MAX_PENDING_ATLAS_READS) return null;
            future = CompletableFuture.supplyAsync(() -> PersistentRenderedAtlasStore.load(
                    worldId, dimension, key.signature(), key.region().x, key.region().z,
                    key.stride(), REGION_SIZE / key.stride(), REGION_TILES), ATLAS_READER);
            reads.put(key, future);
        }
        if (!future.isDone()) return null;
        reads.remove(key);
        Optional<PersistentRenderedAtlasStore.Snapshot> restored;
        try {
            restored = future.join();
        } catch (java.util.concurrent.CompletionException failure) {
            restored = Optional.empty();
        }
        if (restored.isEmpty()) diskMisses.add(key);
        return restored;
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
            if (candidate == null && worldId != null && createdThisFrame < 1
                    && System.nanoTime() < frameDeadline) {
                var restored = loadSnapshot(worldId, dimension, key);
                if (restored != null && restored.isPresent()) {
                    candidate = new Atlas(alternate, signature, restored.get());
                    createdThisFrame++;
                    atlases.put(key, candidate);
                    allocatedTexels += candidate.texels();
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
                if (!backend.isTileMissing(tileKey)) continue;
                if (atlas.fingerprints[index] == 0L) continue;
                paint(atlas, localX, localZ, null, stride, edge);
                atlas.fingerprints[index] = 0L;
                atlas.dirtyForSave = true;
            } else {
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
            diskMisses.remove(new TextureKey(key, stride, atlas.signature));
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
        for (Atlas atlas : atlases.values()) atlas.texture.close();
        atlases.clear();
        reads.values().forEach(future -> future.cancel(false));
        reads.clear();
        diskMisses.clear();
        frame = List.of();
        allocatedTexels = 0;
        dimension = "";
        targetSignature = 0L;
    }
}
