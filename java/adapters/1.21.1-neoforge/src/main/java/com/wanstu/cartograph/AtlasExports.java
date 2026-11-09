package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Async-across-server-ticks paper map export. It reads at most two
 * already-generated chunks per tick; no chunk generation and no one-tick
 * synchronous 64-chunk disk/NBT scan. The blank map is consumed only after
 * the checked export is complete and safely placed in the player's inventory.
 */
public final class AtlasExports {
    private static final int MAX_WORK_PER_TICK = 2;
    private static final Map<UUID, Job> PENDING = new HashMap<>();
    private static final ArrayDeque<UUID> ORDER = new ArrayDeque<>();
    private static final Map<UUID, Long> LAST_EXPORT = new HashMap<>();

    private AtlasExports() {}

    private static ItemStack blankMap(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.MAP)) return stack;
        }
        if (player.getOffhandItem().is(Items.MAP)) return player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    public static void export(ServerPlayer player, MapNetwork.ExportMap request) {
        ServerLevel level = player.serverLevel();
        long tick = level.getGameTime();
        Long last = LAST_EXPORT.get(player.getUUID());
        if (last != null && tick >= last && tick - last < 15) {
            AtlasActions.reply(player, false, "拓印请求过于频繁，请稍后再试");
            return;
        }
        LAST_EXPORT.put(player.getUUID(), tick);
        if (PENDING.containsKey(player.getUUID())) {
            AtlasActions.reply(player, false, "上一份地图正在拓印，完成后再试");
            return;
        }
        if (Math.abs((long) request.pageX()) > 234375 ||
                Math.abs((long) request.pageZ()) > 234375) {
            AtlasActions.reply(player, false, "地图坐标超出允许范围");
            return;
        }
        ItemStack atlas = AtlasMapServer.findAtlas(player, request.atlasId());
        if (atlas.isEmpty()) {
            AtlasActions.reply(player, false, "背包中未找到这本地图册");
            return;
        }
        AtlasWorldData.Entry entry = AtlasWorldData.get(level).access(atlas);
        String dim = level.dimension().location().toString();
        if (!entry.isCovered(dim, request.pageX(), request.pageZ())) {
            AtlasActions.reply(player, false, "这张地图页还未购买");
            return;
        }
        if (blankMap(player).isEmpty() && !player.getAbilities().instabuild) {
            AtlasActions.reply(player, false, "缺少原版空地图：拓印需要消耗 1 张");
            return;
        }
        Job job = new Job(entry.state().id(), dim, request.pageX(), request.pageZ());
        PENDING.put(player.getUUID(), job);
        ORDER.addLast(player.getUUID());
        AtlasActions.reply(player, true, "已开始拓印；正在核验 64 个已生成区块");
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (int i = 0; i < MAX_WORK_PER_TICK && !ORDER.isEmpty(); i++) {
            UUID uuid = ORDER.removeFirst();
            Job job = PENDING.get(uuid);
            if (job == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || !player.serverLevel().dimension().location().toString()
                    .equals(job.dimension)) {
                PENDING.remove(uuid);
                continue;
            }
            ItemStack atlas = AtlasMapServer.findAtlas(player, job.atlasId);
            if (atlas.isEmpty()) {
                PENDING.remove(uuid);
                AtlasActions.reply(player, false, "拓印取消：地图册已不在背包");
                continue;
            }
            ServerLevel level = player.serverLevel();
            AtlasWorldData data = AtlasWorldData.get(level);
            AtlasWorldData.Entry entry = data.access(atlas);
            if (!entry.isCovered(job.dimension, job.pageX, job.pageZ)) {
                PENDING.remove(uuid);
                AtlasActions.reply(player, false, "拓印取消：该地图页已失效");
                continue;
            }
            int index = job.next++;
            int chunkX = job.pageX * 8 + index % 8;
            int chunkZ = job.pageZ * 8 + index / 8;
            SurveyLedger.ChunkKey key = new SurveyLedger.ChunkKey(job.dimension, chunkX, chunkZ);
            if (entry.paid(key)) {
                var sample = SurfaceSampler.surface(level, chunkX, chunkZ);
                if (sample.isPresent()) {
                    SurfaceSampler.SurfaceTile tile = sample.get();
                    data.sample(entry, key, tile.colors(), tile.features(), atlas); // existing paid -> free
                    boolean colorized = entry.isColored(key);
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int mapColorId = Byte.toUnsignedInt(tile.colors()[x + z * 16]);
                            if (mapColorId == 0 || mapColorId > 64) continue;
                            MapColor color = MapColor.byId(mapColorId - 1);
                            if (!colorized) {
                                int rgb = color.col;
                                int luminance = (((rgb >>> 16) & 255) * 30 +
                                        ((rgb >>> 8) & 255) * 59 + (rgb & 255) * 11) / 100;
                                color = luminance >= 132 ? MapColor.QUARTZ : MapColor.COLOR_BLACK;
                            }
                            int mapX = (index % 8) * 16 + x;
                            int mapZ = (index / 8) * 16 + z;
                            job.pixels[mapX + mapZ * 128] =
                                    color.getPackedId(MapColor.Brightness.NORMAL);
                            job.painted++;
                        }
                    }
                }
            }
            if (job.next >= 64) {
                PENDING.remove(uuid);
                finish(player, job);
            } else {
                ORDER.addLast(uuid);
            }
        }
    }

    private static void finish(ServerPlayer player, Job job) {
        if (job.painted == 0) {
            AtlasActions.reply(player, false, "该地图页没有可拓印的已测绘区块");
            return;
        }
        ItemStack blank = blankMap(player);
        if (blank.isEmpty() && !player.getAbilities().instabuild) {
            AtlasActions.reply(player, false, "拓印已准备好，但背包中缺少空地图");
            return;
        }
        ServerLevel level = player.serverLevel();
        int originX = job.pageX * 128, originZ = job.pageZ * 128;
        MapItemSavedData data = MapItemSavedData.createFresh(
                originX + 64.0, originZ + 64.0, (byte) 0,
                false, false, level.dimension());
        System.arraycopy(job.pixels, 0, data.colors, 0, job.pixels.length);
        MapId mapId = level.getFreeMapId();
        level.setMapData(mapId, data);
        data.setDirty();
        ItemStack output = new ItemStack(Items.FILLED_MAP);
        output.set(DataComponents.MAP_ID, mapId);
        if (!player.getAbilities().instabuild) blank.shrink(1);
        if (!player.getInventory().add(output)) player.drop(output, false);
        AtlasActions.reply(player, true, "地图拓印完成；消耗 1 张原版空地图");
    }

    private static final class Job {
        final String atlasId, dimension;
        final int pageX, pageZ;
        final byte[] pixels = new byte[128 * 128];
        int next, painted;
        Job(String atlasId, String dimension, int pageX, int pageZ) {
            this.atlasId = atlasId;
            this.dimension = dimension;
            this.pageX = pageX;
            this.pageZ = pageZ;
        }
    }
}
