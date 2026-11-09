package com.wanstu.cartograph;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Server-authoritative page requests; never accepts raw chunk pixels from the client.
 * Packet size is a single 128x128 map page, capped at 16,384 source bytes.
 */
public final class MapNetwork {
    private MapNetwork() {}

    public record Request(int pageX, int pageZ) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "request_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeInt(value.pageX); buf.writeInt(value.pageZ); },
                buf -> new Request(buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Page(String dimension, int pageX, int pageZ, byte[] pixels, long coloredChunks,
                       int inkUnits, int pigmentUnits, int glowUnits, boolean compass,
                       boolean inRange) implements CustomPacketPayload {
        public static final Type<Page> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.dimension, 128);
                    buf.writeInt(value.pageX); buf.writeInt(value.pageZ);
                    buf.writeByteArray(value.pixels);
                    buf.writeLong(value.coloredChunks);
                    buf.writeInt(value.inkUnits); buf.writeInt(value.pigmentUnits);
                    buf.writeInt(value.glowUnits);
                    buf.writeBoolean(value.compass); buf.writeBoolean(value.inRange);
                },
                buf -> new Page(buf.readUtf(128), buf.readInt(), buf.readInt(), buf.readByteArray(128 * 128),
                        buf.readLong(), buf.readInt(), buf.readInt(), buf.readInt(),
                        buf.readBoolean(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) AtlasMapServer.enqueue(player, request);
        });
        registrar.playToClient(Page.TYPE, Page.CODEC, (page, ctx) -> {
            if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptPage(page);
        });
    }
}
