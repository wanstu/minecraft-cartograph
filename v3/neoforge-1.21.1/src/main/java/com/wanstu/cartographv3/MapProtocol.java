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

    public record RequestWorldInfo() implements CustomPacketPayload {
        public static final Type<RequestWorldInfo> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CartographV3Mod.MOD_ID, "request_world_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestWorldInfo> CODEC =
                StreamCodec.of((buf, value) -> {}, buf -> new RequestWorldInfo());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record WorldInfo(String worldId, String dimension) implements CustomPacketPayload {
        public static final Type<WorldInfo> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CartographV3Mod.MOD_ID, "world_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldInfo> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.worldId(), 36);
                            buf.writeUtf(value.dimension(), 128);
                        },
                        buf -> new WorldInfo(buf.readUtf(36), buf.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestTile(String dimension, int chunkX, int chunkZ,
                              boolean hasKnownFingerprint, long knownFingerprint)
            implements CustomPacketPayload {
        public static final Type<RequestTile> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CartographV3Mod.MOD_ID, "request_tile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestTile> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.dimension(), 128);
                            buf.writeInt(value.chunkX());
                            buf.writeInt(value.chunkZ());
                            buf.writeBoolean(value.hasKnownFingerprint());
                            buf.writeLong(value.knownFingerprint());
                        },
                        buf -> new RequestTile(buf.readUtf(128), buf.readInt(), buf.readInt(),
                                buf.readBoolean(), buf.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Tile(String dimension, int chunkX, int chunkZ,
                       boolean present, boolean changed, int[] rgb)
            implements CustomPacketPayload {
        public Tile {
            if (dimension == null || dimension.isBlank() || dimension.length() > 128)
                throw new IllegalArgumentException("Invalid dimension");
            int expected = present && changed ? SurfaceTile.PIXELS : 0;
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
                            buf.writeBoolean(value.changed());
                            if (value.present() && value.changed()) {
                                for (int color : value.rgb()) buf.writeInt(color);
                            }
                        },
                        buf -> {
                            String dimension = buf.readUtf(128);
                            int x = buf.readInt();
                            int z = buf.readInt();
                            boolean present = buf.readBoolean();
                            boolean changed = buf.readBoolean();
                            int[] rgb = new int[present && changed ? SurfaceTile.PIXELS : 0];
                            for (int i = 0; i < rgb.length; i++) rgb[i] = buf.readInt();
                            return new Tile(dimension, x, z, present, changed, rgb);
                        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToServer(RequestWorldInfo.TYPE, RequestWorldInfo.CODEC, (request, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    PacketDistributor.sendToPlayer(player, new WorldInfo(
                            WorldIdentity.get(player.server).toString(),
                            player.serverLevel().dimension().location().toString()));
                }));
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
                                if (error != null) {
                                    if (request.hasKnownFingerprint()) {
                                        PacketDistributor.sendToPlayer(player,
                                                new Tile(request.dimension(), request.chunkX(),
                                                        request.chunkZ(), true, false, new int[0]));
                                    } else {
                                        PacketDistributor.sendToPlayer(player,
                                                new Tile(request.dimension(), request.chunkX(),
                                                        request.chunkZ(), false, false, new int[0]));
                                    }
                                    return;
                                }
                                if (surface == null || surface.isEmpty()) {
                                    PacketDistributor.sendToPlayer(player,
                                            new Tile(request.dimension(), request.chunkX(),
                                                    request.chunkZ(), false, false, new int[0]));
                                    return;
                                }
                                SurfaceTile tile = surface.get();
                                boolean unchanged = request.hasKnownFingerprint()
                                        && request.knownFingerprint() == tile.fingerprint64();
                                PacketDistributor.sendToPlayer(player,
                                        new Tile(tile.key().dimension(), tile.key().x(), tile.key().z(),
                                                true, !unchanged,
                                                unchanged ? new int[0] : tile.rgb()));
                            }));
                }));

        registrar.playToClient(WorldInfo.TYPE, WorldInfo.CODEC, (info, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) MapClientCache.accept(info);
                }));
        registrar.playToClient(Tile.TYPE, Tile.CODEC, (tile, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) MapClientCache.accept(tile);
                }));
    }
}
