package com.wanstu.cartographv3;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Low-priority world backfill independent from any frontend viewport.
 *
 * Region headers are scanned asynchronously to discover every generated/saved
 * chunk in the current dimension. TileRequestScheduler consumes those keys
 * gradually after high-priority viewport work. Recent saves around the player
 * share that existing background budget with the archive.
 */
final class GeneratedChunkBackfill {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.backfill");
    private static final Pattern REGION_FILE =
            Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final int RECENT_SAVE_RADIUS = 2;
    private static final int RECENT_BURST = 3;
    private static final int MAX_RECENT_PENDING = 128;
    private static final int MAX_RECENT_ATTEMPTS = 3;
    private static final long SAVE_GRACE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final ExecutorService SCANNER =
            Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "cartograph-v31-generated-scan");
                thread.setDaemon(true);
                return thread;
            });

    record Claim(ChunkKey key, int recentAttempt) {}

    static final class ReadyChunks {
        private record RecentSave(int attempt, long readyAt) {}

        private final Set<ChunkKey> queued = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<ChunkKey> archive = new ConcurrentLinkedQueue<>();
        private final LinkedHashMap<ChunkKey, RecentSave> recent = new LinkedHashMap<>();
        private int recentStreak;

        void addArchived(ChunkKey key) {
            if (queued.add(key)) archive.add(key);
        }

        synchronized void addSaved(ChunkKey key, long now) {
            if (queued.add(key)) enqueueSave(key, 1, now);
        }

        synchronized Claim poll(long now) {
            var next = recent.firstEntry();
            boolean eligible = next != null && now - next.getValue().readyAt >= 0;
            if (eligible && recentStreak < RECENT_BURST) return takeRecent();
            ChunkKey key = archive.poll();
            if (key != null) {
                recentStreak = 0;
                return new Claim(key, 0);
            }
            return eligible ? takeRecent() : null;
        }

        private Claim takeRecent() {
            var save = recent.pollFirstEntry();
            recentStreak = Math.min(RECENT_BURST, recentStreak + 1);
            return new Claim(save.getKey(), save.getValue().attempt);
        }

        private void enqueueSave(ChunkKey key, int attempt, long now) {
            if (attempt <= MAX_RECENT_ATTEMPTS && recent.size() < MAX_RECENT_PENDING) {
                // The chunk-save callback can fire before the region write is visible.
                recent.put(key, new RecentSave(attempt, now + SAVE_GRACE_NANOS));
            } else {
                archive.add(key);
            }
        }

        synchronized void requeue(Claim claim, long now) {
            if (!queued.contains(claim.key)) return;
            if (claim.recentAttempt == 0) archive.add(claim.key);
            else enqueueSave(claim.key, claim.recentAttempt + 1, now);
        }

        synchronized boolean retryEmpty(Claim claim, long now) {
            if (claim.recentAttempt == 0 || !queued.contains(claim.key)) return false;
            enqueueSave(claim.key, claim.recentAttempt + 1, now);
            return true;
        }

        synchronized boolean hasPendingSave(ChunkKey key) {
            return recent.containsKey(key);
        }

        synchronized void complete(ChunkKey key) {
            recent.remove(key);
            queued.remove(key);
        }
    }

    private static final class State {
        volatile boolean active;
        final Map<String, ReadyChunks> ready = new ConcurrentHashMap<>();
        final Set<String> scanned = ConcurrentHashMap.newKeySet();
        final Set<String> scanning = ConcurrentHashMap.newKeySet();
        final Set<String> readyLogged = ConcurrentHashMap.newKeySet();
        final Map<String, Set<ChunkKey>> initialPending = new ConcurrentHashMap<>();
        final Map<String, Map<ChunkKey, Long>> knownFingerprints = new ConcurrentHashMap<>();
        final Map<String, Long> checkpoints = new ConcurrentHashMap<>();
        final Map<String, Long> scanStartedAt = new ConcurrentHashMap<>();
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private GeneratedChunkBackfill() {}

    static void open(ServerPlayer player, long checkpointMillis) {
        if (player == null) return;
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        boolean wasActive = state.active;
        state.active = true;

        String dimension = MapGameAccess.dimension(MapGameAccess.level(player));
        long checkpoint = Math.max(0L, checkpointMillis);
        Long previousCheckpoint = state.checkpoints.put(dimension, checkpoint);
        boolean needsScan = !wasActive
                || previousCheckpoint == null
                || previousCheckpoint.longValue() != checkpoint;
        if (needsScan) {
            state.readyLogged.remove(dimension);
            if (!state.scanning.contains(dimension)) state.scanned.remove(dimension);
        }
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

        ServerLevel level = MapGameAccess.level(player);
        String dimension = MapGameAccess.dimension(level);
        if (state.scanned.contains(dimension)
                || !state.scanning.add(dimension)) {
            return;
        }

        Path regionDirectory = MapGameAccess.regionDirectory(level);
        int centerX = MapGameAccess.chunkX(player.chunkPosition());
        int centerZ = MapGameAccess.chunkZ(player.chunkPosition());
        long checkpointMillis = state.checkpoints.getOrDefault(dimension, 0L);
        long scanStartedMillis = System.currentTimeMillis();
        state.scanStartedAt.put(dimension, scanStartedMillis);
        UUID playerId = player.getUUID();
        MinecraftServer server = MapGameAccess.server(player);
        SCANNER.execute(() ->
                scan(server, playerId, state, dimension, regionDirectory,
                        centerX, centerZ, checkpointMillis, scanStartedMillis));
    }

    static Claim poll(ServerPlayer player) {
        if (player == null) return null;
        State state = STATES.get(player.getUUID());
        if (state == null || !state.active) return null;
        String dimension = MapGameAccess.dimension(MapGameAccess.level(player));
        ReadyChunks queue = state.ready.get(dimension);
        return queue == null ? null : queue.poll(System.nanoTime());
    }

    static void requeue(ServerPlayer player, Claim claim) {
        State state = STATES.get(player.getUUID());
        ReadyChunks queue = state == null ? null : state.ready.get(claim.key.dimension());
        if (queue != null) queue.requeue(claim, System.nanoTime());
    }

    static boolean retryEmptySave(ServerPlayer player, Claim claim) {
        if (claim == null || claim.recentAttempt == 0) return false;
        State state = STATES.get(player.getUUID());
        ReadyChunks queue = state == null ? null : state.ready.get(claim.key.dimension());
        return queue != null && queue.retryEmpty(claim, System.nanoTime());
    }

    static Long knownFingerprint(ServerPlayer player, ChunkKey key) {
        if (player == null || key == null) return null;
        State state = STATES.get(player.getUUID());
        if (state == null) return null;
        Map<ChunkKey, Long> known = state.knownFingerprints.get(key.dimension());
        return known == null ? null : known.get(key);
    }

    static void markDelivered(ServerPlayer player, SurfaceTile tile) {
        if (player == null || tile == null) return;
        State state = STATES.get(player.getUUID());
        if (state == null) return;
        state.knownFingerprints.computeIfAbsent(tile.key().dimension(),
                ignored -> new ConcurrentHashMap<>()).put(tile.key(), tile.fingerprint64());
        completeAttempt(player, state, tile.key());
    }

    static void markEmpty(ServerPlayer player, ChunkKey key) {
        if (player == null || key == null) return;
        State state = STATES.get(player.getUUID());
        if (state == null) return;
        ReadyChunks queue = state.ready.get(key.dimension());
        // A viewport request can finish before the queued region write is visible.
        if (queue != null && queue.hasPendingSave(key)) return;
        completeAttempt(player, state, key);
    }

    static void chunkSaved(ServerLevel level, ChunkPos pos) {
        ChunkKey key = new ChunkKey(
                MapGameAccess.dimension(level), MapGameAccess.chunkX(pos), MapGameAccess.chunkZ(pos));

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (MapGameAccess.level(player) != level) continue;
            State state = STATES.get(player.getUUID());
            if (state == null || !state.active) continue;

            // Once a full surface fact is known, ordinary chunk autosaves do
            // not justify rereading the same MCA data. Visible/live updates
            // handle active map areas. Save events remain important for
            // candidates that were incomplete during the initial scan and for
            // chunks generated after that scan.
            Map<ChunkKey, Long> known = state.knownFingerprints.get(key.dimension());
            if (known != null && known.containsKey(key)) continue;
            Set<ChunkKey> pending = state.initialPending.get(key.dimension());
            if (pending != null && pending.contains(key)) continue;
            ReadyChunks queue = ready(state, key.dimension());
            ChunkPos center = player.chunkPosition();
            if (Math.abs(key.x() - MapGameAccess.chunkX(center)) <= RECENT_SAVE_RADIUS
                    && Math.abs(key.z() - MapGameAccess.chunkZ(center)) <= RECENT_SAVE_RADIUS) {
                queue.addSaved(key, System.nanoTime());
            } else {
                queue.addArchived(key);
            }
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
            MinecraftServer server,
            UUID playerId,
            State state,
            String dimension,
            Path regionDirectory,
            int centerX,
            int centerZ,
            long checkpointMillis,
            long scanStartedMillis) {
        List<ChunkKey> discovered = new ArrayList<>();
        try {
            if (Files.isDirectory(regionDirectory)) {
                try (DirectoryStream<Path> stream =
                             Files.newDirectoryStream(regionDirectory, "r.*.*.mca")) {
                    for (Path regionFile : stream) {
                        Matcher matcher = REGION_FILE.matcher(
                                regionFile.getFileName().toString());
                        if (!matcher.matches()) continue;
                        if (checkpointMillis > 0L) {
                            try {
                                if (Files.getLastModifiedTime(regionFile).toMillis()
                                        <= checkpointMillis) continue;
                            } catch (Exception ignored) {
                                // If metadata cannot be read, scan conservatively.
                            }
                        }
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
            state.initialPending.computeIfAbsent(dimension,
                    ignored -> ConcurrentHashMap.newKeySet()).addAll(discovered);
            ReadyChunks queue = ready(state, dimension);
            for (ChunkKey key : discovered) queue.addArchived(key);
            state.scanned.add(dimension);
            LOG.info(
                    "V31_BACKGROUND_INDEX player={} dimension={} region_candidates={} checkpoint={}",
                    playerId, dimension, discovered.size(), checkpointMillis);
            // A tiny queue can finish while this worker is still publishing its keys.
            if (state.initialPending.get(dimension).isEmpty()) {
                publishCheckpoint(
                        server, playerId, state, dimension, scanStartedMillis);
            }
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
            // RandomAccessFile.readInt performs unbuffered reads. Read the
            // whole location table once instead of thousands of tiny reads.
            byte[] locations = new byte[4096];
            file.readFully(locations);
            java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(locations);
            for (int index = 0; index < 1024; index++) {
                int location = header.getInt();
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

    private static void completeAttempt(ServerPlayer player, State state, ChunkKey key) {
        ReadyChunks queue = state.ready.get(key.dimension());
        if (queue != null) queue.complete(key);
        Set<ChunkKey> pending = state.initialPending.get(key.dimension());
        if (pending != null) pending.remove(key);

        // Never traverse another dimension's potentially huge backlog on the server thread.
        boolean dimensionPending = pending != null && !pending.isEmpty();
        if (!dimensionPending && state.scanned.contains(key.dimension())
                && !state.readyLogged.contains(key.dimension())) {
            publishCheckpoint(
                    MapGameAccess.server(player),
                    player.getUUID(),
                    state,
                    key.dimension(),
                    state.scanStartedAt.getOrDefault(
                            key.dimension(), System.currentTimeMillis()));
        }
    }

    private static void publishCheckpoint(
            MinecraftServer server,
            UUID playerId,
            State state,
            String dimension,
            long checkpointMillis) {
        if (server == null || checkpointMillis <= 0L || state.readyLogged.contains(dimension)) return;
        server.execute(() -> {
            if (STATES.get(playerId) != state || !state.active) return;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !state.readyLogged.add(dimension)) return;

            Map<ChunkKey, Long> known = state.knownFingerprints.get(dimension);
            long full = known == null ? 0L : known.size();
            LOG.info(
                    "V31_BACKGROUND_READY dimension={} full_chunks={} checkpoint={}",
                    dimension, full, checkpointMillis);
            MapTransport.sendToPlayer(
                    player, new MapProtocol.BackfillCheckpoint(dimension, checkpointMillis));
        });
    }

    private static ReadyChunks ready(State state, String dimension) {
        return state.ready.computeIfAbsent(dimension, ignored -> new ReadyChunks());
    }


}
