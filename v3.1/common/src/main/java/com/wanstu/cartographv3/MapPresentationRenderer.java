package com.wanstu.cartographv3;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/** Resolves drawing mode first, then delegates presentation to the active visual style. */
final class MapPresentationRenderer {
    private static final long PIPELINE_VERSION = 7L;
    private static final int FACT_FINGERPRINT_CACHE_LIMIT = 16384;
    private static final Map<SurfaceTile, Long> FACT_FINGERPRINT_CACHE =
            new IdentityHashMap<>();

    private record DerivedKey(ChunkKey key, long facts, long resources) {}
    private static final Map<DerivedKey, HandDrawnMapData> HAND_DRAWN_DATA =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(128, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<DerivedKey, HandDrawnMapData> entry) {
                    return size() > 1024;
                }
            });
    private static long handDrawnBuilds;

    private MapPresentationRenderer() {}

    static void beginMapSession() {
        HAND_DRAWN_DATA.clear();
        synchronized (FACT_FINGERPRINT_CACHE) {
            FACT_FINGERPRINT_CACHE.clear();
        }
        HandDrawnMapData.beginMapSession();
        SatelliteSurfaceRenderer.beginMapSession();
    }

    static MapVisualStyleProcessor.Palette palette() {
        return CartographSettings.visualStyle().processor().palette();
    }

    static long renderSignature() {
        long base = SatelliteSurfaceRenderer.renderSignature();
        long mode = CartographSettings.drawingMode().ordinal() + 1L;
        long style = Integer.toUnsignedLong(CartographSettings.visualStyle().id().hashCode());
        long mixed = base ^ (PIPELINE_VERSION * 0x9e3779b97f4a7c15L);
        mixed ^= mode * 0xbf58476d1ce4e5b9L;
        mixed ^= style * 0x94d049bb133111ebL;
        return mixed == 0L ? 1L : mixed;
    }

    static SurfaceTile render(SurfaceTile tile) {
        return render(tile, ignored -> null);
    }

    static SurfaceTile render(
            SurfaceTile tile,
            Function<ChunkKey, SurfaceTile> tileLookup) {
        if (!tile.hasSurfaceFacts()) return tile;
        return tile.withRgb(renderPixels(tile, 1, tileLookup));
    }

    static int[] renderPixels(SurfaceTile tile, int stride) {
        return renderPixels(tile, stride, ignored -> null);
    }

    static int[] renderPixels(
            SurfaceTile tile,
            int stride,
            Function<ChunkKey, SurfaceTile> tileLookup) {
        MapVisualStyleProcessor processor = CartographSettings.visualStyle().processor();
        return switch (CartographSettings.drawingMode()) {
            case SATELLITE -> processor.renderSatellite(SatelliteMapData.from(tile), stride);
            case HAND_DRAWN -> processor.renderHandDrawn(
                    handDrawnData(tile, tileLookup), stride);
        };
    }

    static int overviewColor(SurfaceTile tile, Function<ChunkKey, SurfaceTile> neighbors) {
        MapVisualStyleProcessor processor = CartographSettings.visualStyle().processor();
        return switch (CartographSettings.drawingMode()) {
            case SATELLITE -> processor.renderSatellite(SatelliteMapData.from(tile), SurfaceTile.SIZE)[0];
            case HAND_DRAWN -> processor.renderHandDrawn(
                    HandDrawnMapData.from(tile, neighbors), SurfaceTile.SIZE)[0];
        };
    }

    private static HandDrawnMapData handDrawnData(
            SurfaceTile tile, Function<ChunkKey, SurfaceTile> tileLookup) {
        DerivedKey key = new DerivedKey(tile.key(), dependencyFingerprint(tile, tileLookup),
                SatelliteSurfaceRenderer.renderSignature());
        HandDrawnMapData cached = HAND_DRAWN_DATA.get(key);
        if (cached != null) return cached;
        HandDrawnMapData derived = HandDrawnMapData.from(tile, tileLookup);
        handDrawnBuilds++;
        HAND_DRAWN_DATA.put(key, derived);
        return derived;
    }

    /**
     * The hand-drawn relief of one chunk consumes a one-chunk semantic halo.
     * Include that halo in the presentation dependency so a neighboring chunk
     * appearing or changing invalidates the rendered edge instead of leaving a
     * stale seam. Satellite mode remains strictly chunk-local.
     */
    static long dependencyFingerprint(
            SurfaceTile tile,
            Function<ChunkKey, SurfaceTile> tileLookup) {
        long center = factFingerprint(tile);
        if (CartographSettings.drawingMode() != MapDrawingMode.HAND_DRAWN) return center;

        ChunkKey key = tile.key();
        long mixed = center ^ 0x6a09e667f3bcc909L;
        int ordinal = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                SurfaceTile sample;
                if (dx == 0 && dz == 0) {
                    sample = tile;
                } else {
                    sample = tileLookup.apply(new ChunkKey(
                            key.dimension(), key.x() + dx, key.z() + dz));
                }
                long fingerprint = sample != null && sample.hasSurfaceFacts()
                        ? factFingerprint(sample) : 0L;
                long salted = fingerprint
                        ^ (0x9e3779b97f4a7c15L * (ordinal + 1L));
                mixed ^= Long.rotateLeft(salted, (ordinal * 7 + 11) & 63);
                mixed *= 0xbf58476d1ce4e5b9L;
                ordinal++;
            }
        }
        return mixed == 0L ? 1L : mixed;
    }

    private static long factFingerprint(SurfaceTile tile) {
        synchronized (FACT_FINGERPRINT_CACHE) {
            Long cached = FACT_FINGERPRINT_CACHE.get(tile);
            if (cached != null) return cached;
            if (FACT_FINGERPRINT_CACHE.size() >= FACT_FINGERPRINT_CACHE_LIMIT) {
                FACT_FINGERPRINT_CACHE.clear();
            }
            long fingerprint = tile.fingerprint64();
            FACT_FINGERPRINT_CACHE.put(tile, fingerprint);
            return fingerprint;
        }
    }
}
