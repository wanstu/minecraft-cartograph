package com.wanstu.cartographv3;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class MapProtocol {
    private MapProtocol() {}

    public record RequestTile(String dimension, int chunkX, int chunkZ)
            implements CustomPacketPayload {
        public static final Type<RequestTile> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CartographV3Mod.MOD_ID, "request_tile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestTile> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.dimension(), 128);
                            buf.writeInt(value.chunkX());
                            buf.writeInt(value.chunkZ());
                        },
                        buf -> new RequestTile(buf.readUtf(128), buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Tile(String dimension, int chunkX, int chunkZ, boolean present, int[] rgb)
            implements CustomPacketPayload {
        public Tile {
            if (dimension == null || dimension.isBlank() || dimension.length() > 128)
                throw new IllegalArgumentException("Invalid dimension");
            int expected = present ? SurfaceTile.PIXELS : 0;
            if (rgb == null || rgb.length != expected)
                throw new IllegalArgumentException("Invalid tile payload");
            rgb = rgb.clone();
        }
        @Override public int[] rgb() { return rgb.clone(); }

        public static final Type<Tile> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CartographV3Mod.MOD_ID, "tile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Tile> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.dimension(), 128);
                            buf.writeInt(value.chunkX());
                            buf.writeInt(value.chunkZ());
                            buf.writeBoolean(value.present());
                            if (value.present()) {
                                for (int color : value.rgb()) buf.writeInt(color);
                            }
                        },
                        buf -> {
                            String dimension = buf.readUtf(128);
                            int x = buf.readInt();
                            int z = buf.readInt();
                            boolean present = buf.readBoolean();
                            int[] rgb = new int[present ? SurfaceTile.PIXELS : 0];
                            for (int i = 0; i < rgb.length; i++) rgb[i] = buf.readInt();
                            return new Tile(dimension, x, z, present, rgb);
                        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(RequestTile.TYPE, RequestTile.CODEC, (request, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    String current = player.serverLevel().dimension().location().toString();
                    if (!current.equals(request.dimension())) return;

                    SurfaceService.request(player.serverLevel(), request.chunkX(), request.chunkZ())
                            .whenComplete((surface, error) -> player.server.execute(() -> {
                                if (!player.isAlive() || player.serverLevel() == null) return;
                                if (!player.serverLevel().dimension().location().toString()
                                        .equals(request.dimension())) return;
                                if (error != null || surface == null || surface.isEmpty()) {
                                    PacketDistributor.sendToPlayer(player,
                                            new Tile(request.dimension(), request.chunkX(),
                                                    request.chunkZ(), false, new int[0]));
                                    return;
                                }
                                SurfaceTile tile = surface.get();
                                PacketDistributor.sendToPlayer(player,
                                        new Tile(tile.key().dimension(), tile.key().x(), tile.key().z(),
                                                true, tile.rgb()));
                            }));
                }));

        registrar.playToClient(Tile.TYPE, Tile.CODEC, (tile, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) MapClientCache.accept(tile);
                }));
    }
}
