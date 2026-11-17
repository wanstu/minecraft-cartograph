package com.wanstu.cartographv3;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-tick budget for map tile requests.
 *
 * Client requestBatchSize controls network/prefetch breadth, not how much
 * synchronous surface sampling is allowed to execute in one server tick.
 * This separation protects entity AI, TNT and normal simulation from a burst
 * of map requests.
 */
final class TileRequestScheduler {
    private static final int MAX_QUEUED_PER_PLAYER = 1024;
    private static final int MAX_STARTED_PER_TICK = 32;
    private static final int MAX_STARTED_PER_PLAYER_PER_TICK = 8;
    private static final int MAX_QUEUE_SCAN_PER_PLAYER_PER_TICK = 64;
    private static final int MAX_LOADED_CAPTURES_PER_TICK = 2;

    private record RequestKey(String dimension, int x, int z) {}

    private static final class PlayerQueue {
        final LinkedHashMap<RequestKey, MapProtocol.RequestTile> pending =
                new LinkedHashMap<>();
        final java.util.Set<RequestKey> inFlight = new java.util.HashSet<>();
    }

    private static final Map<UUID, PlayerQueue> QUEUES = new LinkedHashMap<>();
    private static final java.util.Set<UUID> OPEN_PLAYERS = new java.util.HashSet<>();
    private static int playerCursor;

    private TileRequestScheduler() {}

    static void open(ServerPlayer player) {
        if (player == null) return;
        OPEN_PLAYERS.add(player.getUUID());
        QUEUES.computeIfAbsent(player.getUUID(), ignored -> new PlayerQueue());
        GeneratedChunkBackfill.open(player);
    }

    static void enqueue(ServerPlayer player, MapProtocol.RequestTile request) {
        if (player == null || request == null) return;
        if (!OPEN_PLAYERS.contains(player.getUUID())) {
            sendRetry(player, request);
            return;
        }

        String current = player.serverLevel().dimension().location().toString();
        if (!current.equals(request.dimension())) {
            sendRetry(player, request);
            return;
        }

        PlayerQueue queue = QUEUES.computeIfAbsent(
                player.getUUID(), ignored -> new PlayerQueue());
        RequestKey key = new RequestKey(
                request.dimension(), request.chunkX(), request.chunkZ());

        // Refresh duplicate queued requests in place. If the same tile is
        // already executing, that in-flight result will satisfy this request.
        if (queue.inFlight.contains(key)) return;
        if (queue.pending.containsKey(key)) {
            queue.pending.put(key, request);
            return;
        }

        if (queue.pending.size() >= MAX_QUEUED_PER_PLAYER) {
            sendRetry(player, request);
            return;
        }
        queue.pending.put(key, request);
    }

    static void close(ServerPlayer player) {
        if (player == null) return;
        OPEN_PLAYERS.remove(player.getUUID());
        GeneratedChunkBackfill.close(player);
        PlayerQueue queue = QUEUES.remove(player.getUUID());
        if (queue == null) return;

        // A queued client request is already marked pending client-side.
        // Never silently discard it when the map closes, otherwise reopening
        // the map cannot request that tile again.
        for (MapProtocol.RequestTile request : queue.pending.values()) {
            sendRetry(player, request);
        }
        queue.pending.clear();
        queue.inFlight.clear();
    }

    static void clear() {
        QUEUES.clear();
        OPEN_PLAYERS.clear();
        GeneratedChunkBackfill.clear();
        playerCursor = 0;
    }

    static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        QUEUES.keySet().removeIf(uuid ->
                server.getPlayerList().getPlayer(uuid) == null);
        OPEN_PLAYERS.removeIf(uuid ->
                server.getPlayerList().getPlayer(uuid) == null);
        GeneratedChunkBackfill.prune(server);

        if (OPEN_PLAYERS.isEmpty()) {
            playerCursor = 0;
            return;
        }

        List<UUID> players = new ArrayList<>(OPEN_PLAYERS);
        if (playerCursor >= players.size()) playerCursor = 0;

        int started = 0;
        int heavy = 0;
        int visitedPlayers = 0;

        // Explicit viewport requests always win. Background generated-chunk
        // backfill only consumes otherwise-unused request budget.
        while (started < MAX_STARTED_PER_TICK
                && visitedPlayers < players.size()) {
            UUID uuid = players.get(playerCursor);
            playerCursor = (playerCursor + 1) % players.size();
            visitedPlayers++;

            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || !player.isAlive()) continue;

            GeneratedChunkBackfill.ensureCurrent(player);
            PlayerQueue queue = QUEUES.computeIfAbsent(
                    uuid, ignored -> new PlayerQueue());

            int playerStarts = 0;
            int scanned = 0;
            var iterator = queue.pending.entrySet().iterator();
            while (iterator.hasNext()
                    && started < MAX_STARTED_PER_TICK
                    && playerStarts < MAX_STARTED_PER_PLAYER_PER_TICK
                    && scanned < MAX_QUEUE_SCAN_PER_PLAYER_PER_TICK) {
                var entry = iterator.next();
                scanned++;
                MapProtocol.RequestTile request = entry.getValue();

                if (!player.serverLevel().dimension().location().toString()
                        .equals(request.dimension())) {
                    iterator.remove();
                    sendRetry(player, request);
                    continue;
                }

                boolean requiresLoadedCapture =
                        SurfaceService.requiresLoadedCapture(
                                player.serverLevel(),
                                request.chunkX(), request.chunkZ());
                if (requiresLoadedCapture
                        && heavy >= MAX_LOADED_CAPTURES_PER_TICK) {
                    continue;
                }

                RequestKey requestKey = new RequestKey(
                        request.dimension(), request.chunkX(), request.chunkZ());
                iterator.remove();
                if (!queue.inFlight.add(requestKey)) continue;
                if (requiresLoadedCapture) heavy++;
                started++;
                playerStarts++;
                start(player, request, true);
            }

