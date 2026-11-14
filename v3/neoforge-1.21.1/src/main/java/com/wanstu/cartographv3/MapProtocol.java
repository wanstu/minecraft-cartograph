package com.wanstu.cartographv3;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
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

    public record TeleportRequest(String dimension, int blockX, int blockZ)
            implements CustomPacketPayload {
        public static final Type<TeleportRequest> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CartographV3Mod.MOD_ID, "teleport"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TeleportRequest> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.dimension(), 128);
                            buf.writeInt(value.blockX());
                            buf.writeInt(value.blockZ());
                        },
                        buf -> new TeleportRequest(
                                buf.readUtf(128), buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Tile(
            String dimension,
            int chunkX,
            int chunkZ,
            boolean present,
            boolean changed,
            boolean retry,
            int[] stateIds,
            int[] biomeIds,
            short[] surfaceHeights,
            short[] reliefHeights,
            byte[] waterDepths) implements CustomPacketPayload {

        public Tile {
            if (dimension == null || dimension.isBlank() || dimension.length() > 128)
                throw new IllegalArgumentException("Invalid dimension");
            int expected = present && changed ? SurfaceTile.PIXELS : 0;
            if (stateIds == null || biomeIds == null
                    || surfaceHeights == null || reliefHeights == null || waterDepths == null
                    || stateIds.length != expected || biomeIds.length != expected
                    || surfaceHeights.length != expected || reliefHeights.length != expected
                    || waterDepths.length != expected) {
                throw new IllegalArgumentException("Invalid tile payload");
            }
            stateIds = stateIds.clone();
            biomeIds = biomeIds.clone();
            surfaceHeights = surfaceHeights.clone();
            reliefHeights = reliefHeights.clone();
            waterDepths = waterDepths.clone();
        }

        static Tile status(String dimension, int chunkX, int chunkZ,
                           boolean present, boolean changed, boolean retry) {
            return new Tile(
                    dimension, chunkX, chunkZ, present, changed, retry,
                    new int[0], new int[0], new short[0], new short[0], new byte[0]);
        }

        static Tile changed(SurfaceTile tile) {
            if (!tile.hasSurfaceFacts())
                throw new IllegalArgumentException("Changed tile has no surface facts");
            return new Tile(
                    tile.key().dimension(), tile.key().x(), tile.key().z(),
                    true, true, false,
                    tile.stateIds(), tile.biomeIds(),
                    tile.surfaceHeights(), tile.reliefHeights(), tile.waterDepths());
        }

        @Override public int[] stateIds() { return stateIds.clone(); }
        @Override public int[] biomeIds() { return biomeIds.clone(); }
        @Override public short[] surfaceHeights() { return surfaceHeights.clone(); }
        @Override public short[] reliefHeights() { return reliefHeights.clone(); }
        @Override public byte[] waterDepths() { return waterDepths.clone(); }

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
                            buf.writeBoolean(value.retry());
                            if (!value.present() || !value.changed()) return;

                            int[] states = value.stateIds;
                            int[] biomes = value.biomeIds;
                            short[] surface = value.surfaceHeights;
                            short[] relief = value.reliefHeights;
                            byte[] water = value.waterDepths;
                            for (int i = 0; i < SurfaceTile.PIXELS; i++) {
                                buf.writeVarInt(states[i] + 1);
                                buf.writeVarInt(biomes[i] + 1);
                                buf.writeShort(surface[i]);
                                buf.writeShort(relief[i]);
                                buf.writeByte(water[i]);
                            }
                        },
                        buf -> {
                            String dimension = buf.readUtf(128);
                            int x = buf.readInt();
                            int z = buf.readInt();
                            boolean present = buf.readBoolean();
                            boolean changed = buf.readBoolean();
                            boolean retry = buf.readBoolean();
                            if (!present || !changed) {
                                return Tile.status(dimension, x, z, present, changed, retry);
                            }

                            int[] states = new int[SurfaceTile.PIXELS];
                            int[] biomes = new int[SurfaceTile.PIXELS];
                            short[] surface = new short[SurfaceTile.PIXELS];
                            short[] relief = new short[SurfaceTile.PIXELS];
                            byte[] water = new byte[SurfaceTile.PIXELS];
                            for (int i = 0; i < SurfaceTile.PIXELS; i++) {
                                states[i] = buf.readVarInt() - 1;
                                biomes[i] = buf.readVarInt() - 1;
                                surface[i] = buf.readShort();
                                relief[i] = buf.readShort();
                                water[i] = buf.readByte();
                            }
                            return new Tile(
                                    dimension, x, z, true, true, retry,
                                    states, biomes, surface, relief, water);
                        });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void pushChanged(ServerPlayer player, SurfaceTile tile) {
        PacketDistributor.sendToPlayer(player, Tile.changed(tile));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("6");
        registrar.playToServer(RequestWorldInfo.TYPE, RequestWorldInfo.CODEC, (request, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    PacketDistributor.sendToPlayer(player, new WorldInfo(
                            WorldIdentity.get(player.server).toString(),
                            player.serverLevel().dimension().location().toString()));
                }));
        registrar.playToServer(TeleportRequest.TYPE, TeleportRequest.CODEC, (request, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    var level = player.serverLevel();
                    if (!level.dimension().location().toString().equals(request.dimension())) return;
                    if (!player.server.isSingleplayerOwner(player.getGameProfile())
                            && !player.hasPermissions(2)) return;

                    int x = request.blockX();
                    int z = request.blockZ();
                    int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
                    player.teleportTo(
                            level, x + 0.5, y, z + 0.5,
                            player.getYRot(), player.getXRot());
                }));

        registrar.playToServer(RequestTile.TYPE, RequestTile.CODEC, (request, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)) return;
                    String current = player.serverLevel().dimension().location().toString();
                    if (!current.equals(request.dimension())) return;

                    ChunkKey watchedKey = new ChunkKey(
                            request.dimension(), request.chunkX(), request.chunkZ());
                    LiveSurfaceUpdates.watch(player, watchedKey);

                    SurfaceService.request(player.serverLevel(), request.chunkX(), request.chunkZ())
                            .whenComplete((surface, error) -> player.server.execute(() -> {
                                if (!player.isAlive() || player.serverLevel() == null) return;
                                if (!player.serverLevel().dimension().location().toString()
                                        .equals(request.dimension())) return;
                                if (error != null) {
                                    PacketDistributor.sendToPlayer(player,
                                            Tile.status(
                                                    request.dimension(), request.chunkX(),
                                                    request.chunkZ(),
                                                    request.hasKnownFingerprint(),
                                                    false, true));
                                    return;
                                }
                                if (surface == null || surface.isEmpty()) {
                                    PacketDistributor.sendToPlayer(player,
                                            Tile.status(
                                                    request.dimension(), request.chunkX(),
                                                    request.chunkZ(),
                                                    false, false, false));
                                    return;
                                }
                                SurfaceTile tile = surface.get();
                                boolean unchanged = request.hasKnownFingerprint()
                                        && request.knownFingerprint() == tile.fingerprint64();
                                PacketDistributor.sendToPlayer(player,
                                        unchanged
                                                ? Tile.status(
                                                        tile.key().dimension(),
                                                        tile.key().x(), tile.key().z(),
                                                        true, false, false)
                                                : Tile.changed(tile));
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
