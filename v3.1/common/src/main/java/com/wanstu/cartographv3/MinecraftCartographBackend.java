package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * NeoForge/client implementation of the stable Cartograph frontend API.
 *
 * This class owns network/cache/request policy. Themes never touch
 * MapTransport or MapClientCache directly.
 */
final class MinecraftCartographBackend implements CartographBackendApi {
    private static final int MIN_PENDING_REQUESTS = 32;
    private static final int MAX_RAW_VISIBLE_TILES = 4096;
    private static final int MAX_RAW_VISIBLE_RADIUS = 31;

    private final Minecraft minecraft = Minecraft.getInstance();

    private Viewport viewport;
    private boolean opened;
    private boolean viewStateSent;
    private String viewStateDimension = "";
    private int requestTicks;

    private int lastRequestMinChunkX = Integer.MIN_VALUE;
    private int lastRequestMaxChunkX = Integer.MIN_VALUE;
    private int lastRequestMinChunkZ = Integer.MIN_VALUE;
    private int lastRequestMaxChunkZ = Integer.MIN_VALUE;
    private int requestCenterChunkX = Integer.MIN_VALUE;
    private int requestCenterChunkZ = Integer.MIN_VALUE;
    private int requestRing;
    private int requestRingOffset;

    @Override
    public void open() {
        if (opened) return;
        opened = true;
        viewStateSent = false;
        viewStateDimension = "";
        MapClientCache.ensureWorld(minecraft.level);
        MapClientCache.beginMapSession();
        ensureViewState();
    }

    @Override
    public void tick() {
        if (!opened) return;
        ensureViewState();
        if (!viewStateSent) return;
        requestTicks++;
        if (viewport == null || viewport.dragging()) return;
        int pendingLimit = maxPendingRequests();
        if (MapClientCache.pendingCount() < pendingLimit) {
            requestVisible(requestTicks % 5 == 0);
        }
    }

    @Override
    public String dimension() {
        return minecraft.level == null ? ""
                : MapGameAccess.dimension(minecraft.level);
    }

    @Override
    public UUID worldId() {
        return MapClientCache.worldId();
    }

    @Override
    public SurfaceTile tile(ChunkKey key) {
        return MapClientCache.tile(key);
    }

    @Override
    public boolean hasTilesInRegion(String dimension, int regionX, int regionZ) {
        return MapClientCache.hasTilesInRegion(dimension, regionX, regionZ);
    }

    @Override
    public long regionRevision(String dimension, int regionX, int regionZ) {
        return MapClientCache.regionRevision(dimension, regionX, regionZ);
    }

    @Override
    public int cachedCount() {
        return MapClientCache.cachedCount();
    }

    @Override
    public int pendingCount() {
        return MapClientCache.pendingCount();
    }

    @Override
    public List<ChunkKey> cachedKeysInBounds(
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ) {
        return MapClientCache.keysInBounds(
                dimension,
                minChunkX, maxChunkX,
                minChunkZ, maxChunkZ);
    }

    @Override
    public void updateViewport(Viewport viewport, boolean force) {
        this.viewport = viewport;
        if (!opened || viewport == null) return;
        ensureViewState();
        if (!viewStateSent) return;
        requestVisible(force);
    }

    @Override
    public PlayerState player() {
        if (minecraft.player == null) return PlayerState.absent();
        return new PlayerState(
                true,
                minecraft.player.getX(),
                minecraft.player.getZ(),
                minecraft.player.getBlockY(),
                minecraft.player.getYRot());
    }

    @Override
    public int surfaceYAt(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        SurfaceTile tile = tile(new ChunkKey(dimension(), chunkX, chunkZ));
        if (tile != null && tile.hasSurfaceFacts()) {
            int localX = Math.floorMod(blockX, 16);
            int localZ = Math.floorMod(blockZ, 16);
            int value = tile.surfaceHeightAt(localZ * 16 + localX);
            if (value != SurfaceTile.UNKNOWN_HEIGHT) return value;
        }
        PlayerState player = player();
        return player.present() ? player.y() : 64;
    }

