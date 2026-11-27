package com.wanstu.cartographv3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One pixel per saved chunk, independent of the detailed facts kept near the viewport. */
final class InGameMapOverview implements AutoCloseable {
    private static final int SIDE = SavedMapCoverage.REGION_CHUNKS;
    private static final int WORLD_SIDE = SIDE * SurfaceTile.SIZE;
    private static final int PIXELS = SIDE * SIDE;
    private static final int RETAINED_IMAGES = 1024;
    private static final int MAX_READS = 2;
    private static final long OVERVIEW_SALT = 0xb2cd9526948ff0e1L;
    private static final ExecutorService READER = Executors.newFixedThreadPool(MAX_READS, task -> {
        Thread thread = new Thread(task, "cartograph-overview-reader");
        thread.setDaemon(true);
        return thread;
    });

    private record Key(SavedMapCoverage.Region region, long appearance) {}

    private static final class Image {
        final MapGraphics.Texture texture = new MapGraphics.Texture(SIDE);
        long revision = -1;
    }

    private static final class Read {
        volatile boolean cancelled;
        final long revision;
        final CompletableFuture<Optional<PersistentRenderedAtlasStore.Snapshot>> preview =
                new CompletableFuture<>();
        final CompletableFuture<SurfaceTile[]> facts;
        final long[] coverage = new long[PIXELS];
        final int[] pixels = new int[PIXELS];
        int next;

        Read(UUID world, String dimension, Key key, SavedMapCoverage source,
                long revision, boolean halo, boolean restorePreview) {
            this.revision = revision;
            facts = CompletableFuture.supplyAsync(() -> {
                var region = key.region();
                preview.complete(restorePreview && !cancelled
                        ? PersistentRenderedAtlasStore.load(world, dimension, signature(key.appearance()),
                                region.x(), region.z(), SurfaceTile.SIZE, SIDE, PIXELS)
                        : Optional.empty());
                return source.read(region, halo, () -> !cancelled);
            }, READER);
        }

        SurfaceTile tile(SurfaceTile[] tiles, int localX, int localZ) {
            int span = SIDE + 2;
            return tiles[(localZ + 1) * span + localX + 1];
        }
    }

    private final Map<Key, Image> images = new LinkedHashMap<>(16, .75f, true);
    private final Map<Key, Read> reads = new LinkedHashMap<>();
    private final Map<Key, Long> empty = new LinkedHashMap<>();
    private UUID world;
    private String dimension = "";
    private long appearance;

    static boolean wide(InGameMapCamera camera) {
        long width = chunk(camera.worldX(camera.width())) - (long) chunk(camera.worldX(0)) + 3;
        long height = chunk(camera.worldZ(camera.bottom())) - (long) chunk(camera.worldZ(camera.top())) + 3;
        return width * height > CartographBackendApi.MAX_RAW_VISIBLE_TILES;
    }

