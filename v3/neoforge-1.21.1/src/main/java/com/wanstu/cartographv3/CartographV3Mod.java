package com.wanstu.cartographv3;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(CartographV3Mod.MOD_ID)
public final class CartographV3Mod {
    public static final String MOD_ID = "cartograph_v3";

    public CartographV3Mod(IEventBus modBus) {
        modBus.addListener(MapProtocol::register);
        NeoForge.EVENT_BUS.addListener(CartographV3Mod::chunkSaved);
        NeoForge.EVENT_BUS.addListener(CartographV3Mod::serverStopped);
    }

    private static void chunkSaved(ChunkDataEvent.Save event) {
        SurfaceService.invalidate(event);
    }

    private static void serverStopped(ServerStoppedEvent event) {
        SurfaceService.clear(event.getServer());
    }
}