    @Override
    public String biomeLabelAt(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        SurfaceTile tile = tile(new ChunkKey(dimension(), chunkX, chunkZ));
        if (tile == null || !tile.hasSurfaceFacts()) return "群系：未加载";
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        int biomeId = tile.biomeIdAt(localZ * 16 + localX);
        if (biomeId < 0 || minecraft.level == null) return "群系：未加载";
        try {
            var registry = MapGameAccess.registry(minecraft.level.registryAccess(),
                    net.minecraft.core.registries.Registries.BIOME);
            var biome = registry.byId(biomeId);
            if (biome == null) return "群系 #" + biomeId;
            var key = registry.getKey(biome);
            if (key == null) return "群系 #" + biomeId;
            return "群系：" + Component.translatable(
                    "biome." + key.getNamespace() + "." + key.getPath()).getString();
        } catch (RuntimeException ignored) {
            return "群系 #" + biomeId;
        }
    }

    @Override
    public List<MapMarkerStore.Marker> markers() {
        return MapMarkerStore.visible(dimension());
    }

    @Override
    public MapMarkerStore.Marker createMarker(
            int x, int y, int z,
            String name, MapMarkerStore.Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description) {
        return MapMarkerStore.createCustom(
                dimension(), x, y, z,
                name, icon,
                primaryColor, accentColor, lineColor,
                description);
    }

    @Override
    public void updateMarker(
            UUID id, int x, int y, int z,
            String name, MapMarkerStore.Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description) {
        MapMarkerStore.update(
                id, x, y, z,
                name, icon,
                primaryColor, accentColor, lineColor,
                description);
    }

    @Override
    public void deleteMarker(UUID id) {
        MapMarkerStore.delete(id);
    }

    @Override
    public void resetAutomaticMarker(UUID id) {
        MapMarkerStore.resetAutomatic(id);
    }

    @Override
    public void teleport(int blockX, int blockZ) {
        MapTransport.sendToServer(new MapProtocol.TeleportRequest(
                dimension(), blockX, blockZ));
    }

    @Override
    public void setLiveScanInterval(int ticks) {
        CartographSettings.setLiveScanIntervalTicks(ticks);
        if (opened) {
            ensureViewState();
            if (viewStateSent) {
                String dim = dimension();
                MapTransport.sendToServer(new MapProtocol.MapViewState(
                        true, ticks, MapClientCache.backgroundCheckpoint(dim)));
            }
        }
    }

    @Override
    public void close() {
        if (!opened) return;
        opened = false;
        viewport = null;

        var connection = minecraft.getConnection();
        if (connection != null && connection.getConnection().isConnected() && viewStateSent) {
            MapTransport.sendToServer(new MapProtocol.MapViewState(
                    false, CartographSettings.liveScanIntervalTicks(), 0L));
        }
        viewStateSent = false;
        viewStateDimension = "";
    }

    private void ensureViewState() {
        if (!opened || minecraft.level == null || MapClientCache.worldId() == null) return;
        String dim = dimension();
        if (dim.isBlank()) return;
        if (viewStateSent && dim.equals(viewStateDimension)) return;

        MapTransport.sendToServer(new MapProtocol.MapViewState(
                true,
                CartographSettings.liveScanIntervalTicks(),
                MapClientCache.backgroundCheckpoint(dim)));
        viewStateSent = true;
        viewStateDimension = dim;
    }

    private int maxPendingRequests() {
        return Math.max(MIN_PENDING_REQUESTS, CartographSettings.requestBatchSize() * 2);
    }

