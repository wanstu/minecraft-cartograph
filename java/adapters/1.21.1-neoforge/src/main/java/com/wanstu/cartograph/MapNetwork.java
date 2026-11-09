package com.wanstu.cartograph;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * All map and inventory mutations run on the logical server. Clients never
 * provide terrain pixels or world identifiers. Page payload is bounded.
 */
public final class MapNetwork {
    private MapNetwork() {}

    public record Request(String atlasId, int pageX, int pageZ) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "request_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.atlasId, 64);
                    buf.writeInt(value.pageX);
                    buf.writeInt(value.pageZ);
                },
                buf -> new Request(buf.readUtf(64), buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Page(String atlasId, String dimension, int pageX, int pageZ,
                       byte[] pixels, byte[] features, long coloredChunks, int inkUnits, int pigmentUnits,
                       int glowUnits, boolean compass, boolean inRange,
                       boolean complete, int scanned, int generated, int painted,
                       int anchorX, int anchorZ, int totalPages,
                       List<AtlasMarker> markers, List<SurfaceStructure> structures) implements CustomPacketPayload {
        public static final Type<Page> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.atlasId, 64);
                    buf.writeUtf(value.dimension, 128);
                    buf.writeInt(value.pageX); buf.writeInt(value.pageZ);
                    buf.writeByteArray(value.pixels);
                    buf.writeByteArray(value.features);
                    buf.writeLong(value.coloredChunks);
                    buf.writeInt(value.inkUnits); buf.writeInt(value.pigmentUnits);
                    buf.writeInt(value.glowUnits);
                    buf.writeBoolean(value.compass); buf.writeBoolean(value.inRange);
                    buf.writeBoolean(value.complete);
                    buf.writeVarInt(value.scanned); buf.writeVarInt(value.generated);
                    buf.writeVarInt(value.painted);
                    buf.writeInt(value.anchorX); buf.writeInt(value.anchorZ);
                    buf.writeVarInt(value.totalPages);
                    if (value.markers.size() > 128) throw new IllegalArgumentException("Too many markers");
                    buf.writeVarInt(value.markers.size());
                    for (AtlasMarker m : value.markers) {
                        buf.writeUtf(m.id(), 48);
                        buf.writeUtf(m.dimension(), 128);
                        buf.writeInt(m.x()); buf.writeInt(m.z());
                        buf.writeUtf(m.name(), 24);
                        buf.writeByte(m.icon()); buf.writeByte(m.color());
                        buf.writeBoolean(m.glowing());
                        buf.writeBoolean(m.glowEverPaid());
                    }
                    if (value.structures.size() > 64) throw new IllegalArgumentException("Too many structures");
                    buf.writeVarInt(value.structures.size());
                    for (SurfaceStructure marker : value.structures) {
                        buf.writeUtf(marker.type(), 64);
                        buf.writeInt(marker.x());
                        buf.writeInt(marker.z());
                    }
                },
                buf -> {
                    String atlasId = buf.readUtf(64), dim = buf.readUtf(128);
                    int pageX = buf.readInt(), pageZ = buf.readInt();
                    byte[] pixels = buf.readByteArray(128 * 128);
                    byte[] features = buf.readByteArray(128 * 128);
                    long colored = buf.readLong();
                    int ink = buf.readInt(), pigment = buf.readInt(), glow = buf.readInt();
                    boolean compass = buf.readBoolean(), inRange = buf.readBoolean();
                    boolean complete = buf.readBoolean();
                    int scanned = buf.readVarInt(), generated = buf.readVarInt();
                    int painted = buf.readVarInt();
                    int anchorX = buf.readInt(), anchorZ = buf.readInt();
                    int totalPages = buf.readVarInt();
                    if (scanned < 0 || scanned > 64 || generated < 0 || generated > 64
                            || painted < 0 || painted > 64 || totalPages < 1 || totalPages > 65536)
                        throw new IllegalArgumentException("Invalid survey progress");
                    int count = buf.readVarInt();
                    if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid waypoint count");
                    List<AtlasMarker> markers = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        markers.add(new AtlasMarker(buf.readUtf(48), buf.readUtf(128),
                                buf.readInt(), buf.readInt(), buf.readUtf(24),
                                buf.readUnsignedByte(), buf.readUnsignedByte(),
                                buf.readBoolean(), buf.readBoolean()));
                    }
                    int countStructures = buf.readVarInt();
                    if (countStructures < 0 || countStructures > 64)
                        throw new IllegalArgumentException("Too many automatic structures");
                    List<SurfaceStructure> structures = new ArrayList<>(countStructures);
                    for (int i = 0; i < countStructures; i++) {
                        structures.add(new SurfaceStructure(buf.readUtf(64), buf.readInt(), buf.readInt()));
                    }
                    return new Page(atlasId, dim, pageX, pageZ, pixels, features, colored,
                            ink, pigment, glow, compass, inRange, complete, scanned, generated,
                            painted, anchorX, anchorZ, totalPages,
                            List.copyOf(markers), List.copyOf(structures));
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record EditMarker(String atlasId, String id, int x, int z, String name,
                             int icon, int color, boolean glow, boolean delete)
            implements CustomPacketPayload {
        public static final Type<EditMarker> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "edit_marker"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditMarker> CODEC = StreamCodec.of(
                (buf, marker) -> {
                    buf.writeUtf(marker.atlasId, 64);
                    buf.writeUtf(marker.id, 48);
                    buf.writeInt(marker.x); buf.writeInt(marker.z);
                    buf.writeUtf(marker.name, 24);
                    buf.writeByte(marker.icon); buf.writeByte(marker.color);
                    buf.writeBoolean(marker.glow); buf.writeBoolean(marker.delete);
                },
                buf -> new EditMarker(buf.readUtf(64), buf.readUtf(48),
                        buf.readInt(), buf.readInt(), buf.readUtf(24),
                        buf.readUnsignedByte(), buf.readUnsignedByte(),
                        buf.readBoolean(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Refill(String atlasId, int material) implements CustomPacketPayload {
        public static final Type<Refill> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "refill"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Refill> CODEC = StreamCodec.of(
                (buf, v) -> { buf.writeUtf(v.atlasId, 64); buf.writeVarInt(v.material); },
                buf -> new Refill(buf.readUtf(64), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ExportMap(String atlasId, int pageX, int pageZ) implements CustomPacketPayload {
        public static final Type<ExportMap> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "export_map"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ExportMap> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUtf(v.atlasId, 64);
                    buf.writeInt(v.pageX); buf.writeInt(v.pageZ);
                },
                buf -> new ExportMap(buf.readUtf(64), buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CostSettings(int ink, int pigment, int glow,
                               int inkRefill, int pigmentRefill, int glowRefill, boolean editable)
            implements CustomPacketPayload {
        public static final Type<CostSettings> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "cost_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CostSettings> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeByte(v.ink); buf.writeByte(v.pigment); buf.writeByte(v.glow);
                    buf.writeVarInt(v.inkRefill); buf.writeVarInt(v.pigmentRefill);
                    buf.writeVarInt(v.glowRefill);
                    buf.writeBoolean(v.editable);
                },
                buf -> new CostSettings(buf.readUnsignedByte(), buf.readUnsignedByte(),
                        buf.readUnsignedByte(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SetCostSettings(int ink, int pigment, int glow,
                                  int inkRefill, int pigmentRefill, int glowRefill) implements CustomPacketPayload {
        public static final Type<SetCostSettings> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "set_cost_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetCostSettings> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeByte(v.ink); buf.writeByte(v.pigment); buf.writeByte(v.glow);
                    buf.writeVarInt(v.inkRefill); buf.writeVarInt(v.pigmentRefill);
                    buf.writeVarInt(v.glowRefill);
                },
                buf -> new SetCostSettings(buf.readUnsignedByte(), buf.readUnsignedByte(),
                        buf.readUnsignedByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A server-confirmed operation result, never a speculative client toast. */
    public record Feedback(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "feedback"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Feedback> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeBoolean(value.ok); buf.writeUtf(value.message, 160); },
                buf -> new Feedback(buf.readBoolean(), buf.readUtf(160)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("5");
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasMapServer.enqueue(player, request);
                }));
        registrar.playToClient(Feedback.TYPE, Feedback.CODEC, (result, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptFeedback(result);
                }));
        registrar.playToServer(EditMarker.TYPE, EditMarker.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.editMarker(player, request);
                }));
        registrar.playToServer(Refill.TYPE, Refill.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.refill(player, request);
                }));
        registrar.playToServer(SetCostSettings.TYPE, SetCostSettings.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.updateCosts(player, request);
                }));
        registrar.playToClient(CostSettings.TYPE, CostSettings.CODEC, (costs, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptSettings(costs);
                }));
        registrar.playToServer(ExportMap.TYPE, ExportMap.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.exportMap(player, request);
                }));
        registrar.playToClient(Page.TYPE, Page.CODEC, (page, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptPage(page);
                }));
    }
}