            int backgroundAttempts = 0;
            while (started < MAX_STARTED_PER_TICK
                    && playerStarts < MAX_STARTED_PER_PLAYER_PER_TICK
                    && backgroundAttempts < MAX_QUEUE_SCAN_PER_PLAYER_PER_TICK) {
                ChunkKey key = GeneratedChunkBackfill.poll(player);
                if (key == null) break;
                backgroundAttempts++;

                if (!player.serverLevel().dimension().location().toString()
                        .equals(key.dimension())) {
                    continue;
                }

                RequestKey requestKey = new RequestKey(
                        key.dimension(), key.x(), key.z());
                if (queue.pending.containsKey(requestKey)
                        || queue.inFlight.contains(requestKey)) {
                    continue;
                }

                Long knownFingerprint =
                        GeneratedChunkBackfill.knownFingerprint(player, key);
                MapProtocol.RequestTile background = new MapProtocol.RequestTile(
                        key.dimension(), key.x(), key.z(),
                        knownFingerprint != null,
                        knownFingerprint == null ? 0L : knownFingerprint);
                if (!queue.inFlight.add(requestKey)) continue;
                started++;
                playerStarts++;
                start(player, background, false);
            }
        }
    }

    private static void start(
            ServerPlayer player,
            MapProtocol.RequestTile request,
            boolean watchLive) {
        if (watchLive) {
            ChunkKey watchedKey = new ChunkKey(
                    request.dimension(), request.chunkX(), request.chunkZ());
            LiveSurfaceUpdates.watch(player, watchedKey);
        }

        var level = player.serverLevel();
        var saved = new SavedChunkRead();
        var surfaceFuture = SurfaceService.request(
                level, request.chunkX(), request.chunkZ(), watchLive, saved);
        var structureFuture = StructureDiscovery.request(
                level, request.chunkX(), request.chunkZ(), watchLive, saved);
        saved.submit(level, request.chunkX(), request.chunkZ());

        surfaceFuture.thenCombine(
                        structureFuture,
                        (surface, structures) ->
                                new java.util.AbstractMap.SimpleImmutableEntry<>(
                                        surface, structures))
                .whenComplete((combined, error) ->
                        player.server.execute(() ->
                                finish(player, request, combined, error, watchLive)));
    }

    private static void finish(
            ServerPlayer player,
            MapProtocol.RequestTile request,
            java.util.AbstractMap.SimpleImmutableEntry<
                    java.util.Optional<SurfaceTile>,
                    List<MapProtocol.StructureHit>> combined,
            Throwable error,
            boolean watchLive) {
        RequestKey requestKey = new RequestKey(
                request.dimension(), request.chunkX(), request.chunkZ());
        PlayerQueue queue = QUEUES.get(player.getUUID());
        if (queue != null) queue.inFlight.remove(requestKey);

        if (!player.isAlive() || player.serverLevel() == null) return;
        if (!player.serverLevel().dimension().location().toString()
                .equals(request.dimension())) {
            return;
        }

        var surface = combined == null
                ? java.util.Optional.<SurfaceTile>empty()
                : combined.getKey();
        var structures = combined == null
                ? List.<MapProtocol.StructureHit>of()
                : combined.getValue();

        if (error != null) {
            if (watchLive) {
                sendRetry(player, request);
            } else {
                GeneratedChunkBackfill.requeue(
                        player,
                        new ChunkKey(
                                request.dimension(),
                                request.chunkX(),
                                request.chunkZ()));
            }
            return;
        }
        if (surface == null || surface.isEmpty()) {
            GeneratedChunkBackfill.markEmpty(
                    player,
                    new ChunkKey(
                            request.dimension(),
                            request.chunkX(),
                            request.chunkZ()));
            PacketDistributor.sendToPlayer(player,
                    MapProtocol.Tile.status(
                            request.dimension(),
                            request.chunkX(), request.chunkZ(),
                            false, false, false, structures));
            return;
        }

        SurfaceTile tile = surface.get();
        GeneratedChunkBackfill.markDelivered(player, tile);
        boolean unchanged = request.hasKnownFingerprint()
                && request.knownFingerprint() == tile.fingerprint64();
        PacketDistributor.sendToPlayer(player,
                unchanged
                        ? MapProtocol.Tile.status(
                                tile.key().dimension(),
                                tile.key().x(), tile.key().z(),
                                true, false, false, structures)
                        : MapProtocol.Tile.changed(tile, structures));
    }

    private static void sendRetry(
            ServerPlayer player, MapProtocol.RequestTile request) {
        if (player == null || request == null) return;
        PacketDistributor.sendToPlayer(player,
                MapProtocol.Tile.status(
                        request.dimension(),
                        request.chunkX(), request.chunkZ(),
                        request.hasKnownFingerprint(),
                        false, true, List.of()));
    }
}
