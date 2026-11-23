package com.wanstu.cartographv3;

import java.util.List;
import java.util.UUID;

/**
 * Stable boundary consumed by Cartograph frontends/themes.
 *
 * Implementations own world/network/cache details. Frontends must only consume
 * world facts and invoke semantic actions through this interface.
 */
interface CartographBackendApi extends AutoCloseable {
    int REGION_CHUNKS = 16;

    record Viewport(
            double centerX, double centerZ, double zoom,
            int width, int height,
            boolean dragging) {}

    record PlayerState(
            boolean present,
            double x, double z,
            int y,
            float yaw) {
        static PlayerState absent() {
            return new PlayerState(false, 0.0, 0.0, 64, 0.0f);
        }
    }

    void open();

    void tick();

    String dimension();

    UUID worldId();

    SurfaceTile tile(ChunkKey key);

    boolean hasTilesInRegion(String dimension, int regionX, int regionZ);

    long regionRevision(String dimension, int regionX, int regionZ);

    int cachedCount();

    int pendingCount();

    List<ChunkKey> cachedKeysInBounds(
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ);

    void updateViewport(Viewport viewport, boolean force);

    PlayerState player();

    int surfaceYAt(int blockX, int blockZ);

    String biomeLabelAt(int blockX, int blockZ);

    List<MapMarkerStore.Marker> markers();

    MapMarkerStore.Marker createMarker(
            int x, int y, int z,
            String name, MapMarkerStore.Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description);

    void updateMarker(
            UUID id, int x, int y, int z,
            String name, MapMarkerStore.Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description);

    void deleteMarker(UUID id);

    void resetAutomaticMarker(UUID id);

    void teleport(int blockX, int blockZ);

    void setLiveScanInterval(int ticks);

    @Override
    void close();
}
