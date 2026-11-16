package com.wanstu.cartographv3;

import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class MapClientCache {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.client");
    private static final Map<ChunkKey, SurfaceTile> TILES = new HashMap<>();
    private static final Set<ChunkKey> PENDING = new HashSet<>();
    private static final Map<ChunkKey, Integer> MISSING_AT = new HashMap<>();
    private static final Map<ChunkKey, Long> VALIDATED_SESSION = new HashMap<>();
    private record RegionKey(String dimension, int x, int z) {}
    private static final Map<RegionKey, Integer> REGION_TILE_COUNTS = new HashMap<>();
    private static final Map<RegionKey, Long> REGION_REVISIONS = new HashMap<>();
    private static final java.util.concurrent.CopyOnWriteArrayList<
            java.util.function.Consumer<ChunkKey>> TILE_CHANGE_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private record DiskRestore(
            java.util.UUID worldId, long generation, SurfaceTile tile) {}
    private static Set<ChunkKey> DISK_RESTORE_SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<DiskRestore> DISK_RESTORE_QUEUE =
            new ConcurrentLinkedQueue<>();
    private static final int MAX_DISK_RESTORES_PER_TICK = 512;

    private static ClientLevel world;
    private static java.util.UUID worldId;
    private static boolean worldInfoRequested;
    private static int ticks;
    private static long firstRequestNanos;
    private static int received;
    private static int validated;
    private static long mapSession;
    private static long regionRevisionCounter;
    private static long diskRestoreGeneration;

    private MapClientCache() {}

    public static void ensureWorld(ClientLevel level) {
        if (level == world) return;
        world = level;
        worldId = null;
        worldInfoRequested = false;
        TILES.clear();
        PENDING.clear();
        MISSING_AT.clear();
        VALIDATED_SESSION.clear();
        REGION_TILE_COUNTS.clear();
        REGION_REVISIONS.clear();
        resetDiskRestores();
        mapSession = 0L;
        regionRevisionCounter++;
        ticks = 0;
        firstRequestNanos = 0L;
        received = 0;
        validated = 0;
        MapMarkerStore.ensureWorld(null);
        if (level != null) requestWorldInfo();
    }

    private static void requestWorldInfo() {
        if (world == null || worldInfoRequested) return;
        worldInfoRequested = true;
        PacketDistributor.sendToServer(new MapProtocol.RequestWorldInfo());
    }

    public static void tick() {
        ticks++;
        drainDiskRestores();
        if (world != null && worldId == null && ticks % 20 == 0) {
            worldInfoRequested = false;
            requestWorldInfo();
        }
    }

    public static SurfaceTile tile(ChunkKey key) {
        return TILES.get(key);
    }

    public static void restoreVisibleFromDisk(
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ) {
        java.util.UUID currentWorldId = worldId;
        if (world == null || currentWorldId == null || dimension == null || dimension.isBlank()) {
            return;
        }
        Set<ChunkKey> scheduled = DISK_RESTORE_SCHEDULED;
        long generation = diskRestoreGeneration;
        PersistentTileStore.loadVisibleAsync(
                currentWorldId,
                dimension,
                minChunkX, maxChunkX,
                minChunkZ, maxChunkZ,
                scheduled,
                (restoreWorldId, tile) ->
                        DISK_RESTORE_QUEUE.add(
                                new DiskRestore(restoreWorldId, generation, tile)));
    }

    private static void preloadCurrentDimensionFromDisk(String dimension) {
        java.util.UUID currentWorldId = worldId;
        if (world == null || currentWorldId == null
                || dimension == null || dimension.isBlank()) {
            return;
        }

        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            int centerChunkX = Math.floorDiv((int) Math.floor(player.getX()), 16);
            int centerChunkZ = Math.floorDiv((int) Math.floor(player.getZ()), 16);
            int radius = 1024;
            Set<ChunkKey> scheduled = DISK_RESTORE_SCHEDULED;
            long generation = diskRestoreGeneration;
            PersistentTileStore.loadVisibleAsync(
                    currentWorldId,
                    dimension,
                    centerChunkX - radius, centerChunkX + radius,
                    centerChunkZ - radius, centerChunkZ + radius,
                    scheduled,
                    (restoreWorldId, tile) ->
                            DISK_RESTORE_QUEUE.add(
                                    new DiskRestore(restoreWorldId, generation, tile)));
        }

    }

    private static void resetDiskRestores() {
        diskRestoreGeneration++;
        DISK_RESTORE_SCHEDULED = ConcurrentHashMap.newKeySet();
        DISK_RESTORE_QUEUE.clear();
    }

    private static void drainDiskRestores() {
        int restored = 0;
        while (restored < MAX_DISK_RESTORES_PER_TICK) {
            DiskRestore next = DISK_RESTORE_QUEUE.poll();
            if (next == null) break;
            if (!Objects.equals(worldId, next.worldId())
                    || next.generation() != diskRestoreGeneration) {
                continue;
            }

            SurfaceTile raw = next.tile();
            ChunkKey key = raw.key();
            if (MISSING_AT.containsKey(key) || TILES.containsKey(key)) continue;

            SurfaceTile tile = raw;
            TILES.put(key, tile);
            addRegionTile(key);
            markRegionChanged(key);
            restored++;

            int total = TILES.size();
            if (total == 1 || total % 512 == 0) {
                LOG.info("V3_MAP_CACHE_RESTORE restored={} example={},{}",
                        total, key.x(), key.z());
            }
        }
    }

    public static void beginMapSession() {
        mapSession++;
        validated = 0;
        firstRequestNanos = 0L;
        received = 0;
    }

    public static boolean needsRequest(ChunkKey key) {
        if (world == null || worldId == null || PENDING.contains(key)) return false;
        SurfaceTile tile = TILES.get(key);
        if (tile == null) return !DISK_RESTORE_SCHEDULED.contains(key);
        return VALIDATED_SESSION.getOrDefault(key, Long.MIN_VALUE) != mapSession;
    }

    public static void request(ChunkKey key) {
        if (world == null || worldId == null || PENDING.contains(key)) return;

        SurfaceTile inMemory = TILES.get(key);
        if (inMemory != null) {
            if (VALIDATED_SESSION.getOrDefault(key, Long.MIN_VALUE) == mapSession) return;
            PENDING.add(key);
            PacketDistributor.sendToServer(new MapProtocol.RequestTile(
                    key.dimension(), key.x(), key.z(), true, inMemory.fingerprint64()));
            return;
        }

        java.util.Optional<SurfaceTile> disk = PersistentTileStore.load(worldId, key);
        if (disk.isPresent()) {
            SurfaceTile restoredTile = disk.get();
            if (TILES.put(key, restoredTile) == null) addRegionTile(key);
            markRegionChanged(key);
            int restored = TILES.size();
            if (restored == 1 || restored % 64 == 0)
                LOG.info("V3_MAP_CACHE_RESTORE restored={} example={},{}",
                        restored, key.x(), key.z());
            PENDING.add(key);
            PacketDistributor.sendToServer(new MapProtocol.RequestTile(
                    key.dimension(), key.x(), key.z(), true, restoredTile.fingerprint64()));
            return;
        }
        Integer missing = MISSING_AT.get(key);
        if (missing != null && ticks - missing < 40) return;
        PENDING.add(key);
        if (firstRequestNanos == 0L) firstRequestNanos = System.nanoTime();
        PacketDistributor.sendToServer(new MapProtocol.RequestTile(
                key.dimension(), key.x(), key.z(), false, 0L));
    }

    public static void accept(MapProtocol.WorldInfo info) {
        if (world == null) return;
        String current = world.dimension().location().toString();
        if (!current.equals(info.dimension())) return;
        try {
            java.util.UUID next = java.util.UUID.fromString(info.worldId());
            if (worldId != null && !worldId.equals(next)) {
                TILES.clear();
                PENDING.clear();
                MISSING_AT.clear();
                VALIDATED_SESSION.clear();
                REGION_TILE_COUNTS.clear();
                REGION_REVISIONS.clear();
                resetDiskRestores();
                regionRevisionCounter++;
            }
            worldId = next;
            MapMarkerStore.ensureWorld(worldId);
            preloadCurrentDimensionFromDisk(current);
            LOG.info("V3_WORLD_ID world={} dimension={} cache_preload=started",
                    worldId, current);
        } catch (IllegalArgumentException invalid) {
            worldId = null;
            MapMarkerStore.ensureWorld(null);
            worldInfoRequested = false;
        }
    }

    public static void accept(MapProtocol.Tile packet) {
        if (world == null) return;
        String current = world.dimension().location().toString();
        if (!current.equals(packet.dimension())) return;
        ChunkKey key = new ChunkKey(packet.dimension(), packet.chunkX(), packet.chunkZ());
        PENDING.remove(key);
        if (worldId != null && packet.structures() != null && !packet.structures().isEmpty()) {
            MapMarkerStore.ensureWorld(worldId);
            MapMarkerStore.acceptAutomatic(packet.dimension(), packet.structures());
        }
        if (packet.retry()) {
            return;
        }
        if (!packet.present()) {
            MISSING_AT.put(key, ticks);
            VALIDATED_SESSION.put(key, mapSession);
            if (TILES.remove(key) != null) {
                removeRegionTile(key);
                markRegionChanged(key);
            }
            if (worldId != null) PersistentTileStore.delete(worldId, key);
            return;
        }
        MISSING_AT.remove(key);
        if (!packet.changed()) {
            VALIDATED_SESSION.put(key, mapSession);
            validated++;
            if (validated == 1 || validated % 64 == 0)
                LOG.info("V3_MAP_CACHE_VALID validated={} cached={} pending={} example={},{}",
                        validated, TILES.size(), PENDING.size(), key.x(), key.z());
            return;
        }
        SurfaceTile tile = SurfaceTile.fromFacts(
                key,
                packet.stateIds(), packet.biomeIds(),
                packet.surfaceHeights(), packet.reliefHeights(), packet.waterDepths());
        VALIDATED_SESSION.put(key, mapSession);
        if (TILES.put(key, tile) == null) addRegionTile(key);
        markRegionChanged(key);
        if (worldId != null) PersistentTileStore.save(worldId, tile);
        received++;
        if (received == 1 || received % 64 == 0) {
            long elapsed = firstRequestNanos == 0L ? -1L
                    : (System.nanoTime() - firstRequestNanos) / 1_000_000L;
            LOG.info("V3_MAP_TILE received={} cached={} pending={} elapsed_ms={} example={},{}",
                    received, TILES.size(), PENDING.size(), elapsed, key.x(), key.z());
        }
    }

    public static int cachedCount() { return TILES.size(); }
    public static int pendingCount() { return PENDING.size(); }

    static List<ChunkKey> keysInBounds(
            String dimension,
            int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ) {
        if (dimension == null || dimension.isBlank()) return List.of();
        List<ChunkKey> result = new ArrayList<>();
        for (ChunkKey key : TILES.keySet()) {
            if (!dimension.equals(key.dimension())) continue;
            if (key.x() < minChunkX || key.x() > maxChunkX
                    || key.z() < minChunkZ || key.z() > maxChunkZ) {
                continue;
            }
            result.add(key);
        }
        return result;
    }

    static java.util.UUID worldId() { return worldId; }

    static boolean hasTilesInRegion(String dimension, int regionX, int regionZ) {
        return REGION_TILE_COUNTS.getOrDefault(
                new RegionKey(dimension, regionX, regionZ), 0) > 0;
    }

    private static void addRegionTile(ChunkKey key) {
        REGION_TILE_COUNTS.merge(regionKey(key), 1, Integer::sum);
    }

    private static void removeRegionTile(ChunkKey key) {
        RegionKey region = regionKey(key);
        REGION_TILE_COUNTS.computeIfPresent(
                region, (ignored, count) -> count <= 1 ? null : count - 1);
    }

    private static RegionKey regionKey(ChunkKey key) {
        return new RegionKey(
                key.dimension(),
                Math.floorDiv(key.x(), 16),
                Math.floorDiv(key.z(), 16));
    }

    static void addTileChangeListener(java.util.function.Consumer<ChunkKey> listener) {
        if (listener != null) TILE_CHANGE_LISTENERS.addIfAbsent(listener);
    }

    static void removeTileChangeListener(java.util.function.Consumer<ChunkKey> listener) {
        TILE_CHANGE_LISTENERS.remove(listener);
    }

    private static void markRegionChanged(ChunkKey key) {
        REGION_REVISIONS.put(regionKey(key), ++regionRevisionCounter);
        for (var listener : TILE_CHANGE_LISTENERS) {
            try {
                listener.accept(key);
            } catch (RuntimeException failure) {
                LOG.debug("Cartograph tile listener failed", failure);
            }
        }
    }

    static long regionRevision(String dimension, int regionX, int regionZ) {
        return REGION_REVISIONS.getOrDefault(
                new RegionKey(dimension, regionX, regionZ), 0L);
    }
}
