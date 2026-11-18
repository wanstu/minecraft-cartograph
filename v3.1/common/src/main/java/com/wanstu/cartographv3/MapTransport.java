package com.wanstu.cartographv3;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class MapTransport {
    private static volatile Consumer<CustomPacketPayload> serverbound;
    private static volatile BiConsumer<ServerPlayer, CustomPacketPayload> clientbound;

    private MapTransport() {}

    static void bindClientSender(Consumer<CustomPacketPayload> sender) {
        serverbound = sender;
    }

    static void bindServerSender(BiConsumer<ServerPlayer, CustomPacketPayload> sender) {
        clientbound = sender;
    }

    static void sendToServer(CustomPacketPayload payload) {
        serverbound.accept(payload);
    }

    static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        clientbound.accept(player, payload);
    }
}
