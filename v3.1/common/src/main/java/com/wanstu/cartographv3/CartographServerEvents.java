package com.wanstu.cartographv3;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class CartographServerEvents {
    private CartographServerEvents() {}

    public static void chunkSaved(ServerLevel level, ChunkPos pos) {
        SurfaceService.invalidate(level, pos);
        StructureDiscovery.invalidate(level, pos);
        GeneratedChunkBackfill.chunkSaved(level, pos);
        // A save is the fallback for biome edits and mods bypassing block mutation hooks.
        chunkLoaded(level, pos);
    }

    public static void chunkChanged(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) return;
        if (level.getServer().isSameThread()) applyMutation(level, chunk);
        else level.getServer().execute(() -> applyMutation(level, chunk));
    }

    private static void applyMutation(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        SurfaceService.loadedChanged(chunk);
        if (LiveSurfaceUpdates.active()) LiveSurfaceUpdates.changed(level, chunk.getPos(), true);
    }

    public static void chunkLoaded(ServerLevel level, ChunkPos pos) {
        if (!LiveSurfaceUpdates.active()) return;
        if (level.getServer().isSameThread()) LiveSurfaceUpdates.changed(level, pos);
        else level.getServer().execute(() -> LiveSurfaceUpdates.changed(level, pos));
    }

    static void tick(MinecraftServer server) {
        TileRequestScheduler.tick(server);
        LiveSurfaceUpdates.tick(server);
        LoadedSurfaceReader.tick(server);
    }

    static void stopped(MinecraftServer server) {
        SurfaceService.clear(server);
        StructureDiscovery.clear(server);
        TileRequestScheduler.clear();
        LiveSurfaceUpdates.clear();
        LoadedSurfaceReader.clear(server);
        WorldIdentity.clear(server);
    }
}
