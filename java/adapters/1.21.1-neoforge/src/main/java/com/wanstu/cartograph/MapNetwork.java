package com.wanstu.cartograph;

import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.MapSheetCoverage;
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

    /** Client requests ONLY a possessed page; server chooses original samples
     * and pays color in its authoritative ledger. No client supplied pixels. */
    public record RequestColorPage(String atlasId, int pageX, int pageZ)
            implements CustomPacketPayload {
        public static final Type<RequestColorPage> TYPE = new Type<>(id("request_color_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestColorPage> CODEC = StreamCodec.of(
                (b, v) -> { b.writeUtf(v.atlasId(), 64); b.writeInt(v.pageX()); b.writeInt(v.pageZ()); },
                b -> new RequestColorPage(b.readUtf(64), b.readInt(), b.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One page's authorized newly colored 16x16 chunks, without any geometry.
     * pixels contains bitCount(mask)*256 raw material palette IDs, ordered by
     * ascending chunk index. Mask contains ONLY ink-authorized cells. Empty
     * deltas are valid terminal acknowledgements to release bounded queues. */
    public record ColorDelta(String atlasId, String dimension, int pageX, int pageZ,
                             long chunkMask, byte[] pixels, int pigmentUnits, int totalColored)
            implements CustomPacketPayload {
        public ColorDelta {
            if (pixels.length != Long.bitCount(chunkMask) * 256
                    || pixels.length > Page.PIXELS || pigmentUnits < 0 || totalColored < 0)
                throw new IllegalArgumentException("Invalid authorized color delta");
        }
        public static final Type<ColorDelta> TYPE = new Type<>(id("color_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ColorDelta> CODEC = StreamCodec.of(
                (b, v) -> {
                    b.writeUtf(v.atlasId(), 64); b.writeUtf(v.dimension(), 128);
                    b.writeInt(v.pageX()); b.writeInt(v.pageZ()); b.writeLong(v.chunkMask());
                    b.writeByteArray(v.pixels()); b.writeVarInt(v.pigmentUnits());
                    b.writeVarInt(v.totalColored());
                },
                b -> new ColorDelta(b.readUtf(64), b.readUtf(128),
                        b.readInt(), b.readInt(), b.readLong(),
                        b.readByteArray(Page.PIXELS), b.readVarInt(), b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Notification of a world edit; client keeps old pixels until refreshed. */
    /** Admin-adjustable I/O workers and bounded main-thread scan budget. */
    public record PerformanceSettings(int workers, int budgetMs, boolean editable)
            implements CustomPacketPayload {
        public static final Type<PerformanceSettings> TYPE = new Type<>(id("performance_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PerformanceSettings> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.workers); buf.writeVarInt(v.budgetMs); buf.writeBoolean(v.editable);
                }, buf -> new PerformanceSettings(buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record SetPerformance(int workers, int budgetMs) implements CustomPacketPayload {
        public static final Type<SetPerformance> TYPE = new Type<>(id("set_performance"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetPerformance> CODEC = StreamCodec.of(
                (buf, v) -> { buf.writeVarInt(v.workers); buf.writeVarInt(v.budgetMs); },
                buf -> new SetPerformance(buf.readVarInt(), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** op 0=start, 1=pause, 2=resume, 3=cancel, 4=query current status. */
    public record RefreshControl(String atlasId, int op) implements CustomPacketPayload {
        public static final Type<RefreshControl> TYPE = new Type<>(id("refresh_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RefreshControl> CODEC = StreamCodec.of(
                (buf, v) -> { buf.writeUtf(v.atlasId, 64); buf.writeVarInt(v.op); },
                buf -> new RefreshControl(buf.readUtf(64), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    /** Stable job progress: total is frozen at start, never the dynamic tile queue. */
    public record RefreshProgress(int state, int done, int total, int failed) implements CustomPacketPayload {
        public static final Type<RefreshProgress> TYPE = new Type<>(id("refresh_progress"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RefreshProgress> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.state); buf.writeVarInt(v.done);
                    buf.writeVarInt(v.total); buf.writeVarInt(v.failed);
                }, buf -> new RefreshProgress(buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record InvalidatePage(String atlasId, String dimension, int pageX, int pageZ)
            implements CustomPacketPayload {
        public static final Type<InvalidatePage> TYPE = new Type<>(id("invalidate_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, InvalidatePage> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUtf(v.atlasId, 64); buf.writeUtf(v.dimension, 128);
                    buf.writeInt(v.pageX); buf.writeInt(v.pageZ);
                }, buf -> new InvalidatePage(buf.readUtf(64), buf.readUtf(128),
                        buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server-only, cost-free edit of exactly ONE previously surveyed 16x16
     * world chunk. Never authorizes an unknown square, never changes resource
     * balances, survey coverage, or color entitlement.
     */
    public record TerrainPatch(String atlasId, String dimension, int chunkX, int chunkZ,
                               byte[] pixels, byte[] features, short[] heights, byte[] waterDepths,
                               List<SurfaceStructure> structures)
            implements CustomPacketPayload {
        public static final int PIXELS = 16 * 16;
        public TerrainPatch {
            if (pixels.length != PIXELS || features.length != PIXELS
                    || heights.length != PIXELS || waterDepths.length != PIXELS
                    || structures.size() > 16)
                throw new IllegalArgumentException("Invalid 16x16 terrain patch");
        }
        public static final Type<TerrainPatch> TYPE = new Type<>(id("terrain_patch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerrainPatch> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUtf(v.atlasId, 64); buf.writeUtf(v.dimension, 128);
                    buf.writeInt(v.chunkX); buf.writeInt(v.chunkZ);
                    buf.writeByteArray(v.pixels); buf.writeByteArray(v.features);
                    for (short height : v.heights) buf.writeShort(height);
                    buf.writeByteArray(v.waterDepths);
                    buf.writeVarInt(v.structures.size());
                    for (SurfaceStructure f : v.structures) {
                        buf.writeUtf(f.type(), 64);
                        buf.writeInt(f.x()); buf.writeInt(f.z());
                    }
                }, buf -> {
                    String atlas = buf.readUtf(64), dim = buf.readUtf(128);
                    int cx = buf.readInt(), cz = buf.readInt();
                    byte[] pixels = buf.readByteArray(PIXELS), features = buf.readByteArray(PIXELS);
                    short[] heights = new short[PIXELS];
                    for (int i = 0; i < PIXELS; i++) heights[i] = buf.readShort();
                    byte[] depths = buf.readByteArray(PIXELS);
                    int count = buf.readVarInt();
                    if (count < 0 || count > 16) throw new IllegalArgumentException("Too many patch structures");
                    List<SurfaceStructure> structures = new ArrayList<>(count);
                    for (int i = 0; i < count; i++)
                        structures.add(new SurfaceStructure(buf.readUtf(64), buf.readInt(), buf.readInt()));
                    return new TerrainPatch(atlas, dim, cx, cz, pixels, features, heights, depths,
                            List.copyOf(structures));
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Request(String atlasId, int pageX, int pageZ, boolean cachedOnly) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "request_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.atlasId, 64);
                    buf.writeInt(value.pageX);
                    buf.writeInt(value.pageZ);
                    buf.writeBoolean(value.cachedOnly);
                },
                buf -> new Request(buf.readUtf(64), buf.readInt(), buf.readInt(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Page(String atlasId, String dimension, int pageX, int pageZ,
                       byte[] pixels, byte[] features, short[] heights, byte[] waterDepths,
                       long surveyedChunks, long coloredChunks, int inkUnits, int pigmentUnits,
                       int glowUnits, boolean compass, boolean inRange,
                       boolean complete, boolean cachedOnly, int scanned, int generated, int painted,
                       String anchorDimension, int anchorX, int anchorZ, int totalPages,
                       int sheetSideBlocks, int totalSurveyed, int totalColored,
                       List<AtlasMarker> markers, List<SurfaceStructure> structures) implements CustomPacketPayload {
        public static final int PIXELS = AtlasPage.SIDE_BLOCKS * AtlasPage.SIDE_BLOCKS;

        public Page {
            if (pixels.length != PIXELS || features.length != PIXELS
                    || (heights.length != 0 && heights.length != PIXELS)
                    || waterDepths.length != heights.length)
                throw new IllegalArgumentException("Invalid Cartograph surface arrays");
        }

        public static final Type<Page> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.atlasId, 64);
                    buf.writeUtf(value.dimension, 128);
                    buf.writeInt(value.pageX); buf.writeInt(value.pageZ);
                    buf.writeByteArray(value.pixels);
                    buf.writeByteArray(value.features);
                    buf.writeVarInt(value.heights.length);
                    for (short height : value.heights) buf.writeShort(height);
                    buf.writeByteArray(value.waterDepths);
                    buf.writeLong(value.surveyedChunks);
                    buf.writeLong(value.coloredChunks);
                    buf.writeInt(value.inkUnits); buf.writeInt(value.pigmentUnits);
                    buf.writeInt(value.glowUnits);
                    buf.writeBoolean(value.compass); buf.writeBoolean(value.inRange);
                    buf.writeBoolean(value.complete);
                    buf.writeBoolean(value.cachedOnly);
                    buf.writeVarInt(value.scanned); buf.writeVarInt(value.generated);
                    buf.writeVarInt(value.painted);
                    buf.writeUtf(value.anchorDimension, 128);
                    buf.writeInt(value.anchorX); buf.writeInt(value.anchorZ);
                    buf.writeVarInt(value.totalPages);
                    buf.writeVarInt(value.sheetSideBlocks);
                    buf.writeVarInt(value.totalSurveyed);
                    buf.writeVarInt(value.totalColored);
                    if (value.markers.size() > 128) throw new IllegalArgumentException("Too many markers");
                    buf.writeVarInt(value.markers.size());
                    for (AtlasMarker m : value.markers) writeMarker(buf, m);
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
                    byte[] pixels = buf.readByteArray(PIXELS);
                    byte[] features = buf.readByteArray(PIXELS);
                    int heightCount = buf.readVarInt();
                    if (heightCount != 0 && heightCount != PIXELS)
                        throw new IllegalArgumentException("Invalid Cartograph height count");
                    short[] heights = new short[heightCount];
                    for (int i = 0; i < heightCount; i++) heights[i] = buf.readShort();
                    byte[] waterDepths = buf.readByteArray(heightCount);
                    long surveyed = buf.readLong(), colored = buf.readLong();
                    int ink = buf.readInt(), pigment = buf.readInt(), glow = buf.readInt();
                    boolean compass = buf.readBoolean(), inRange = buf.readBoolean();
                    boolean complete = buf.readBoolean(), cachedOnly = buf.readBoolean();
                    int scanned = buf.readVarInt(), generated = buf.readVarInt();
                    int painted = buf.readVarInt();
                    String anchorDimension = buf.readUtf(128);
                    int anchorX = buf.readInt(), anchorZ = buf.readInt();
                    int totalPages = buf.readVarInt(), sheetSideBlocks = buf.readVarInt();
                    int totalSurveyed = buf.readVarInt(), totalColored = buf.readVarInt();
                    if (scanned < 0 || scanned > 64 || generated < 0 || generated > 64
                            || painted < 0 || painted > 64 || totalPages < 1 || totalPages > AtlasState.MAX_PAGES
                            || !MapSheetCoverage.valid(sheetSideBlocks)
                            || totalSurveyed < 0
                            || totalSurveyed > (long) totalPages * MapSheetCoverage.areaChunksPerSheet(sheetSideBlocks)
                            || totalColored < 0
                            || totalColored > (long) totalPages * MapSheetCoverage.areaChunksPerSheet(sheetSideBlocks))
                        throw new IllegalArgumentException("Invalid survey progress");
                    int count = buf.readVarInt();
                    if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid waypoint count");
                    List<AtlasMarker> markers = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) markers.add(readMarker(buf));
                    int countStructures = buf.readVarInt();
                    if (countStructures < 0 || countStructures > 64)
                        throw new IllegalArgumentException("Too many automatic structures");
                    List<SurfaceStructure> structures = new ArrayList<>(countStructures);
                    for (int i = 0; i < countStructures; i++) {
                        structures.add(new SurfaceStructure(buf.readUtf(64), buf.readInt(), buf.readInt()));
                    }
                    return new Page(atlasId, dim, pageX, pageZ, pixels, features, heights, waterDepths, surveyed, colored,
                            ink, pigment, glow, compass, inRange, complete, cachedOnly, scanned, generated,
                            painted, anchorDimension, anchorX, anchorZ, totalPages, sheetSideBlocks, totalSurveyed, totalColored,
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
                    buf.writeUtf(marker.name, AtlasMarker.MAX_NAME);
                    buf.writeByte(marker.icon); buf.writeInt(marker.color);
                    buf.writeBoolean(marker.glow); buf.writeBoolean(marker.delete);
                },
                buf -> new EditMarker(buf.readUtf(64), buf.readUtf(48),
                        buf.readInt(), buf.readInt(), buf.readUtf(AtlasMarker.MAX_NAME),
                        buf.readUnsignedByte(), buf.readInt(),
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

    /** Place a new paper sheet at this specific map tile, including distant
     * already-generated parts of a world. No extra paper spent for the same
     * tile beyond the normal AddPage cost. Server verifies location/ownership. */
    public record AddPage(String atlasId, int targetPageX, int targetPageZ)
            implements CustomPacketPayload {
        public static final Type<AddPage> TYPE = new Type<>(id("add_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AddPage> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUtf(v.atlasId, 64);
                    buf.writeInt(v.targetPageX()); buf.writeInt(v.targetPageZ());
                },
                buf -> new AddPage(buf.readUtf(64), buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server-owned paper mask. All native world facts still exist regardless
     * of these sheet positions or whether the player carries an atlas. */
    public record PaperSite(int pageX, int pageZ) {}
    public record PaperCoverage(String atlasId, String dimension, int anchorX, int anchorZ,
                                int sheetSideBlocks, int legacySheets, List<PaperSite> remoteSheets)
            implements CustomPacketPayload {
        public PaperCoverage {
            if (remoteSheets.size() > 4096 || legacySheets < 1
                    || !MapSheetCoverage.valid(sheetSideBlocks))
                throw new IllegalArgumentException("Invalid paper coverage");
            remoteSheets = List.copyOf(remoteSheets);
        }
        public static final Type<PaperCoverage> TYPE = new Type<>(id("paper_coverage"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PaperCoverage> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUtf(v.atlasId(), 64); buf.writeUtf(v.dimension(), 128);
                    buf.writeInt(v.anchorX()); buf.writeInt(v.anchorZ());
                    buf.writeVarInt(v.sheetSideBlocks()); buf.writeVarInt(v.legacySheets());
                    buf.writeVarInt(v.remoteSheets().size());
                    for (PaperSite sheet : v.remoteSheets()) {
                        buf.writeInt(sheet.pageX()); buf.writeInt(sheet.pageZ());
                    }
                },
                buf -> {
                    String id = buf.readUtf(64), dimension = buf.readUtf(128);
                    int ax = buf.readInt(), az = buf.readInt();
                    int side = buf.readVarInt(), legacy = buf.readVarInt();
                    int count = buf.readVarInt();
                    if (count < 0 || count > 4096)
                        throw new IllegalArgumentException("Too many remote paper sheets");
                    List<PaperSite> sites = new ArrayList<>(count);
                    for (int i = 0; i < count; i++)
                        sites.add(new PaperSite(buf.readInt(), buf.readInt()));
                    return new PaperCoverage(id, dimension, ax, az, side, legacy, sites);
                });
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

    public record MaterialRates(int inkCost, int pigmentCost, int glowCost, int paperCost,
                                int inkInput, int pigmentInput, int glowInput, int paperInput,
                                int permanentFlags, int pageSideBlocks) {
        public static final MaterialRates DEFAULT = new MaterialRates(1, 1, 1, 10, 40, 40, 40, 10,
                0, MapSheetCoverage.DEFAULT_BLOCKS);
        public int cost(int slot) {
            return switch (slot) { case 0 -> inkCost; case 1 -> pigmentCost; case 2 -> glowCost; case 3 -> paperCost; default -> throw new IllegalArgumentException("slot"); };
        }
        public int input(int slot) {
            return switch (slot) { case 0 -> inkInput; case 1 -> pigmentInput; case 2 -> glowInput; case 3 -> paperInput; default -> throw new IllegalArgumentException("slot"); };
        }
        public boolean permanent(int slot) { return (permanentFlags & (1 << slot)) != 0; }
        public boolean valid() {
            for (int i = 0; i < 4; i++)
                if (cost(i) < 1 || cost(i) > 100 || input(i) < 1 || input(i) > 100) return false;
            return (permanentFlags & ~15) == 0 && MapSheetCoverage.valid(pageSideBlocks);
        }
        void encode(RegistryFriendlyByteBuf b) {
            b.writeByte(inkCost); b.writeByte(pigmentCost); b.writeByte(glowCost); b.writeByte(paperCost);
            b.writeByte(inkInput); b.writeByte(pigmentInput); b.writeByte(glowInput); b.writeByte(paperInput);
            b.writeByte(permanentFlags);
            b.writeVarInt(pageSideBlocks);
        }
        static MaterialRates decode(RegistryFriendlyByteBuf b) {
            return new MaterialRates(b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(), b.readUnsignedByte(),
                    b.readUnsignedByte(), b.readVarInt());
        }
    }

    public record CostSettings(MaterialRates rates, boolean editable) implements CustomPacketPayload {
        public static final Type<CostSettings> TYPE = new Type<>(id("cost_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CostSettings> CODEC = StreamCodec.of(
                (b, v) -> { v.rates.encode(b); b.writeBoolean(v.editable); },
                b -> new CostSettings(MaterialRates.decode(b), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SetCostSettings(MaterialRates rates) implements CustomPacketPayload {
        public static final Type<SetCostSettings> TYPE = new Type<>(id("set_cost_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetCostSettings> CODEC = StreamCodec.of(
                (b, v) -> v.rates.encode(b), b -> new SetCostSettings(MaterialRates.decode(b)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, path);
    }

    private static void writeMarker(RegistryFriendlyByteBuf buf, AtlasMarker marker) {
        buf.writeUtf(marker.id(), 48); buf.writeUtf(marker.dimension(), 128);
        buf.writeInt(marker.x()); buf.writeInt(marker.z());
        buf.writeUtf(marker.name(), AtlasMarker.MAX_NAME);
        buf.writeByte(marker.icon()); buf.writeInt(marker.color());
        buf.writeBoolean(marker.glowing()); buf.writeBoolean(marker.glowEverPaid());
    }

    private static AtlasMarker readMarker(RegistryFriendlyByteBuf buf) {
        return new AtlasMarker(buf.readUtf(48), buf.readUtf(128),
                buf.readInt(), buf.readInt(), buf.readUtf(AtlasMarker.MAX_NAME),
                buf.readUnsignedByte(), buf.readInt(), buf.readBoolean(), buf.readBoolean());
    }

    private static void writePoints(RegistryFriendlyByteBuf b, List<NamedRegion.Point> points) {
        if (points.size() > NamedRegion.MAX_VERTICES) throw new IllegalArgumentException("Too many vertices");
        b.writeVarInt(points.size());
        for (NamedRegion.Point p : points) { b.writeInt(p.x()); b.writeInt(p.z()); }
    }

    private static List<NamedRegion.Point> readPoints(RegistryFriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > NamedRegion.MAX_VERTICES) throw new IllegalArgumentException("Too many vertices");
        List<NamedRegion.Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) points.add(new NamedRegion.Point(b.readInt(), b.readInt()));
        return List.copyOf(points);
    }

    public record RequestAnnotations(String atlasId) implements CustomPacketPayload {
        public static final Type<RequestAnnotations> TYPE = new Type<>(id("request_annotations"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestAnnotations> CODEC = StreamCodec.of(
                (b, v) -> b.writeUtf(v.atlasId, 64), b -> new RequestAnnotations(b.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Annotations(String atlasId, String dimension, List<NamedRegion> entries,
                              List<AtlasMarker> markers) implements CustomPacketPayload {
        public static final Type<Annotations> TYPE = new Type<>(id("annotations"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Annotations> CODEC = StreamCodec.of(
                (b, v) -> {
                    b.writeUtf(v.atlasId, 64); b.writeUtf(v.dimension, 128);
                    b.writeVarInt(v.entries.size());
                    for (NamedRegion region : v.entries) {
                        b.writeUtf(region.id(), 48); b.writeUtf(region.name(), NamedRegion.MAX_NAME);
                        b.writeByte(region.type()); b.writeInt(region.color());
                        b.writeUtf(region.note(), NamedRegion.MAX_NOTE); writePoints(b, region.boundary());
                    }
                    if (v.markers.size() > AtlasMarker.MAX_MARKERS) throw new IllegalArgumentException("Too many markers");
                    b.writeVarInt(v.markers.size());
                    for (AtlasMarker marker : v.markers) writeMarker(b, marker);
                }, b -> {
                    String atlas = b.readUtf(64), dimension = b.readUtf(128);
                    int n = b.readVarInt();
                    if (n < 0 || n > NamedRegion.MAX_REGIONS) throw new IllegalArgumentException("Too many regions");
                    List<NamedRegion> entries = new ArrayList<>(n);
                    for (int i = 0; i < n; i++)
                        entries.add(new NamedRegion(b.readUtf(48), dimension, b.readUtf(NamedRegion.MAX_NAME),
                                b.readUnsignedByte(), b.readInt(), b.readUtf(NamedRegion.MAX_NOTE), readPoints(b)));
                    int count = b.readVarInt();
                    if (count < 0 || count > AtlasMarker.MAX_MARKERS) throw new IllegalArgumentException("Too many markers");
                    List<AtlasMarker> markers = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) markers.add(readMarker(b));
                    return new Annotations(atlas, dimension, List.copyOf(entries), List.copyOf(markers));
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record EditRegion(String atlasId, String regionId, String name, int regionType, int color, String note,
                             List<NamedRegion.Point> boundary, boolean delete) implements CustomPacketPayload {
        public static final Type<EditRegion> TYPE = new Type<>(id("edit_region"));
        public static final StreamCodec<RegistryFriendlyByteBuf, EditRegion> CODEC = StreamCodec.of(
                (b, v) -> {
                    b.writeUtf(v.atlasId, 64); b.writeUtf(v.regionId, 48); b.writeUtf(v.name, NamedRegion.MAX_NAME);
                    b.writeByte(v.regionType); b.writeInt(v.color); b.writeUtf(v.note, NamedRegion.MAX_NOTE);
                    writePoints(b, v.boundary); b.writeBoolean(v.delete);
                },
                b -> new EditRegion(b.readUtf(64), b.readUtf(48), b.readUtf(NamedRegion.MAX_NAME),
                        b.readUnsignedByte(), b.readInt(), b.readUtf(NamedRegion.MAX_NOTE), readPoints(b), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** A server-confirmed operation result, never a speculative client toast. */
    /** Survey changes need cached tile authorization to refresh; paper/compass do not. */
    public record Feedback(boolean ok, String message, boolean surveyChanged,
                           boolean colorChanged) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "feedback"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Feedback> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBoolean(value.ok);
                    buf.writeUtf(value.message, 160);
                    buf.writeBoolean(value.surveyChanged);
                    buf.writeBoolean(value.colorChanged);
                },
                buf -> new Feedback(buf.readBoolean(), buf.readUtf(160),
                        buf.readBoolean(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("17");
        registrar.playToServer(RequestAnnotations.TYPE, RequestAnnotations.CODEC, (req, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.listAnnotations(player, req.atlasId());
                }));
        registrar.playToServer(EditRegion.TYPE, EditRegion.CODEC, (req, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.editRegion(player, req);
                }));
        registrar.playToClient(Annotations.TYPE, Annotations.CODEC, (annotations, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptAnnotations(annotations);
                }));
        registrar.playToClient(InvalidatePage.TYPE, InvalidatePage.CODEC, (item, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.invalidatePage(item);
                }));
        registrar.playToServer(RequestColorPage.TYPE, RequestColorPage.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player)
                        AtlasMapServer.enqueueColor(player, request);
                }));
        registrar.playToClient(ColorDelta.TYPE, ColorDelta.CODEC, (delta, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT)
                        CartographClient.acceptColorDelta(delta);
                }));
        registrar.playToClient(TerrainPatch.TYPE, TerrainPatch.CODEC, (patch, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptTerrainPatch(patch);
                }));
        registrar.playToServer(SetPerformance.TYPE, SetPerformance.CODEC, (req, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player)
                        AtlasActions.updatePerformance(player, req);
                }));
        registrar.playToClient(PerformanceSettings.TYPE, PerformanceSettings.CODEC, (v, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptPerformance(v);
                }));
        registrar.playToServer(RefreshControl.TYPE, RefreshControl.CODEC, (req, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player)
                        MapRefreshJobs.control(player, req);
                }));
        registrar.playToClient(RefreshProgress.TYPE, RefreshProgress.CODEC, (v, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptRefreshProgress(v);
                }));
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
        registrar.playToClient(PaperCoverage.TYPE, PaperCoverage.CODEC, (mask, ctx) ->
                ctx.enqueueWork(() -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) CartographClient.acceptPaperCoverage(mask);
                }));
        registrar.playToServer(AddPage.TYPE, AddPage.CODEC, (request, ctx) ->
                ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer player) AtlasActions.addPage(player, request);
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