    private void requestVisible(boolean force) {
        if (viewport == null
                || minecraft.level == null
                || MapClientCache.worldId() == null) {
            return;
        }

        String dim = dimension();
        double halfBlocksX = viewport.width() / (2.0 * viewport.zoom());
        double halfBlocksZ = viewport.height() / (2.0 * viewport.zoom());

        int minChunkX = Math.floorDiv(
                (int) Math.floor(viewport.centerX() - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv(
                (int) Math.floor(viewport.centerX() + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv(
                (int) Math.floor(viewport.centerZ() - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv(
                (int) Math.floor(viewport.centerZ() + halfBlocksZ), 16) + 1;

        int centerChunkX = Math.floorDiv((int) Math.floor(viewport.centerX()), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(viewport.centerZ()), 16);

        long visibleTiles = (long) (maxChunkX - minChunkX + 1)
                * (long) (maxChunkZ - minChunkZ + 1);
        int rawMinChunkX = minChunkX;
        int rawMaxChunkX = maxChunkX;
        int rawMinChunkZ = minChunkZ;
        int rawMaxChunkZ = maxChunkZ;
        if (visibleTiles > MAX_RAW_VISIBLE_TILES) {
            rawMinChunkX = Math.max(minChunkX, centerChunkX - MAX_RAW_VISIBLE_RADIUS);
            rawMaxChunkX = Math.min(maxChunkX, centerChunkX + MAX_RAW_VISIBLE_RADIUS);
            rawMinChunkZ = Math.max(minChunkZ, centerChunkZ - MAX_RAW_VISIBLE_RADIUS);
            rawMaxChunkZ = Math.min(maxChunkZ, centerChunkZ + MAX_RAW_VISIBLE_RADIUS);
        }

        boolean viewportChanged =
                rawMinChunkX != lastRequestMinChunkX
                        || rawMaxChunkX != lastRequestMaxChunkX
                        || rawMinChunkZ != lastRequestMinChunkZ
                        || rawMaxChunkZ != lastRequestMaxChunkZ
                        || centerChunkX != requestCenterChunkX
                        || centerChunkZ != requestCenterChunkZ;

        if (!force && !viewportChanged) return;

        if (viewportChanged) {
            MapClientCache.restoreVisibleFromDisk(
                    dim, rawMinChunkX, rawMaxChunkX, rawMinChunkZ, rawMaxChunkZ);

            lastRequestMinChunkX = rawMinChunkX;
            lastRequestMaxChunkX = rawMaxChunkX;
            lastRequestMinChunkZ = rawMinChunkZ;
            lastRequestMaxChunkZ = rawMaxChunkZ;
            requestCenterChunkX = centerChunkX;
            requestCenterChunkZ = centerChunkZ;
            requestRing = 0;
            requestRingOffset = 0;
        }

        int maxRadius = Math.max(
                Math.max(
                        Math.abs(rawMinChunkX - centerChunkX),
                        Math.abs(rawMaxChunkX - centerChunkX)),
                Math.max(
                        Math.abs(rawMinChunkZ - centerChunkZ),
                        Math.abs(rawMaxChunkZ - centerChunkZ)));

        int requestBatch = CartographSettings.requestBatchSize();
        int requestBudget = Math.min(
                requestBatch,
                Math.max(0, maxPendingRequests() - MapClientCache.pendingCount()));
        if (requestBudget <= 0) return;

        int requested = 0;
        int scanned = 0;
        int scanBudget = requestBatch * 8;

        while (requested < requestBudget
                && scanned < scanBudget
                && requestRing <= maxRadius) {
            int dx;
            int dz;

            if (requestRing == 0) {
                dx = 0;
                dz = 0;
                requestRing = 1;
                requestRingOffset = 0;
            } else {
                int r = requestRing;
                int side = r * 2;
                int perimeter = r * 8;
                int p = requestRingOffset++;

                if (p < side) {
                    dx = -r + p;
                    dz = -r;
                } else if (p < side * 2) {
                    dx = r;
                    dz = -r + (p - side);
                } else if (p < side * 3) {
                    dx = r - (p - side * 2);
                    dz = r;
                } else {
                    dx = -r;
                    dz = r - (p - side * 3);
                }

                if (requestRingOffset >= perimeter) {
                    requestRing++;
                    requestRingOffset = 0;
                }
            }

            scanned++;
            int x = centerChunkX + dx;
            int z = centerChunkZ + dz;
            if (x < rawMinChunkX || x > rawMaxChunkX
                    || z < rawMinChunkZ || z > rawMaxChunkZ) {
                continue;
            }

            ChunkKey key = new ChunkKey(dim, x, z);
            if (!MapClientCache.needsRequest(key)) continue;
            MapClientCache.request(key);
            requested++;
        }
    }
}
