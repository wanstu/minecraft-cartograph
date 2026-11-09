package com.wanstu.cartograph;

import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import java.util.List;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Two sampled chunks per server tick across all viewers. Does not load or
 * generate any chunks. A viewer may keep only one pending page at a time;
 * requests cannot flood a server with scans, and only covered pages are
 * eligible for a response.
 */
public final class AtlasMapServer {
    private static final int MAX_WORK_PER_TICK = 2;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_ACTION = new HashMap<>();
    private static int ticks;

    private AtlasMapServer() {}

    public static ItemStack findAtlas(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(CartographMod.ATLAS.get())) return main;
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(CartographMod.ATLAS.get())) return offhand;
        for (ItemStack stack : player.getInventory().items)
            if (stack.is(CartographMod.ATLAS.get())) return stack;
        return ItemStack.EMPTY;
    }

    public static ItemStack findAtlas(ServerPlayer player, String atlasId) {
        if (atlasId == null || atlasId.isBlank()) {
            for (ItemStack stack : player.getInventory().items) {
                if (stack.is(CartographMod.ATLAS.get())
                        && stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                        AtlasState.blank()).id().isBlank()) return stack;
            }
            for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
                if (stack.is(CartographMod.ATLAS.get())
                        && stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                        AtlasState.blank()).id().isBlank()) return stack;
            }
            return ItemStack.EMPTY;
        }
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            if (stack.is(CartographMod.ATLAS.get())
                    && atlasId.equals(stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                    AtlasState.blank()).id())) return stack;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(CartographMod.ATLAS.get())
                    && atlasId.equals(stack.getOrDefault(CartographMod.ATLAS_STATE.get(),
                    AtlasState.blank()).id())) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static void enqueue(ServerPlayer player, MapNetwork.Request request) {
        if (Math.abs((long) request.pageX()) > 234375 || Math.abs((long) request.pageZ()) > 234375) return;
        ItemStack stack = findAtlas(player, request.atlasId());
        if (stack.isEmpty()) return;
        long tick = player.serverLevel().getGameTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && tick - last < 25 && tick >= last) return;
        LAST_REQUEST.put(player.getUUID(), tick);
        var data = AtlasWorldData.get(player.serverLevel());
        var entry = data.access(stack);
        PacketDistributor.sendToPlayer(player, AtlasActions.settings(player));
        String dim = player.serverLevel().dimension().location().toString();
        if (!entry.anchored()) {
            // First use anchors the atlas at the player's present map page.
            var origin = AtlasPage.atBlock(dim, player.getBlockX(), player.getBlockZ());
            data.anchor(entry, dim, origin.pageX(), origin.pageZ());
        }
        if (!entry.isCovered(dim, request.pageX(), request.pageZ())) {
            PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                    entry.state().id(), dim, request.pageX(), request.pageZ(),
                    new byte[128 * 128], new byte[128 * 128], 0L,
                    entry.state().inkUnits(), entry.state().pigmentUnits(), entry.state().glowUnits(),
                    entry.state().compassInstalled(), false, List.of(), List.of()));
            return;
        }
        PENDING.put(player.getUUID(), new Pending(entry.state().id(), request.pageX(), request.pageZ(), dim));
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // Prevent indefinite per-player rate-limit state on long-running servers.
        if (++ticks % 600 == 0) {
            LAST_REQUEST.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            LAST_ACTION.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
        int budget = MAX_WORK_PER_TICK;
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            Map.Entry<UUID, Pending> task = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(task.getKey());
            if (player == null || !player.serverLevel().dimension().location().toString().equals(task.getValue().dimension)) {
                iterator.remove(); continue;
            }
            ItemStack item = findAtlas(player, task.getValue().atlasId);
            if (item.isEmpty()) { iterator.remove(); continue; }
            var data = AtlasWorldData.get(player.serverLevel());
            var entry = data.access(item);
            if (!entry.isCovered(task.getValue().dimension, task.getValue().pageX, task.getValue().pageZ)) {
                iterator.remove(); continue;
            }
            Pending current = task.getValue();
            int index = current.next++;
            int cx = current.pageX * AtlasPage.CHUNKS_PER_SIDE + (index % 8);
            int cz = current.pageZ * AtlasPage.CHUNKS_PER_SIDE + (index / 8);
            var key = new SurveyLedger.ChunkKey(current.dimension, cx, cz);
            var sample = SurfaceSampler.surface(player.serverLevel(), cx, cz);
            sample.ifPresent(tile -> data.sample(entry, key, tile.colors(), tile.features(), item));
            byte[] savedColors = entry.getPixels(key);
            // A deleted/unreadable save must display as unknown even if the
            // atlas paid for and cached that chunk earlier. Its ledger remains
            // intact so rebuilding the chunk won't charge for a second time.
            if (sample.isPresent() && savedColors != null && entry.paid(key)) {
                int x = index % 8, z = index / 8;
                for (int pz = 0; pz < 16; pz++) {
                    System.arraycopy(savedColors, pz * 16, current.pixels,
                            (z * 16 + pz) * 128 + x * 16, 16);
                }
                byte[] savedFeatures = entry.getFeatures(key);
                if (savedFeatures != null && savedFeatures.length == 256) {
                    for (int pz = 0; pz < 16; pz++) {
                        System.arraycopy(savedFeatures, pz * 16, current.features,
                                (z * 16 + pz) * 128 + x * 16, 16);
                    }
                }
                // Auto markers are derived from the actually observed FULL
                // chunk and never saved as user-selected waypoint records.
                if (sample.isPresent()) {
                    for (SurfaceStructure marker : sample.get().structures()) {
                        if (current.structures.size() >= 64) break;
                        current.structures.add(marker);
                    }
                }
                if (entry.isColored(key)) current.coloredChunks |= 1L << index;
            }
            budget--;
            if (current.next == 64) {
                PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                        current.atlasId, current.dimension, current.pageX, current.pageZ,
                        current.pixels, current.features, current.coloredChunks,
                        entry.state().inkUnits(), entry.state().pigmentUnits(), entry.state().glowUnits(),
                        entry.state().compassInstalled(), true,
                        entry.markersOnPage(current.dimension, current.pageX, current.pageZ),
                        java.util.List.copyOf(current.structures)));
                iterator.remove();
            }
        }
    }

    private static final class Pending {
        final String atlasId;
        final int pageX, pageZ;
        final String dimension;
        final byte[] pixels = new byte[128 * 128];
        final byte[] features = new byte[128 * 128];
        final java.util.List<SurfaceStructure> structures = new java.util.ArrayList<>();
        int next;
        long coloredChunks;
        Pending(String atlasId, int pageX, int pageZ, String dimension) {
            this.atlasId = atlasId; this.pageX = pageX; this.pageZ = pageZ; this.dimension = dimension;
        }
    }
}
