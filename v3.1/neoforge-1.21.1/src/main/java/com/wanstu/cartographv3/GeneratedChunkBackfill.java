package com.wanstu.cartographv3;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Low-priority world backfill independent from any frontend viewport.
 *
 * Region headers are scanned asynchronously to discover every generated/saved
 * chunk in the current dimension. TileRequestScheduler consumes those keys
 * gradually after high-priority viewport work. Newly saved chunks are queued
 * immediately, so map facts continue to grow while no frontend is visible.
 */
final class GeneratedChunkBackfill {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.backfill");
    private static final Pattern REGION_FILE =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final ExecutorService SCANNER =
            Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "cartograph-v31-generated-scan");
                thread.setDaemon(true);
                return thread;
            });

    private static final class State {
        volatile boolean active;
        final Map<String, ConcurrentLinkedQueue<ChunkKey>> ready =
                new ConcurrentHashMap<>();
        final Map<String, Set<ChunkKey>> queued =
                new ConcurrentHashMap<>();
        final Set<String> scanned = ConcurrentHashMap.newKeySet();
        final Set<String> scanning = ConcurrentHashMap.newKeySet();
        final Set<String> readyLogged = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> initialPending = ConcurrentHashMap.newKeySet();
        final Map<ChunkKey, Long> knownFingerprints = new ConcurrentHashMap<>();
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private GeneratedChunkBackfill() {}

    static void open(ServerPlayer player) {
        if (player == null) return;
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        state.active = true;
        ensureCurrent(player);
    }

    static void close(ServerPlayer player) {
        if (player == null) return;
        State state = STATES.get(player.getUUID());
        if (state != null) state.active = false;
    }

    static void ensureCurrent(ServerPlayer player) {
        if (player == null) return;
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        if (!state.active) return;

        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        if (state.scanned.contains(dimension)
                || !state.scanning.add(dimension)) {
            return;
        }

        Path regionDirectory = regionDirectory(level);
        int centerX = player.chunkPosition().x;
        int centerZ = player.chunkPosition().z;
        UUID playerId = player.getUUID();
        SCANNER.execute(() ->
                scan(playerId, state, dimension, regionDirectory, centerX, centerZ));
    }

    static ChunkKey poll(ServerPlayer player) {
        if (player == null) return null;
        State state = STATES.get(player.getUUID());
        if (state == null || !state.active) return null;
        String dimension = player.serverLevel().dimension().location().toString();
        ConcurrentLinkedQueue<ChunkKey> queue = state.ready.get(dimension);
        if (queue == null) return null;
        return queue.poll();
    }

    static void requeue(ServerPlayer player, ChunkKey key) {
        if (player == null || key == null) return;
        State state = STATES.get(player.getUUID());
        if (state == null || !state.active) return;
        Set<ChunkKey> queued = state.queued.computeIfAbsent(
                key.dimension(), ignored -> ConcurrentHashMap.newKeySet());
        if (queued.add(key)) {
            state.ready.computeIfAbsent(
                    key.dimension(), ignored -> new ConcurrentLinkedQueue<>()).add(key);
            return;
        }
        state.ready.computeIfAbsent(
                key.dimension(), ignored -> new ConcurrentLinkedQueue<>()).add(key);
    }

    static Long knownFingerprint(ServerPlayer player, ChunkKey key) {
        if (player == null || key == null) return null;
        State state = STATES.get(player.getUUID());
        return state == null ? null : state.knownFingerprints.get(key);
    }

    static void markDelivered(ServerPlayer player, SurfaceTile tile) {
        if (player == null || tile == null) return;
        State state = STATES.get(player.getUUID());
        if (state == null) return;
        state.knownFingerprints.put(tile.key(), tile.fingerprint64());
        completeAttempt(state, tile.key());
    }

    static void markEmpty(ServerPlayer player, ChunkKey key) {
        if (player == null || key == null) return;
        State state = STATES.get(player.getUUID());
        if (state == null) return;
        completeAttempt(state, key);
    }

    static void chunkSaved(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        ChunkKey key = new ChunkKey(
                level.dimension().location().toString(), pos.x, pos.z);

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.serverLevel() != level) continue;
            State state = STATES.get(player.getUUID());
            if (state == null || !state.active) continue;

            // Once a full surface fact is known, ordinary chunk autosaves do
            // not justify rereading the same MCA data. Visible/live updates
            // handle active map areas. Save events remain important for
            // candidates that were incomplete during the initial scan and for
            // chunks generated after that scan.
            if (state.knownFingerprints.containsKey(key)) continue;
            if (!state.initialPending.contains(key)) add(state, key);
        }
    }

    static void prune(MinecraftServer server) {
        if (server == null) return;
        STATES.entrySet().removeIf(entry ->
                server.getPlayerList().getPlayer(entry.getKey()) == null);
    }

    static void clear() {
        STATES.clear();
    }

    private static void scan(
            UUID playerId,
            State state,
            String dimension,
            Path regionDirectory,
            int centerX,
            int centerZ) {
        List<ChunkKey> discovered = new ArrayList<>();
        try {
            if (Files.isDirectory(regionDirectory)) {
                try (DirectoryStream<Path> stream =
                             Files.newDirectoryStream(regionDirectory, "r.*.*.mca")) {
                    for (Path regionFile : stream) {
                        Matcher matcher = REGION_FILE.matcher(
                                regionFile.getFileName().toString());
                        if (!matcher.matches()) continue;
                        int regionX = Integer.parseInt(matcher.group(1));
                        int regionZ = Integer.parseInt(matcher.group(2));
                        discoverRegion(regionFile, dimension, regionX, regionZ, discovered);
                    }
                }
            }

            discovered.sort(Comparator.comparingLong(key -> {
                long dx = Math.abs((long) key.x() - centerX);
                long dz = Math.abs((long) key.z() - centerZ);
                return Math.max(dx, dz);
            }));

            if (STATES.get(playerId) != state) return;
            state.initialPending.addAll(discovered);
            for (ChunkKey key : discovered) add(state, key);
            state.scanned.add(dimension);
            LOG.info(
                    "V31_BACKGROUND_INDEX player={} dimension={} region_candidates={}",
                    playerId, dimension, discovered.size());
        } catch (Exception failure) {
            LOG.warn(
                    "Unable to scan generated chunks for dimension {}", dimension, failure);
        } finally {
            state.scanning.remove(dimension);
        }
    }

    private static void discoverRegion(
            Path regionFile,
            String dimension,
            int regionX,
            int regionZ,
            List<ChunkKey> output) {
        try (RandomAccessFile file = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (file.length() < 4096L) return;
            for (int index = 0; index < 1024; index++) {
                int location = file.readInt();
                int sector = location >>> 8;
                int sectors = location & 0xff;
                if (sector < 2 || sectors == 0) continue;
                int localX = index & 31;
                int localZ = index >>> 5;
                output.add(new ChunkKey(
                        dimension,
                        regionX * 32 + localX,
                        regionZ * 32 + localZ));
            }
        } catch (Exception ignored) {
            // A region can be concurrently saved. A future save event will
            // enqueue any chunk missed by this one-time header scan.
        }
    }

    private static void completeAttempt(State state, ChunkKey key) {
        Set<ChunkKey> queued = state.queued.get(key.dimension());
        if (queued != null) queued.remove(key);
        state.initialPending.remove(key);

        boolean dimensionPending = state.initialPending.stream()
                .anyMatch(candidate -> candidate.dimension().equals(key.dimension()));
        if (!dimensionPending
                && state.scanned.contains(key.dimension())
                && state.readyLogged.add(key.dimension())) {
            long full = state.knownFingerprints.keySet().stream()
                    .filter(candidate -> candidate.dimension().equals(key.dimension()))
                    .count();
            LOG.info(
                    "V31_BACKGROUND_READY dimension={} full_chunks={}",
                    key.dimension(), full);
        }
    }

    private static void add(State state, ChunkKey key) {
        Set<ChunkKey> queued = state.queued.computeIfAbsent(
                key.dimension(), ignored -> ConcurrentHashMap.newKeySet());
        if (!queued.add(key)) return;
        state.ready.computeIfAbsent(
                key.dimension(), ignored -> new ConcurrentLinkedQueue<>()).add(key);
    }

    private static Path regionDirectory(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        if (level.dimension() == Level.OVERWORLD) return root.resolve("region");
        if (level.dimension() == Level.NETHER) return root.resolve("DIM-1").resolve("region");
        if (level.dimension() == Level.END) return root.resolve("DIM1").resolve("region");

        var id = level.dimension().location();
        return root.resolve("dimensions")
                .resolve(id.getNamespace())
                .resolve(id.getPath())
                .resolve("region");
    }
}
