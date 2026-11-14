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

    public record StructureHit(
            String structureId, int startChunkX, int startChunkZ, int x, int z) {
        public StructureHit {
            if (structureId == null || structureId.isBlank() || structureId.length() > 256)
                throw new IllegalArgumentException("Invalid structure id");
        }
    }

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
            byte[] waterDepths,
            java.util.List<StructureHit> structures) implements CustomPacketPayload {

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
            structures = structures == null ? java.util.List.of() : java.util.List.copyOf(structures);
        }

        static Tile status(String dimension, int chunkX, int chunkZ,
                           boolean present, boolean changed, boolean retry,
                           java.util.List<StructureHit> structures) {
            return new Tile(
                    dimension, chunkX, chunkZ, present, changed, retry,
                    new int[0], new int[0], new short[0], new short[0], new byte[0],
                    structures);
        }

        static Tile changed(SurfaceTile tile) {
            return changed(tile, java.util.List.of());
        }

        static Tile changed(SurfaceTile tile, java.util.List<StructureHit> structures) {
            if (!tile.hasSurfaceFacts())
                throw new IllegalArgumentException("Changed tile has no surface facts");
            return new Tile(
                    tile.key().dimension(), tile.key().x(), tile.key().z(),
                    true, true, false,
                    tile.stateIds(), tile.biomeIds(),
                    tile.surfaceHeights(), tile.reliefHeights(), tile.waterDepths(),
                    structures);
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
                            buf.writeVarInt(value.structures.size());
                            for (StructureHit hit : value.structures) {
                                buf.writeUtf(hit.structureId(), 256);
                                buf.writeInt(hit.startChunkX());
                                buf.writeInt(hit.startChunkZ());
                                buf.writeInt(hit.x());
                                buf.writeInt(hit.z());
                            }
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
                            int structureCount = buf.readVarInt();
                            if (structureCount < 0 || structureCount > 256)
                                throw new IllegalArgumentException("Invalid structure count");
                            java.util.List<StructureHit> structures = new java.util.ArrayList<>(structureCount);
                            for (int i = 0; i < structureCount; i++) {
                                structures.add(new StructureHit(
                                        buf.readUtf(256), buf.readInt(), buf.readInt(),
                                        buf.readInt(), buf.readInt()));
                            }
                            if (!present || !changed) {
                                return Tile.status(
                                        dimension, x, z, present, changed, retry, structures);
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
                                    states, biomes, surface, relief, water, structures);
                        });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void pushChanged(ServerPlayer player, SurfaceTile tile) {
        PacketDistributor.sendToPlayer(player, Tile.changed(tile));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("7");
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

                    var surfaceFuture = SurfaceService.request(
                            player.serverLevel(), request.chunkX(), request.chunkZ());
                    var structureFuture = StructureDiscovery.request(
                            player.serverLevel(), request.chunkX(), request.chunkZ());
                    surfaceFuture.thenCombine(
                                    structureFuture,
                                    (surface, structures) -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                                            surface, structures))
                            .whenComplete((combined, error) -> player.server.execute(() -> {
                                var surface = combined == null
                                        ? java.util.Optional.<SurfaceTile>empty()
                                        : combined.getKey();
                                var structures = combined == null
                                        ? java.util.List.<StructureHit>of()
                                        : combined.getValue();
                                if (!player.isAlive() || player.serverLevel() == null) return;
                                if (!player.serverLevel().dimension().location().toString()
                                        .equals(request.dimension())) return;
                                if (error != null) {
                                    PacketDistributor.sendToPlayer(player,
                                            Tile.status(
                                                    request.dimension(), request.chunkX(),
                                                    request.chunkZ(),
                                                    request.hasKnownFingerprint(),
                                                    false, true, java.util.List.of()));
                                    return;
                                }
                                if (surface == null || surface.isEmpty()) {
                                    PacketDistributor.sendToPlayer(player,
                                            Tile.status(
                                                    request.dimension(), request.chunkX(),
                                                    request.chunkZ(),
                                                    false, false, false, structures));
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
                                                        true, false, false, structures)
                                                : Tile.changed(tile, structures));
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
