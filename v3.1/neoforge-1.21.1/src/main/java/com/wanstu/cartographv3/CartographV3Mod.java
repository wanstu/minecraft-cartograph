package com.wanstu.cartographv3;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.BiConsumer;

@Mod(MapProtocol.MOD_ID)
public final class CartographV3Mod {
    public CartographV3Mod(IEventBus modBus) {
        MapTransport.bindServerSender(PacketDistributor::sendToPlayer);
        modBus.addListener(CartographV3Mod::registerPayloads);
        NeoForge.EVENT_BUS.addListener(CartographV3Mod::chunkSaved);
        NeoForge.EVENT_BUS.addListener(CartographV3Mod::tick);
        NeoForge.EVENT_BUS.addListener(CartographV3Mod::stopped);
    }

    private static void chunkSaved(ChunkDataEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CartographServerEvents.chunkSaved(level, event.getChunk().getPos());
        }
    }

    private static void tick(ServerTickEvent.Post event) {
        CartographServerEvents.tick(event.getServer());
    }

    private static void stopped(ServerStoppedEvent event) {
        CartographServerEvents.stopped(event.getServer());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("8");
        registrar.playToServer(MapProtocol.MapViewState.TYPE, MapProtocol.MapViewState.CODEC,
                (packet, context) -> onServer(packet, context, MapProtocol::handleViewState));
        registrar.playToServer(MapProtocol.RequestWorldInfo.TYPE, MapProtocol.RequestWorldInfo.CODEC,
                (packet, context) -> onServer(packet, context,
                        (player, ignored) -> MapProtocol.replyWorldInfo(player)));
        registrar.playToServer(MapProtocol.TeleportRequest.TYPE, MapProtocol.TeleportRequest.CODEC,
                (packet, context) -> onServer(packet, context, MapProtocol::handleTeleport));
        registrar.playToServer(MapProtocol.RequestTile.TYPE, MapProtocol.RequestTile.CODEC,
                (packet, context) -> onServer(packet, context, TileRequestScheduler::enqueue));

        registrar.playToClient(MapProtocol.WorldInfo.TYPE, MapProtocol.WorldInfo.CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) MapClientCache.accept(packet);
                }));
        registrar.playToClient(MapProtocol.Tile.TYPE, MapProtocol.Tile.CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) MapClientCache.accept(packet);
                }));
    }

    private static <T extends CustomPacketPayload> void onServer(
            T packet, IPayloadContext context, BiConsumer<ServerPlayer, T> handler) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) handler.accept(player, packet);
        });
    }
}
