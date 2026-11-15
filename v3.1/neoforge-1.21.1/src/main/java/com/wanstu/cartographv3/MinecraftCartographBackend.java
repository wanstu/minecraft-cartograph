package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * NeoForge/client implementation of the stable Cartograph frontend API.
 *
 * This class owns network/cache/request policy. Themes never touch
 * PacketDistributor or MapClientCache directly.
 */
final class MinecraftCartographBackend implements CartographBackendApi {
    private static final int MIN_PENDING_REQUESTS = 32;

    private final Minecraft minecraft = Minecraft.getInstance();

    private Viewport viewport;
    private boolean opened;
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
        MapClientCache.ensureWorld(minecraft.level);
        MapClientCache.beginMapSession();
        PacketDistributor.sendToServer(new MapProtocol.MapViewState(
                true, MapUiSettings.liveScanIntervalTicks()));
    }

    @Override
    public void tick() {
        if (!opened) return;
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
                : minecraft.level.dimension().location().toString();
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
    public void updateViewport(Viewport viewport, boolean force) {
        this.viewport = viewport;
        if (!opened || viewport == null) return;
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
            var registry = minecraft.level.registryAccess().registryOrThrow(
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
        PacketDistributor.sendToServer(new MapProtocol.TeleportRequest(
                dimension(), blockX, blockZ));
    }

    @Override
    public void setLiveScanInterval(int ticks) {
        MapUiSettings.setLiveScanIntervalTicks(ticks);
        if (opened) {
            PacketDistributor.sendToServer(new MapProtocol.MapViewState(true, ticks));
        }
    }

    @Override
    public void close() {
        if (!opened) return;
        opened = false;
        PacketDistributor.sendToServer(new MapProtocol.MapViewState(
                false, MapUiSettings.liveScanIntervalTicks()));
        viewport = null;
    }

    private int maxPendingRequests() {
        return Math.max(MIN_PENDING_REQUESTS, MapUiSettings.requestBatchSize() * 2);
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

        boolean viewportChanged =
                minChunkX != lastRequestMinChunkX
                        || maxChunkX != lastRequestMaxChunkX
                        || minChunkZ != lastRequestMinChunkZ
                        || maxChunkZ != lastRequestMaxChunkZ
                        || centerChunkX != requestCenterChunkX
                        || centerChunkZ != requestCenterChunkZ;

        if (!force && !viewportChanged) return;

        if (viewportChanged) {
            MapClientCache.restoreVisibleFromDisk(
                    dim, minChunkX, maxChunkX, minChunkZ, maxChunkZ);

            lastRequestMinChunkX = minChunkX;
            lastRequestMaxChunkX = maxChunkX;
            lastRequestMinChunkZ = minChunkZ;
            lastRequestMaxChunkZ = maxChunkZ;
            requestCenterChunkX = centerChunkX;
            requestCenterChunkZ = centerChunkZ;
            requestRing = 0;
            requestRingOffset = 0;
        }

        int maxRadius = Math.max(
                Math.max(
                        Math.abs(minChunkX - centerChunkX),
                        Math.abs(maxChunkX - centerChunkX)),
                Math.max(
                        Math.abs(minChunkZ - centerChunkZ),
                        Math.abs(maxChunkZ - centerChunkZ)));

        int requestBatch = MapUiSettings.requestBatchSize();
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
            if (x < minChunkX || x > maxChunkX
                    || z < minChunkZ || z > maxChunkZ) {
                continue;
            }

            ChunkKey key = new ChunkKey(dim, x, z);
            if (!MapClientCache.needsRequest(key)) continue;
            MapClientCache.request(key);
            requested++;
        }
    }
}
