package com.wanstu.cartograph;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Change detection, not block-update detection.
 *
 * NeoForge NeighborNotifyEvent fires very frequently for leaf/plant updates
 * and for many non-surface block mutations. Previously EACH such event
 * invalidated an entire atlas tile, triggering 64 chunk scans with no new
 * painted pixels. Keep an old sampled tile, wait until the actual mutation
 * finishes, sample the already-loaded chunk with a hard main-thread budget,
 * and notify map clients only if the resulting map-visible surface changed.
 *
 * No chunk loads or generation; no I/O; never touches material ledgers.
 */
final class SurfaceChangeMonitor {
    private static final int MAX_PENDING = 1024;
    private static final long NEIGHBOR_DEBOUNCE_TICKS = 20;
    private static final long NEIGHBOR_RECHECK_TICKS = 120;
    private static final long DIRECT_DEBOUNCE_TICKS = 3;
    private static final Map<WorldSurfaceIndex.ChunkKey, Candidate> PENDING = new LinkedHashMap<>();
    private static final Map<WorldSurfaceIndex.ChunkKey, Long> LAST_CHECK = new HashMap<>();
    private static long ticks;

    private static final class Candidate {
        final ServerLevel level;
        final int x, z;
        final SurfaceSampler.SurfaceTile before;
        long due;
        boolean direct;
        Candidate(ServerLevel level, int x, int z, SurfaceSampler.SurfaceTile before,
                  long due, boolean direct) {
            this.level = level; this.x = x; this.z = z;
            this.before = before; this.due = due; this.direct = direct;
        }
    }
    private SurfaceChangeMonitor() {}

    static void changed(LevelAccessor world, BlockPos at, boolean direct) {
        if (!(world instanceof ServerLevel level)) return;
        int cx = Math.floorDiv(at.getX(), 16), cz = Math.floorDiv(at.getZ(), 16);
        WorldSurfaceIndex.ChunkKey key =
                new WorldSurfaceIndex.ChunkKey(level.dimension().location().toString(), cx, cz);
        Candidate pending = PENDING.get(key);
        if (pending != null) {
            if (direct) {
                pending.direct = true;
                // Direct edits during an existing candidate MUST expire an
                // intermediate live snapshot. Otherwise the loaded-chunk
                // TTL could return the sample taken before the latest edit.
                indexDirty(level, cx, cz);
                pending.due = ticks + DIRECT_DEBOUNCE_TICKS;
            }
            return; // Neighbor-only noise does not repeatedly resample.
        }
        WorldSurfaceIndex index = WorldSurfaceIndex.of(level);
        WorldSurfaceIndex.Lookup existing = index.cached(level, cx, cz);
        if (!direct && existing.state() != WorldSurfaceIndex.State.READY)
            return; // Never scan an unobserved chunk for mere neighbor noise.
        if (PENDING.size() >= MAX_PENDING) return; // Main-thread backpressure.
        SurfaceSampler.SurfaceTile baseline = existing.snapshot() == null
                ? null : existing.snapshot().tile();
        long debounce = direct ? DIRECT_DEBOUNCE_TICKS : NEIGHBOR_DEBOUNCE_TICKS;
        long due = ticks + debounce;
        if (!direct) due = Math.max(due, LAST_CHECK.getOrDefault(key, 0L) + NEIGHBOR_RECHECK_TICKS);
        PENDING.put(key, new Candidate(level, cx, cz, baseline, due, direct));
        index.markDirty(level, cx, cz);
    }

    private static void indexDirty(ServerLevel level, int cx, int cz) {
        WorldSurfaceIndex.of(level).markDirty(level, cx, cz);
    }

    static void tick(ServerTickEvent.Post event) {
        ticks++;
        long deadline = System.nanoTime() + 2_000_000L;
        int processed = 0;
        Iterator<Map.Entry<WorldSurfaceIndex.ChunkKey, Candidate>> it = PENDING.entrySet().iterator();
        while (it.hasNext() && processed < 4 && System.nanoTime() < deadline) {
            var entry = it.next();
            Candidate candidate = entry.getValue();
            if (ticks < candidate.due) continue;
            WorldSurfaceIndex.Lookup observed =
                    WorldSurfaceIndex.of(candidate.level).inspect(candidate.level, candidate.x, candidate.z);
            if (observed.state() == WorldSurfaceIndex.State.PENDING) {
                candidate.due = ticks + 2;
                continue;
            }
            if (observed.state() != WorldSurfaceIndex.State.READY
                    || candidate.before == null
                    || !SurfaceDiskStore.equal(candidate.before, observed.snapshot().tile())) {
                // Direct mutations without a prior cached baseline are
                // conservatively invalidated; neighbor-only events always
                // have a baseline, so empty map areas don't get scanned.
                AtlasMapServer.markSurfaceChanged(candidate.level, candidate.x, candidate.z);
            }
            LAST_CHECK.put(entry.getKey(), ticks);
            it.remove();
            processed++;
        }
        if (ticks % 600 == 0)
            LAST_CHECK.entrySet().removeIf(e -> ticks - e.getValue() > 1200);
    }

    static void stopped(ServerStoppedEvent event) {
        PENDING.clear();
        LAST_CHECK.clear();
        ticks = 0;
    }
}
