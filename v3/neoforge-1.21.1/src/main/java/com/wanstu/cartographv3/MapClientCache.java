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
    private static ClientLevel world;
    private static int ticks;
    private static long firstRequestNanos;
    private static int received;

    private MapClientCache() {}

    public static void ensureWorld(ClientLevel level) {
        if (level == world) return;
        world = level;
        TILES.clear();
        PENDING.clear();
        MISSING_AT.clear();
        ticks = 0;
        firstRequestNanos = 0L;
        received = 0;
    }

    public static void tick() { ticks++; }

    public static SurfaceTile tile(ChunkKey key) {
        return TILES.get(key);
    }

    public static boolean hasOrPending(ChunkKey key) {
        return TILES.containsKey(key) || PENDING.contains(key);
    }

    public static void request(ChunkKey key) {
        if (world == null || PENDING.contains(key) || TILES.containsKey(key)) return;
        Integer missing = MISSING_AT.get(key);
        if (missing != null && ticks - missing < 40) return;
        PENDING.add(key);
        if (firstRequestNanos == 0L) firstRequestNanos = System.nanoTime();
        PacketDistributor.sendToServer(new MapProtocol.RequestTile(
                key.dimension(), key.x(), key.z()));
    }

    public static void accept(MapProtocol.Tile packet) {
        if (world == null) return;
        String current = world.dimension().location().toString();
        if (!current.equals(packet.dimension())) return;
        ChunkKey key = new ChunkKey(packet.dimension(), packet.chunkX(), packet.chunkZ());
        PENDING.remove(key);
        if (!packet.present()) {
            MISSING_AT.put(key, ticks);
            return;
        }
        MISSING_AT.remove(key);
        TILES.put(key, new SurfaceTile(key, packet.rgb()));
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
}