    void update(UUID worldId, String dim, long signature, InGameMapCamera camera, long deadline) {
        if (!java.util.Objects.equals(world, worldId) || !dimension.equals(dim)) {
            close();
            world = worldId;
            dimension = dim;
        }
        appearance = signature;
        if (worldId == null) return;

        int minX = region(camera.worldX(0));
        int maxX = region(camera.worldX(camera.width()));
        int minZ = region(camera.worldZ(camera.top()));
        int maxZ = region(camera.worldZ(camera.bottom()));
        boolean wide = wide(camera);
        reads.entrySet().removeIf(entry -> {
            var pos = entry.getKey().region();
            boolean cancel = !wide || entry.getKey().appearance() != signature
                    || pos.x() < minX - 1 || pos.x() > maxX + 1
                    || pos.z() < minZ - 1 || pos.z() > maxZ + 1;
            if (cancel) entry.getValue().cancelled = true;
            return cancel;
        });
        if (!wide) return;

        SavedMapCoverage source = PersistentTileStore.savedCoverage(worldId, dim, READER);
        boolean halo = CartographSettings.drawingMode() == MapDrawingMode.HAND_DRAWN;
        List<SavedMapCoverage.Region> visible = source.regionsInBounds(minX, maxX, minZ, maxZ);
        int centerX = region(camera.worldX(camera.middleX()));
        int centerZ = region(camera.worldZ(camera.middleY()));
        visible.sort(Comparator.comparingInt(pos ->
                Math.abs(pos.x() - centerX) + Math.abs(pos.z() - centerZ)));

        // One small texture upload per update. Live detail can still use the rest of the frame budget.
        for (var iterator = reads.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            Key key = entry.getKey();
            Read read = entry.getValue();
            if (System.nanoTime() >= deadline) break;
            try {
                Image image = images.get(key);
                if (image == null && read.preview.isDone()) {
                    var snapshot = read.preview.join();
                    if (snapshot.isPresent()) {
                        putImage(key, snapshot.get().pixels());
                        break;
                    }
                }
                if (!read.facts.isDone()) continue;
                SurfaceTile[] facts = read.facts.join();
                while (read.next < PIXELS && System.nanoTime() < deadline) {
                    int index = read.next++;
                    SurfaceTile tile = read.tile(facts, index % SIDE, index / SIDE);
                    if (tile == null) continue;
                    read.coverage[index] = 1;
                    read.pixels[index] = InGameMapTiles.opaqueAbgr(MapPresentationRenderer.overviewColor(tile,
                            other -> read.tile(facts, other.x() - key.region().x() * SIDE,
                                    other.z() - key.region().z() * SIDE)));
                }
                if (read.next < PIXELS) continue;
                var region = key.region();
                if (java.util.Arrays.stream(read.coverage).anyMatch(value -> value != 0)) {
                    putImage(key, read.pixels).revision = read.revision;
                    PersistentRenderedAtlasStore.save(worldId, dim, signature(key.appearance()),
                            region.x(), region.z(), SurfaceTile.SIZE, SIDE, read.coverage, read.pixels);
                } else {
                    Image blank = images.remove(key);
                    if (blank != null) blank.texture.close();
                    empty.put(key, read.revision);
                    if (empty.size() > RETAINED_IMAGES) empty.remove(empty.keySet().iterator().next());
                }
                iterator.remove();
                break;
            } catch (CompletionException failed) {
                iterator.remove();
            }
        }

        queueReads(worldId, dim, signature, source, halo, visible, false);
        queueReads(worldId, dim, signature, source, halo, visible, true);

        // Regions already displayed remain resident while panning and zooming.
        // Protect images inside the viewport before discarding the least recently used.
        if (images.size() > RETAINED_IMAGES) {
            Set<SavedMapCoverage.Region> protectedRegions = new HashSet<>(visible);
            for (var it = images.entrySet().iterator(); it.hasNext() && images.size() > RETAINED_IMAGES;) {
                var entry = it.next();
                if (entry.getKey().appearance() == signature && protectedRegions.contains(entry.getKey().region())) continue;
                entry.getValue().texture.close();
                it.remove();
            }
        }
    }

    private void queueReads(UUID worldId, String dim, long signature, SavedMapCoverage source,
            boolean halo, List<SavedMapCoverage.Region> visible, boolean refresh) {
        for (var pos : visible) {
            if (reads.size() >= MAX_READS) return;
            Key key = new Key(pos, signature);
            Image image = images.get(key);
            if ((image != null) != refresh || reads.containsKey(key)) continue;
            long revision = source.revision(pos, halo);
            if ((image != null && image.revision == revision)
                    || empty.getOrDefault(key, -1L) == revision) continue;
            reads.put(key, new Read(worldId, dim, key, source, revision, halo, image == null));
        }
    }

    private Image putImage(Key key, int[] pixels) {
        Image image = images.computeIfAbsent(key, ignored -> new Image());
        var buffer = image.texture.getPixels();
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                MapGraphics.writeAbgr(buffer, x, z, pixels[z * SIDE + x]);
            }
        }
        image.texture.upload();
        return image;
    }

    void draw(MapGraphics graphics, InGameMapCamera camera) {
        for (var entry : images.entrySet()) {
            Key key = entry.getKey();
            if (key.appearance() == appearance) {
                var pos = key.region();
                InGameMapTiles.drawAtlas(graphics, camera, pos.x(), pos.z(), entry.getValue().texture);
            }
        }
    }

    @Override public void close() {
        for (Read read : reads.values()) read.cancelled = true;
        reads.clear();
        for (Image image : images.values()) image.texture.close();
        images.clear();
        empty.clear();
    }

    private static int chunk(double coordinate) {
        return (int) Math.floor(coordinate / SurfaceTile.SIZE);
    }

    private static int region(double coordinate) {
        return (int) Math.floor(coordinate / WORLD_SIDE);
    }

    private static long signature(long appearance) {
        return Long.rotateLeft(appearance, 23) ^ OVERVIEW_SALT;
    }
}
