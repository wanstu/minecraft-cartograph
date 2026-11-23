package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

public final class MapProtocol {
    static final String MOD_ID = "cartograph_v3";

    private MapProtocol() {}

    public record StructureHit(
            String structureId, int startChunkX, int startChunkZ, int x, int z) {
        public StructureHit {
            if (structureId == null || structureId.isBlank() || structureId.length() > 256)
                throw new IllegalArgumentException("Invalid structure id");
        }
    }

    public record MapViewState(
            boolean open,
            boolean viewing,
            int liveScanIntervalTicks,
            long backgroundCheckpointMillis)
            implements CustomPacketPayload {
        public MapViewState {
            liveScanIntervalTicks = Math.max(5, Math.min(100, liveScanIntervalTicks));
            backgroundCheckpointMillis = Math.max(0L, backgroundCheckpointMillis);
        }
        public static final Type<MapViewState> TYPE = new Type<>(
                MapGameAccess.id(MOD_ID, "map_view_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapViewState> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeBoolean(value.open());
                            buf.writeBoolean(value.viewing());
                            buf.writeVarInt(value.liveScanIntervalTicks());
                            buf.writeLong(value.backgroundCheckpointMillis());
                        },
                        buf -> new MapViewState(
                                buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestWorldInfo() implements CustomPacketPayload {
        public static final Type<RequestWorldInfo> TYPE = new Type<>(
                MapGameAccess.id(MOD_ID, "request_world_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestWorldInfo> CODEC =
                StreamCodec.of((buf, value) -> {}, buf -> new RequestWorldInfo());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record WorldInfo(String worldId, String dimension) implements CustomPacketPayload {
        public static final Type<WorldInfo> TYPE = new Type<>(
                MapGameAccess.id(MOD_ID, "world_info"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldInfo> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.worldId(), 36);
                            buf.writeUtf(value.dimension(), 128);
                        },
                        buf -> new WorldInfo(buf.readUtf(36), buf.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BackfillCheckpoint(String dimension, long checkpointMillis)
            implements CustomPacketPayload {
        public BackfillCheckpoint {
            if (dimension == null || dimension.isBlank() || dimension.length() > 128)
                throw new IllegalArgumentException("Invalid dimension");
            checkpointMillis = Math.max(0L, checkpointMillis);
        }
        public static final Type<BackfillCheckpoint> TYPE = new Type<>(
                MapGameAccess.id(MOD_ID, "backfill_checkpoint"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BackfillCheckpoint> CODEC =
                StreamCodec.of(
                        (buf, value) -> {
                            buf.writeUtf(value.dimension(), 128);
                            buf.writeLong(value.checkpointMillis());
                        },
                        buf -> new BackfillCheckpoint(buf.readUtf(128), buf.readLong()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestTile(String dimension, int chunkX, int chunkZ,
                              boolean hasKnownFingerprint, long knownFingerprint)
            implements CustomPacketPayload {
        public static final Type<RequestTile> TYPE = new Type<>(
                MapGameAccess.id(MOD_ID, "request_tile"));
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
                MapGameAccess.id(MOD_ID, "teleport"));
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

        boolean matchesFacts(SurfaceTile tile) {
            return tile.matchesFacts(stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths);
        }

        SurfaceTile toSurfaceTile(ChunkKey key) {
            return SurfaceTile.fromFacts(key, stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths);
        }

        @Override public int[] stateIds() { return stateIds.clone(); }
        @Override public int[] biomeIds() { return biomeIds.clone(); }
        @Override public short[] surfaceHeights() { return surfaceHeights.clone(); }
        @Override public short[] reliefHeights() { return reliefHeights.clone(); }
        @Override public byte[] waterDepths() { return waterDepths.clone(); }

        public static final Type<Tile> TYPE = new Type<>(
                MapGameAccess.id(MOD_ID, "tile"));

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

    private static int findSafeTeleportY(
            net.minecraft.server.level.ServerLevel level, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int worldSurface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int start = Math.min(MapGameAccess.maxYExclusive(level) - 2, Math.max(surface, worldSurface) + 4);
        int min = MapGameAccess.minY(level) + 1;

        BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();

        for (int y = start; y >= min; y--) {
            support.set(x, y - 1, z);
            feet.set(x, y, z);
            head.set(x, y + 1, z);

            var supportState = level.getBlockState(support);
            var feetState = level.getBlockState(feet);
            var headState = level.getBlockState(head);

            boolean solidSupport = supportState.isFaceSturdy(level, support, Direction.UP);
            boolean clearFeet = feetState.getCollisionShape(level, feet).isEmpty()
                    && feetState.getFluidState().isEmpty();
            boolean clearHead = headState.getCollisionShape(level, head).isEmpty()
                    && headState.getFluidState().isEmpty();

            if (solidSupport && clearFeet && clearHead) return y;
        }

        // Last-resort fallback: first air above the motion-blocking surface.
        return Math.max(min, Math.min(MapGameAccess.maxYExclusive(level) - 2, surface));
    }

    static void pushChanged(ServerPlayer player, SurfaceTile tile) {
        MapTransport.sendToPlayer(player, Tile.changed(tile));
    }

    static void handleViewState(ServerPlayer player, MapViewState request) {
        if (request.open()) {
            TileRequestScheduler.open(player, request.backgroundCheckpointMillis());
            TileRequestScheduler.setViewing(player, request.viewing());
            if (request.viewing()) {
                LiveSurfaceUpdates.open(player, request.liveScanIntervalTicks());
            } else {
                LiveSurfaceUpdates.close(player);
            }
        } else {
            LiveSurfaceUpdates.close(player);
            TileRequestScheduler.close(player);
        }
    }

    static void replyWorldInfo(ServerPlayer player) {
        MapTransport.sendToPlayer(player, new WorldInfo(
                WorldIdentity.get(MapGameAccess.server(player)).toString(),
                MapGameAccess.dimension(MapGameAccess.level(player))));
    }

    static void handleTeleport(ServerPlayer player, TeleportRequest request) {
        var level = MapGameAccess.level(player);
        if (!MapGameAccess.dimension(level).equals(request.dimension())) return;
        if (!MapGameAccess.canTeleport(player)) return;

        int x = request.blockX();
        int z = request.blockZ();

        // Force the target chunk to exist before consulting heightmaps.
        level.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        int y = findSafeTeleportY(level, x, z);

        MapGameAccess.teleport(player, level, x + 0.5, y, z + 0.5);
    }
}
