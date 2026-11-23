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
