package com.wanstu.cartographv3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FabricCartographClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MapTransport.bindClientSender(packet -> {
            if (ClientPlayNetworking.canSend(packet.type())) ClientPlayNetworking.send(packet);
        });
        ClientPlayNetworking.registerGlobalReceiver(MapProtocol.WorldInfo.TYPE,
                (packet, context) -> MapClientCache.accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(MapProtocol.BackfillCheckpoint.TYPE,
                (packet, context) -> MapClientCache.accept(packet));
        ClientPlayNetworking.registerGlobalReceiver(MapProtocol.Tile.TYPE,
                (packet, context) -> MapClientCache.accept(packet));

        KeyMappingHelper.registerKeyMapping(CartographClient.OPEN_KEY);
        ClientTickEvents.END_CLIENT_TICK.register(CartographClient::tick);
    }
}
