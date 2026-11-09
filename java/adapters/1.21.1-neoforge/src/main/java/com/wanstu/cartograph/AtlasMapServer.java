package com.wanstu.cartograph;

import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.SurveyLedger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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

    public static void enqueue(ServerPlayer player, MapNetwork.Request request) {
        if (Math.abs((long) request.pageX()) > 234375 || Math.abs((long) request.pageZ()) > 234375) return;
        ItemStack stack = findAtlas(player);
        if (stack.isEmpty()) return;
        long tick = player.serverLevel().getGameTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && tick - last < 25 && tick >= last) return;
        LAST_REQUEST.put(player.getUUID(), tick);
        var data = AtlasWorldData.get(player.serverLevel());
        var entry = data.access(stack);
        String dim = player.serverLevel().dimension().location().toString();
        if (!entry.anchored()) {
            // First use anchors the atlas at the player's present map page.
            var origin = AtlasPage.atBlock(dim, player.getBlockX(), player.getBlockZ());
            data.anchor(entry, dim, origin.pageX(), origin.pageZ());
        }
        if (!entry.isCovered(dim, request.pageX(), request.pageZ())) {
            PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                    dim, request.pageX(), request.pageZ(), new byte[128 * 128], 0L,
                    entry.state().inkUnits(), entry.state().pigmentUnits(), entry.state().glowUnits(),
                    entry.state().compassInstalled(), false));
            return;
        }
        PENDING.put(player.getUUID(), new Pending(player.getUUID(), request.pageX(), request.pageZ(), dim));
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        // Prevent indefinite per-player rate-limit state on long-running servers.
        if (++ticks % 600 == 0) {
            LAST_REQUEST.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
        int budget = MAX_WORK_PER_TICK;
        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            Map.Entry<UUID, Pending> task = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(task.getKey());
            if (player == null || !player.serverLevel().dimension().location().toString().equals(task.getValue().dimension)) {
                iterator.remove(); continue;
            }
            ItemStack item = findAtlas(player);
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
            sample.ifPresent(colors -> data.sample(entry, key, colors, item));
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
                if (entry.isColored(key)) current.coloredChunks |= 1L << index;
            }
            budget--;
            if (current.next == 64) {
                PacketDistributor.sendToPlayer(player, new MapNetwork.Page(
                        current.dimension, current.pageX, current.pageZ, current.pixels, current.coloredChunks,
                        entry.state().inkUnits(), entry.state().pigmentUnits(), entry.state().glowUnits(),
                        entry.state().compassInstalled(), true));
                iterator.remove();
            }
        }
    }

    private static final class Pending {
        final UUID player;
        final int pageX, pageZ;
        final String dimension;
        final byte[] pixels = new byte[128 * 128];
        int next;
        long coloredChunks;
        Pending(UUID player, int pageX, int pageZ, String dimension) {
            this.player = player; this.pageX = pageX; this.pageZ = pageZ; this.dimension = dimension;
        }
    }
}
