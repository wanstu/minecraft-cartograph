package com.wanstu.cartograph;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-world surface index independent of every individual atlas and its
 * ink/credits. A chunk is indexed only after observing a live LevelChunk or
 * verified FULL serialized NBT. It NEVER asks Minecraft to load or generate
 * a chunk. No region FileChannel / decompression / NBT parsing runs in a tick.
 *
 * Main thread: lookup(), tick(), and closing. Disk workers receive only an
 * immutable SavedRequest with explicit world path, coordinates and height.
 */
public final class WorldSurfaceIndex implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("cartograph.surface");
    private static final Map<MinecraftServer, WorldSurfaceIndex> INSTANCES =
            new IdentityHashMap<>();

    public enum State { READY, PENDING, NOT_GENERATED }
    public enum Source { LOADED, SAVED }
    public record ChunkKey(String dimension, int x, int z) {}
    public record Snapshot(Source source, SurfaceSampler.SurfaceTile tile, long revision) {}
    public record Lookup(State state, Snapshot snapshot) {
        public static Lookup ready(Snapshot snapshot) { return new Lookup(State.READY, snapshot); }
        public static Lookup pending() { return new Lookup(State.PENDING, null); }
        public static Lookup missing() { return new Lookup(State.NOT_GENERATED, null); }
    }

    private record Cached(State status, Snapshot snapshot, long tick) {}
    private record Completed(ChunkKey key, SurfaceSampler.SurfaceTile tile,
                             long nanos, long submittedRevision) {}

    private static final int MAX_CACHED_CHUNKS = 8192;
    private static final int MAX_PENDING_DISK = 256;
    private static final int CACHE_TTL_ABSENT = 160; // 8s: newly saved chunks become visible

    private final ThreadPoolExecutor readers;
    private final LinkedHashMap<ChunkKey, Cached> chunks =
            new LinkedHashMap<>(1024, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ChunkKey, Cached> eldest) {
                    return size() > MAX_CACHED_CHUNKS;
                }
            };
    // Accessed exclusively on the Minecraft server thread.
    /**
     * World lifecycle notifications are completely atlas-independent:
     * generation/load event -> bounded deferred sampling of FULL loaded
     * chunks -> immutable original-surface cache. The event may fire before
     * FULL promotion; never query the chunk synchronously inside the handler.
     */
    private record LoadedNotice(ServerLevel level, int cx, int cz,
                                boolean newlyGenerated, int attempts) {}
    private static final int MAX_LOAD_NOTICES = 2048;
    private final LinkedHashMap<ChunkKey, LoadedNotice> loadNotices = new LinkedHashMap<>();

    public static void chunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        int cx = pos.x, cz = pos.z;
        boolean newlyGenerated = event.isNewChunk();
        // NeoForge's event can arrive while promoting a chunk toward FULL.
        // Handle ONLY server-owned primitive coordinates from an event thread,
        // and mutate the index's maps strictly on the server tick thread.
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> addChunkNotice(level, cx, cz, newlyGenerated, false));
            return;
        }
        addChunkNotice(level, cx, cz, newlyGenerated, false);
    }

    /** Source-world event: independent of maps and players. */
    private static void addChunkNotice(ServerLevel level, int cx, int cz,
                                      boolean newlyGenerated, boolean fromSave) {
        WorldSurfaceIndex index = of(level);
        if (index.closed) return;
        if (fromSave) index.worldSavedEvents++;
        else index.worldLoadedEvents++;
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), cx, cz);
        if (index.loadNotices.size() >= MAX_LOAD_NOTICES && !index.loadNotices.containsKey(key)) {
            ChunkKey oldest = index.loadNotices.keySet().iterator().next();
            index.loadNotices.remove(oldest);
        }
        LoadedNotice old = index.loadNotices.get(key);
        index.loadNotices.put(key, new LoadedNotice(level, cx, cz,
                newlyGenerated || old != null && old.newlyGenerated(), 0));
    }

    /** A chunk saved by vanilla or another mod may have changed without a
     * block place/break event. The save hook also observes only WORLD state,
     * never player position or item presence. */
    public static void chunkSaved(net.neoforged.neoforge.event.level.ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pos = event.getChunk().getPos();
        int cx = pos.x, cz = pos.z;
        if (!level.getServer().isSameThread())
            level.getServer().execute(() -> addChunkNotice(level, cx, cz, false, true));
        else addChunkNotice(level, cx, cz, false, true);
    }

    private void drainLoadedWorldChunks() {
        long deadline = System.nanoTime() + 1_200_000L;
        int completed = 0, candidates = 0;
        var it = loadNotices.entrySet().iterator();
        while (it.hasNext() && completed < 5 && candidates < 18
                && System.nanoTime() < deadline) {
            var entry = it.next();
            candidates++;
            LoadedNotice next = entry.getValue();
            // getChunkNow never synchronously generates/loads world chunks.
            if (next.level().getChunkSource().getChunkNow(next.cx(), next.cz()) == null) {
                if (next.attempts() >= 80) it.remove();
                else entry.setValue(new LoadedNotice(next.level(), next.cx(), next.cz(),
                        next.newlyGenerated(), next.attempts() + 1));
                continue;
            }
            SurfaceSampler.SurfaceTile tile =
                    SurfaceSampler.loaded(next.level(), next.cx(), next.cz()).orElse(null);
            if (tile == null) {
                if (next.attempts() >= 80) it.remove();
                else entry.setValue(new LoadedNotice(next.level(), next.cx(), next.cz(),
                        next.newlyGenerated(), next.attempts() + 1));
                continue;
            }
            Cached previous = chunks.get(entry.getKey());
            boolean changed = next.newlyGenerated()
                    || previous != null && (previous.status != State.READY
                    || !SurfaceDiskStore.equal(previous.snapshot.tile(), tile));
            chunks.put(entry.getKey(), new Cached(State.READY,
                    new Snapshot(Source.LOADED, tile, ++revision), tick));
            it.remove();
            completed++;
            eventSnapshots++;
            if (changed) {
                changedSnapshots++;
                // Only the optional VISIBLE map page is notified via server
                // dispatch; original world data was already sampled globally.
                if (next.newlyGenerated())
                    AtlasMapServer.markWholePageChanged(next.level(),
                            Math.floorDiv(next.cx(), 8), Math.floorDiv(next.cz(), 8));
                else
                    AtlasMapServer.markSurfaceChanged(next.level(), next.cx(), next.cz());
            }
        }
    }

    private final Set<ChunkKey> inFlight = new HashSet<>();
    private final Map<ChunkKey, Long> dirtyUntilTick = new HashMap<>();
    private final Set<ChunkKey> refreshing = new HashSet<>();
    // Workers enqueue only immutable decoded output; no Minecraft world objects.
    private final ConcurrentLinkedQueue<Completed> completed = new ConcurrentLinkedQueue<>();
    private long tick, revision;
    private long cacheHits, launched, received, dropped;
    private long worldLoadedEvents, worldSavedEvents, eventSnapshots, changedSnapshots;
    private long totalReadNanos;
    private boolean closed;

    private WorldSurfaceIndex() {
        AtomicInteger threadIds = new AtomicInteger();
        ThreadFactory factory = job -> {
            Thread worker = new Thread(job,
                    "cartograph-saved-surface-" + threadIds.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        };
        readers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_DISK), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public static WorldSurfaceIndex of(ServerLevel level) {
        WorldSurfaceIndex result = INSTANCES.computeIfAbsent(level.getServer(), server -> new WorldSurfaceIndex());
        if (result.readers.getCorePoolSize() != CartographConfig.workerThreads())
            result.setWorkerCount(CartographConfig.workerThreads());
        return result;
    }

    void setWorkerCount(int n) {
        int count = Math.clamp(n, 2, 8);
        if (count > readers.getMaximumPoolSize()) {
            readers.setMaximumPoolSize(count);
            readers.setCorePoolSize(count);
        } else {
            readers.setCorePoolSize(count);
            readers.setMaximumPoolSize(count);
        }
    }

    /** Called from the server thread on a real surface-affecting block event.
     * An event often fires BEFORE mutation, so don't resample until later. */
    void markDirty(ServerLevel level, int cx, int cz) {
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), cx, cz);
        if (dirtyUntilTick.getOrDefault(key, 0L) > tick) return;
        chunks.remove(key);
        dirtyUntilTick.put(key, tick + 3);
        // Existing on-disk cache is still valid if the event was cancelled;
        // live-chunk sampling takes precedence, and MCA timestamps validate on restart.
    }

    /** Manual bulk refresh. A caller may poll this every tick without
     * scheduling duplicate reads. Forced disk reads bypass derived cache. */
    Lookup forceRefresh(ServerLevel level, int cx, int cz) {
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), cx, cz);
        if (!refreshing.contains(key)) {
            refreshing.add(key);
            chunks.remove(key);
            dirtyUntilTick.remove(key);
            if (level.getChunkSource().getChunkNow(cx, cz) == null)
                submitDisk(key, SurfaceSampler.request(level, cx, cz), true);
        }
        if (inFlight.contains(key)) return Lookup.pending();
        if (!chunks.containsKey(key) && level.getChunkSource().getChunkNow(cx, cz) == null) {
            // A bounded worker queue may reject the first attempt; retry
            // using the FORCED MCA read, never fall back to the old disk cache.
            submitDisk(key, SurfaceSampler.request(level, cx, cz), true);
            return Lookup.pending();
        }
        Lookup now = inspect(level, cx, cz);
        if (now.state() != State.PENDING) refreshing.remove(key);
        return now;
    }

    /** Material changes can reveal a prepared snapshot without reading the world again. */
    public Lookup cached(ServerLevel level, int cx, int cz) {
        Cached cached = chunks.get(new ChunkKey(level.dimension().location().toString(), cx, cz));
        return cached != null && cached.status == State.READY
                ? Lookup.ready(cached.snapshot) : Lookup.missing();
    }

    /** Queue only UNLOADED saved chunks for I/O ahead of the current tile.
     * Unlike inspect(), prefetch must never sample live chunks on the main
     * thread, which would defeat the per-tick scanning time budget. */
    public void prefetchSaved(ServerLevel level, int cx, int cz) {
        if (closed) return;
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), cx, cz);
        if (inFlight.contains(key)) return;
        Cached old = chunks.get(key);
        if (old != null && (old.status == State.READY
                || old.status == State.NOT_GENERATED && tick - old.tick < CACHE_TTL_ABSENT)) return;
        if (level.getChunkSource().getChunkNow(cx, cz) != null) return;
        submitDisk(key, SurfaceSampler.request(level, cx, cz));
    }

    /** Main thread read and cache only. Never run the disk I/O inline. */
    public Lookup inspect(ServerLevel level, int cx, int cz) {
        if (closed) return Lookup.pending();
        String dimension = level.dimension().location().toString();
        ChunkKey key = new ChunkKey(dimension, cx, cz);
        Cached old = chunks.get(key);
        Long dirtyAt = dirtyUntilTick.get(key);
        if (dirtyAt != null) {
            if (tick < dirtyAt) return Lookup.pending();
            dirtyUntilTick.remove(key);
            old = null;
        }

        // getChunkNow never loads or generates a chunk. Loaded chunks may change
        // before being saved and are authoritative over old on-disk samples.
        LevelChunk loaded = level.getChunkSource().getChunkNow(cx, cz);
        if (loaded != null) {
            if (old != null && old.status == State.READY
                    && old.snapshot.source() == Source.LOADED) {
                cacheHits++;
                return Lookup.ready(old.snapshot);
            }
            SurfaceSampler.SurfaceTile sample =
                    SurfaceSampler.loaded(level, cx, cz).orElse(null);
            if (sample != null) {
                Snapshot fresh = new Snapshot(Source.LOADED, sample, ++revision);
                chunks.put(key, new Cached(State.READY, fresh, tick));
                // Never persist an unsaved in-memory block edit against the
                // OLD MCA timestamp: after a crash the on-disk world might
                // revert, and such a derived cache would fabricate terrain.
                // Saved FULL NBT is the only source of persistent raw tiles.
                return Lookup.ready(fresh);
            }
        }

        // Do not keep a previously loaded-only result after its chunk unloads.
        // It may have changed on disk. A disk result remains usable during
        // async revalidation, so the map never blanks on every refresh.
        if (old != null && old.status == State.READY
                && old.snapshot.source() == Source.SAVED) {
            // Real world events and explicit refresh invalidate this value;
            // never re-read the same MCA region every 10 seconds.
            cacheHits++;
            return Lookup.ready(old.snapshot);
        }
        if (old != null && old.status == State.NOT_GENERATED
                && tick - old.tick < CACHE_TTL_ABSENT) {
            cacheHits++;
            return Lookup.missing();
        }
        if (inFlight.contains(key)) return Lookup.pending();

        // Only capture path and integer dimension bounds here; actual file
        // metadata, NBT read, decompression, palette decode remain off-thread.
        submitDisk(key, SurfaceSampler.request(level, cx, cz));
        return Lookup.pending();
    }

    private boolean submitDisk(ChunkKey key, SurfaceSampler.SavedRequest request) {
        return submitDisk(key, request, false);
    }
    private boolean submitDisk(ChunkKey key, SurfaceSampler.SavedRequest request, boolean force) {
        if (closed || inFlight.contains(key)) return false;
        inFlight.add(key);
        long submittedRevision = revision;
        try {
            readers.execute(() -> {
                long start = System.nanoTime();
                SurfaceSampler.SurfaceTile tile = null;
                try {
                    tile = (force ? SurfaceSampler.savedFresh(request) : SurfaceSampler.saved(request)).orElse(null);
                } finally {
                    // A failure must not strand inFlight forever. Exceptions
                    // are already interpreted as unknown/non-FULL by saved().
                    completed.add(new Completed(key, tile,
                            System.nanoTime() - start, submittedRevision));
                }
            });
            launched++;
            return true;
        } catch (RejectedExecutionException full) {
            inFlight.remove(key);
            dropped++;
            return false;
        }
    }

    /**
     * Accept bounded completions once each server tick. Costs/ledger changes
     * are deliberately NOT applied here: atlas ownership and payment are
     * verified by AtlasMapServer/AtlasExports on the main thread.
     */
    public void drain() {
        if (closed) return;
        tick++;
        for (int n = 0; n < 64; n++) {
            Completed next = completed.poll();
            if (next == null) break;
            inFlight.remove(next.key());
            received++;
            totalReadNanos += next.nanos();
            Cached currently = chunks.get(next.key());
            if (currently != null && currently.snapshot() != null
                    && currently.snapshot().revision() > next.submittedRevision()
                    && currently.snapshot().source() == Source.LOADED) {
                // A live full chunk was sampled after the disk job started.
                // Never overwrite its newer surface with older saved bytes.
                continue;
            }
            if (next.tile() == null) {
                chunks.put(next.key(), new Cached(State.NOT_GENERATED, null, tick));
            } else {
                Snapshot snapshot = new Snapshot(Source.SAVED, next.tile(), ++revision);
                chunks.put(next.key(), new Cached(State.READY, snapshot, tick));
            }
        }
        if (tick % 600 == 0 && (launched > 0 || dropped > 0
                || worldLoadedEvents > 0 || worldSavedEvents > 0)) {
            LOG.info("Surface cache: chunks={} pending={} queue={} scheduled={} done={} " +
                            "hits={} rejected={} avg_read_ms={} world_loaded={} world_saved={} " +
                            "world_snapshots={} true_surface_changes={}",
                    chunks.size(), inFlight.size(), readers.getQueue().size(),
                    launched, received, cacheHits, dropped,
                    received == 0 ? 0.0 : (totalReadNanos / 1_000_000.0 / received),
                    worldLoadedEvents, worldSavedEvents, eventSnapshots, changedSnapshots);
        }
    }

    public static void tick(ServerTickEvent.Post event) {
        WorldSurfaceIndex existing = INSTANCES.get(event.getServer());
        if (existing != null) {
            existing.drain();
            existing.drainLoadedWorldChunks();
        }
    }

    public static void serverStopped(ServerStoppedEvent event) {
        WorldSurfaceIndex existing = INSTANCES.remove(event.getServer());
        if (existing != null) existing.close();
    }

    public int cachedChunks() { return chunks.size(); }
    public int pendingChunks() { return inFlight.size(); }
    public int diskQueueSize() { return readers.getQueue().size(); }
    public long completedScans() { return received; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        readers.shutdownNow();
        completed.clear();
        inFlight.clear();
        dirtyUntilTick.clear();
        refreshing.clear();
        chunks.clear();
        loadNotices.clear();
    }
}
