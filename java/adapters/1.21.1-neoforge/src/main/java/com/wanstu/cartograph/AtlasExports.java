package com.wanstu.cartograph;

import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Converts the already-purchased surveyed page into a vanilla filled map.
 * Consumes exactly one empty vanilla map; the source atlas keeps its page.
 * Never forces chunk generation. Unpaid and unavailable chunks remain blank.
 */
public final class AtlasExports {
    private static final Map<UUID, Long> LAST_EXPORT = new HashMap<>();
    private AtlasExports() {}

    public static void export(ServerPlayer player, MapNetwork.ExportMap request) {
        ServerLevel level = player.serverLevel();
        long currentTick = level.getGameTime();
        Long last = LAST_EXPORT.get(player.getUUID());
        if (last != null && currentTick >= last && currentTick - last < 30) return;
        LAST_EXPORT.put(player.getUUID(), currentTick);

        if (Math.abs((long) request.pageX()) > 234375 || Math.abs((long) request.pageZ()) > 234375)
            return;
        ItemStack atlas = AtlasMapServer.findAtlas(player, request.atlasId());
        if (atlas.isEmpty()) return;
        AtlasWorldData data = AtlasWorldData.get(level);
        AtlasWorldData.Entry entry = data.access(atlas);
        String dim = level.dimension().location().toString();
        if (!entry.isCovered(dim, request.pageX(), request.pageZ())) return;

        ItemStack blank = ItemStack.EMPTY;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(Items.MAP)) { blank = stack; break; }
        }
        if (blank.isEmpty() && player.getOffhandItem().is(Items.MAP)) blank = player.getOffhandItem();
        if (blank.isEmpty() && !player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("cartograph.message.map_needed"), true);
            return;
        }

        int blockOriginX = request.pageX() * 128, blockOriginZ = request.pageZ() * 128;
        MapItemSavedData copy = MapItemSavedData.createFresh(
                blockOriginX + 64.0, blockOriginZ + 64.0, (byte) 0,
                false, false, level.dimension());
        int painted = 0;

        for (int cz = 0; cz < 8; cz++) {
            for (int cx = 0; cx < 8; cx++) {
                int chunkX = request.pageX() * 8 + cx;
                int chunkZ = request.pageZ() * 8 + cz;
                var key = new SurveyLedger.ChunkKey(dim, chunkX, chunkZ);
                if (!entry.paid(key)) continue;
                // Re-read actual world. If a chunk was erased, do not export stale terrain.
                var sampled = SurfaceSampler.surface(level, chunkX, chunkZ);
                if (sampled.isEmpty()) continue;
                byte[] colorIds = sampled.get().colors();
                data.sample(entry, key, colorIds, sampled.get().features(), atlas); // already paid -> free refresh

                boolean colored = entry.isColored(key);
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        int value = Byte.toUnsignedInt(colorIds[localX + localZ * 16]);
                        if (value == 0 || value > 64) continue;
                        MapColor color = MapColor.byId(value - 1);
                        if (!colored) {
                            int rgb = color.col;
                            int lum = (((rgb >>> 16) & 255) * 30 + ((rgb >>> 8) & 255) * 59
                                    + (rgb & 255) * 11) / 100;
                            color = lum >= 132 ? MapColor.QUARTZ : MapColor.COLOR_BLACK;
                        }
                        int dstX = cx * 16 + localX, dstZ = cz * 16 + localZ;
                        copy.colors[dstX + dstZ * 128] = color.getPackedId(MapColor.Brightness.NORMAL);
                        painted++;
                    }
                }
            }
        }
        if (painted == 0) {
            player.displayClientMessage(Component.translatable("cartograph.message.map_empty"), true);
            return;
        }

        MapId mapId = level.getFreeMapId();
        level.setMapData(mapId, copy);
        copy.setDirty();
        ItemStack result = new ItemStack(Items.FILLED_MAP);
        result.set(DataComponents.MAP_ID, mapId);
        if (!player.getAbilities().instabuild) blank.shrink(1);
        if (!player.getInventory().add(result)) player.drop(result, false);
        player.displayClientMessage(Component.translatable("cartograph.message.map_exported"), true);
    }
}
