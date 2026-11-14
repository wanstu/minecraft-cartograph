package com.wanstu.cartographv3;

import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public final class MapClientCache {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.client");
    private static final Map<ChunkKey, SurfaceTile> TILES = new HashMap<>();
    private static final Set<ChunkKey> PENDING = new HashSet<>();
    private static final Map<ChunkKey, Integer> MISSING_AT = new HashMap<>();
    private static final Map<ChunkKey, Long> VALIDATED_SESSION = new HashMap<>();
    private static ClientLevel world;
    private static java.util.UUID worldId;
    private static boolean worldInfoRequested;
    private static int ticks;
    private static long firstRequestNanos;
    private static int received;
    private static int validated;
    private static long mapSession;
    private static long tileRevision;

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
        mapSession = 0L;
        tileRevision++;
        ticks = 0;
        firstRequestNanos = 0L;
        received = 0;
        validated = 0;
        if (level != null) requestWorldInfo();
    }

    private static void requestWorldInfo() {
        if (world == null || worldInfoRequested) return;
        worldInfoRequested = true;
        PacketDistributor.sendToServer(new MapProtocol.RequestWorldInfo());
    }

    public static void tick() {
        ticks++;
        if (world != null && worldId == null && ticks % 20 == 0) {
            worldInfoRequested = false;
            requestWorldInfo();
        }
    }

    public static SurfaceTile tile(ChunkKey key) {
        return TILES.get(key);
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
        if (tile == null) return true;
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
            TILES.put(key, restoredTile);
            tileRevision++;
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
            }
            worldId = next;
            LOG.info("V3_WORLD_ID world={} dimension={}", worldId, current);
        } catch (IllegalArgumentException invalid) {
            worldId = null;
            worldInfoRequested = false;
        }
    }

    public static void accept(MapProtocol.Tile packet) {
        if (world == null) return;
        String current = world.dimension().location().toString();
        if (!current.equals(packet.dimension())) return;
        ChunkKey key = new ChunkKey(packet.dimension(), packet.chunkX(), packet.chunkZ());
        PENDING.remove(key);
        if (packet.retry()) {
            return;
        }
        if (!packet.present()) {
            MISSING_AT.put(key, ticks);
            VALIDATED_SESSION.put(key, mapSession);
            if (TILES.remove(key) != null) tileRevision++;
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
        SurfaceTile tile = new SurfaceTile(key, packet.rgb());
        VALIDATED_SESSION.put(key, mapSession);
        TILES.put(key, tile);
        tileRevision++;
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
    static long tileRevision() { return tileRevision; }
}
