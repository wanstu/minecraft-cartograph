package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/**
 * Explicit cache refresh for already-surveyed chunks only.
 * Snapshot progress is fixed at task creation. All costs and masks remain
 * untouched. Disk parsing uses WorldSurfaceIndex's bounded worker pool.
 * Only sampling still-loaded chunks happens on the logical server thread.
 */
final class MapRefreshJobs {
    private static final Map<UUID, Job> TASKS = new HashMap<>();
    private static final int MAX_IN_FLIGHT = 56;
    private static int ticks;
    private MapRefreshJobs() {}

    private record Coord(int x, int z) {}
    private record PageCoord(int x, int z) {
        PageCoord(Coord chunk) {
            this(Math.floorDiv(chunk.x(), 8), Math.floorDiv(chunk.z(), 8));
        }
    }
    private static final class Job {
        final String atlas, dimension;
        final List<Coord> target;
        final LinkedHashMap<Coord, Integer> waiting = new LinkedHashMap<>();
        // A page becomes invalid only ONCE, after every surveyed chunk in it
        // is refreshed. Never trigger N page re-scans for N edited chunks.
        final Map<PageCoord, Integer> unfinishedPerPage = new HashMap<>();
        int cursor, done, failed, state = 1; // 1 running, 2 paused, 3 done, 4 cancelled
        Job(String atlas, String dimension, List<Coord> target) {
            this.atlas = atlas; this.dimension = dimension;
            this.target = target;
            for (Coord c : target)
                unfinishedPerPage.merge(new PageCoord(c), 1, Integer::sum);
            if (target.isEmpty()) state = 3;
        }
        MapNetwork.RefreshProgress progress() {
            return new MapNetwork.RefreshProgress(state, done, target.size(), failed);
        }
    }

    static void control(ServerPlayer player, MapNetwork.RefreshControl request) {
        if (request.op() < 0 || request.op() > 4) return;
        Job job = TASKS.get(player.getUUID());
        if (request.op() == 4) {
            send(player, job != null && job.atlas.equals(request.atlasId()) ? job : null);
            return;
        }
        ItemStack atlas = AtlasMapServer.findAtlas(player, request.atlasId());
        if (atlas.isEmpty()) {
            AtlasActions.reply(player, false, "请将地图册放入背包后刷新");
            return;
        }
        AtlasWorldData.Entry entry = AtlasWorldData.get(player.serverLevel()).access(atlas);
        if (!entry.state().id().equals(request.atlasId())) {
            AtlasActions.reply(player, false, "地图册标识不匹配"); return;
        }
        if (request.op() == 0) {
            if (job != null && job.state == 1) {
                send(player, job); return;
            }
            String dim = player.serverLevel().dimension().location().toString();
            List<Coord> entries = new ArrayList<>();
            for (SurveyLedger.ChunkKey key : entry.surveyedKeys(dim)) {
                if (!entry.isCovered(dim, Math.floorDiv(key.x(), 8), Math.floorDiv(key.z(), 8))) continue;
                entries.add(new Coord(key.x(), key.z()));
            }
            // Nearby region coordinates are visited together to reduce file seeks.
            entries.sort(Comparator.comparingInt((Coord k) -> Math.floorDiv(k.x(), 32))
                    .thenComparingInt(k -> Math.floorDiv(k.z(), 32))
                    .thenComparingInt(Coord::z).thenComparingInt(Coord::x));
            job = new Job(entry.state().id(), dim, List.copyOf(entries));
            TASKS.put(player.getUUID(), job);
            AtlasActions.reply(player, true, job.state == 3
                    ? "没有需要刷新测绘的区块" : "已启动后台刷新：共 " + entries.size() + " 个已测绘区块，不消耗任何材料");
        } else if (job != null) {
            if (request.op() == 1 && job.state == 1) job.state = 2;
            if (request.op() == 2 && job.state == 2) job.state = 1;
            if (request.op() == 3 && (job.state == 1 || job.state == 2)) {
                job.state = 4;
                job.waiting.clear();
            }
        }
        send(player, job);
    }

    private static void send(ServerPlayer player, Job job) {
        PacketDistributor.sendToPlayer(player,
                job == null ? new MapNetwork.RefreshProgress(0, 0, 0, 0) : job.progress());
    }

    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        TASKS.clear();
        ticks = 0;
    }

    private static void finishedChunk(Job job, Coord c, WorldSurfaceIndex.Lookup snapshot,
                                      net.minecraft.server.level.ServerLevel world) {
        job.done++;
        if (snapshot.state() != WorldSurfaceIndex.State.READY) job.failed++;
        PageCoord page = new PageCoord(c);
        int remaining = job.unfinishedPerPage.merge(page, -1, Integer::sum);
        if (remaining == 0) {
            job.unfinishedPerPage.remove(page);
            AtlasMapServer.markSurfaceChanged(world, c.x(), c.z());
        }
    }

    static void tick(ServerTickEvent.Post event) {
        ticks++;
        MinecraftServer server = event.getServer();
        Iterator<Map.Entry<UUID, Job>> it = TASKS.entrySet().iterator();
        long tickBegin = System.nanoTime();
        for (; it.hasNext();) {
            if (System.nanoTime() - tickBegin > 3_000_000L) break;
            var entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { it.remove(); continue; }
            Job job = entry.getValue();
            if (job.state != 1) {
                if (ticks % 20 == 0) send(player, job);
                continue;
            }
            if (!player.serverLevel().dimension().location().toString().equals(job.dimension)
                    || AtlasMapServer.findAtlas(player, job.atlas).isEmpty()) {
                job.state = 2; send(player, job); continue;
            }
            WorldSurfaceIndex index = WorldSurfaceIndex.of(player.serverLevel());
            int work = 0;
            var pending = job.waiting.entrySet().iterator();
            while (pending.hasNext() && work < 14) {
                if (work > 3 && System.nanoTime() - tickBegin > 2_500_000L) break;
                var item = pending.next();
                Coord c = item.getKey();
                WorldSurfaceIndex.Lookup result = index.forceRefresh(player.serverLevel(), c.x, c.z);
                if (result.state() != WorldSurfaceIndex.State.PENDING || ticks - item.getValue() > 1800) {
                    finishedChunk(job, c, result, player.serverLevel());
                    pending.remove();
                }
                work++;
            }
            while (job.cursor < job.target.size() && job.waiting.size() < MAX_IN_FLIGHT && work < 24) {
                if (work > 3 && System.nanoTime() - tickBegin > 2_500_000L) break;
                Coord c = job.target.get(job.cursor++);
                WorldSurfaceIndex.Lookup result = index.forceRefresh(player.serverLevel(), c.x, c.z);
                if (result.state() == WorldSurfaceIndex.State.PENDING) job.waiting.put(c, ticks);
                else {
                    finishedChunk(job, c, result, player.serverLevel());
                }
                work++;
            }
            if (job.done >= job.target.size()) job.state = 3;
            if (ticks % 20 == 0 || job.state == 3) send(player, job);
        }
    }
}
