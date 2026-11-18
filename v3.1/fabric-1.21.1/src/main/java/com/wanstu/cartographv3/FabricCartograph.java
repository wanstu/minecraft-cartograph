package com.wanstu.cartographv3;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricCartograph implements ModInitializer {
    @Override
    public void onInitialize() {
        registerPayloads();
        ServerTickEvents.END_SERVER_TICK.register(CartographServerEvents::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(CartographServerEvents::stopped);
    }

    private static void registerPayloads() {
        MapTransport.bindServerSender((player, packet) -> {
            if (ServerPlayNetworking.canSend(player, packet.type())) {
                ServerPlayNetworking.send(player, packet);
            }
        });

        var toServer = PayloadTypeRegistry.playC2S();
        toServer.register(MapProtocol.MapViewState.TYPE, MapProtocol.MapViewState.CODEC);
        toServer.register(MapProtocol.RequestWorldInfo.TYPE, MapProtocol.RequestWorldInfo.CODEC);
        toServer.register(MapProtocol.TeleportRequest.TYPE, MapProtocol.TeleportRequest.CODEC);
        toServer.register(MapProtocol.RequestTile.TYPE, MapProtocol.RequestTile.CODEC);

        var toClient = PayloadTypeRegistry.playS2C();
        toClient.register(MapProtocol.WorldInfo.TYPE, MapProtocol.WorldInfo.CODEC);
        toClient.register(MapProtocol.Tile.TYPE, MapProtocol.Tile.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MapProtocol.MapViewState.TYPE,
                (packet, context) -> MapProtocol.handleViewState(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(MapProtocol.RequestWorldInfo.TYPE,
                (packet, context) -> MapProtocol.replyWorldInfo(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(MapProtocol.TeleportRequest.TYPE,
                (packet, context) -> MapProtocol.handleTeleport(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(MapProtocol.RequestTile.TYPE,
                (packet, context) -> TileRequestScheduler.enqueue(context.player(), packet));
    }
}
