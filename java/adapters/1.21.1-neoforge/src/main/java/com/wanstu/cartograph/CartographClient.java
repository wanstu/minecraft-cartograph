package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import com.wanstu.cartograph.core.AtlasPage;
import com.wanstu.cartograph.core.MapSheetCoverage;
import com.wanstu.cartograph.core.TerrainRelief;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Mod(value = CartographMod.MOD_ID, dist = Dist.CLIENT)
public final class CartographClient {
    public static final KeyMapping OPEN =
            new KeyMapping("key.cartograph.open", GLFW.GLFW_KEY_M, "key.categories.cartograph");
    // Map tiles remain in RAM across opening/closing the atlas. Do not re-scan
    // 64 chunks per tile every 10 seconds just because the GUI is visible.
    // Enough native 128x128 tiles to cover the 1920x1080 map at ordinary
    // zoom without evicting tiles that are still visible on the screen.
    private static final int CACHE_LIMIT = 1400;
    private static final int GPU_TEXTURE_LIMIT = 140;
    private static final int MAX_PARALLEL_PAGES = 4;
    private static final int SURFACE_RECHECK_TICKS = 12000; // 10 min for fully drawn tiles
    private static final LinkedHashMap<CacheKey, Entry> PAGES = new LinkedHashMap<>(32, 0.75f, true);
    private static final LinkedHashMap<CacheKey, Boolean> QUEUED = new LinkedHashMap<>();
    private static final Set<CacheKey> REDRAW = new LinkedHashSet<>();
    private static final LinkedHashMap<CacheKey, Integer> IN_FLIGHT = new LinkedHashMap<>();
    private static final Set<CacheKey> STALE = new LinkedHashSet<>();
    private static ItemStack previousAtlas = ItemStack.EMPTY;
    private static String lastDimension = "", lastAtlasId = "";
    private static MapNetwork.Page coverage;
    private static List<NamedRegion> regions = List.of();
    private static List<AtlasMarker> markers = List.of();
    private static MapNetwork.CostSettings settings = new MapNetwork.CostSettings(MapNetwork.MaterialRates.DEFAULT, false);
    private static MapNetwork.PerformanceSettings performance =
            new MapNetwork.PerformanceSettings(4, 4, false);
    private static MapNetwork.RefreshProgress refresh =
            new MapNetwork.RefreshProgress(0, 0, 0, 0);
    private static int ticks, textureRevision;

    private record CacheKey(String atlasId, String dimension, int px, int pz) {}
    private record Entry(MapNetwork.Page page, DynamicTexture texture, ResourceLocation id, float[] light, int updated) {}

    public CartographClient(IEventBus bus) {
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN));
        NeoForge.EVENT_BUS.addListener(CartographClient::tick);
        NeoForge.EVENT_BUS.addListener(CartographClient::hud);
    }

    public static ItemStack currentAtlas() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        if (player.getMainHandItem().is(CartographMod.ATLAS.get())) return player.getMainHandItem();
        if (player.getOffhandItem().is(CartographMod.ATLAS.get())) return player.getOffhandItem();
        for (ItemStack stack : player.getInventory().items) if (stack.is(CartographMod.ATLAS.get())) return stack;
        return ItemStack.EMPTY;
    }

    static AtlasState state() { return currentAtlas().getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank()); }

    public static String atlasId() {
        ItemStack stack = currentAtlas();
        String id = stack.getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank()).id();
        if (!stack.isEmpty() && stack == previousAtlas && id.isBlank()) return lastAtlasId;
        previousAtlas = stack;
        return id;
    }

    public static boolean hasAtlas() { return !currentAtlas().isEmpty(); }
    /** Suppress the floating minimap while the player visibly holds the atlas. */
    static boolean holdingAtlas() {
        var player = Minecraft.getInstance().player;
        return player != null && (player.getMainHandItem().is(CartographMod.ATLAS.get())
                || player.getOffhandItem().is(CartographMod.ATLAS.get()));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && hasAtlas()) mc.setScreen(new AtlasScreen());
    }

    public static String dimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? "" : level.dimension().location().toString();
    }

    static String dimensionName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> dim;
        };
    }

    private static CacheKey key(int x, int z) { return new CacheKey(atlasId(), dimension(), x, z); }
    private static String worldNamespace() {
        Minecraft mc = Minecraft.getInstance();
        String key;
        if (mc.getSingleplayerServer() != null)
            key = "local:" + mc.getSingleplayerServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize();
        else if (mc.getCurrentServer() != null)
            key = "remote:" + mc.getCurrentServer().ip;
        else
            key = "unknown:" + (mc.level == null ? "" : mc.level.dimension().location());
        return ClientPageStore.namespace(key);
    }
    private static ClientPageStore.Key diskKey(CacheKey k) {
        return new ClientPageStore.Key(worldNamespace(), k.atlasId(), k.dimension(), k.px(), k.pz());
    }

    public static MapNetwork.Page page(int x, int z) {
        Entry e = PAGES.get(key(x, z)); return e == null ? null : e.page();
    }
    static ResourceLocation texture(int x, int z) {
        CacheKey k = key(x, z);
        Entry e = PAGES.get(k);
        if (e != null && e.id() == null && drawable(e.page())) REDRAW.add(k);
        return e == null ? null : e.id();
    }
    static NativeImage image(int x, int z) {
        CacheKey k = key(x, z);
        Entry e = PAGES.get(k);
        if (e != null && e.texture() == null && drawable(e.page())) REDRAW.add(k);
        return e == null || e.texture() == null ? null : e.texture().getPixels();
    }
    static int revision() { return textureRevision; }

    static MapNetwork.Page coverage() {
        return coverage != null && coverage.atlasId().equals(atlasId()) && coverage.dimension().equals(dimension())
                ? coverage : null;
    }

    public static boolean covered(int x, int z) {
        MapNetwork.Page c = coverage();
        return c == null || c.anchorDimension().equals(dimension())
                && MapSheetCoverage.covers(c.anchorX(), c.anchorZ(), x, z,
                        c.totalPages(), c.sheetSideBlocks());
    }

    static boolean surveyed(int x, int z) {
        MapNetwork.Page p = page(Math.floorDiv(x, 128), Math.floorDiv(z, 128));
        if (p == null || !p.inRange()) return false;
        int i = Math.floorMod(z, 128) / 16 * 8 + Math.floorMod(x, 128) / 16;
        return (p.surveyedChunks() & 1L << i) != 0;
    }

    static int pageCount() { MapNetwork.Page c = coverage(); return c == null ? state().pages() : c.totalPages(); }
    static boolean awaitingPage() { return !IN_FLIGHT.isEmpty() || !QUEUED.isEmpty(); }
    static int queuedCount() { return QUEUED.size() + IN_FLIGHT.size(); }
    static int cachedCount() { return PAGES.size(); }
    /** Keep the in-flight server task but discard obsolete HUD/viewport queue
     * after a manual pan or zoom, so newly visible tiles jump to the front. */
    static void prioritizeViewport() { QUEUED.clear(); }

    public static boolean queue(int x, int z, boolean force) { return queue(x, z, force, false); }

    /** Return whether a NEW tile request was queued, so visible missing tiles
     * beyond the first 64 already cached pages are not starved. */
    static boolean queue(int x, int z, boolean force, boolean cachedOnly) {
        if (Math.abs((long) x) > 234375 || Math.abs((long) z) > 234375 || !hasAtlas() || !covered(x, z)) return false;
        CacheKey key = key(x, z);
        Entry entry = PAGES.get(key);
        if (entry == null && !force && !key.atlasId().isBlank()) {
            // First display the previous server-confirmed page from local disk.
            // The actual server refresh happens after the cached pixels appear.
            ClientPageStore.request(Minecraft.getInstance().gameDirectory.toPath(), diskKey(key));
        }
        if (!force && entry != null) {
            // Fully drawn map tiles need no continual re-reading. Partially
            // drawn tiles can gain world chunks and are rechecked sooner.
            int refreshAfter = entry.page().painted() >= 64 ? SURFACE_RECHECK_TICKS : 600;
            if (ticks - entry.updated() < refreshAfter) return false;
        }
        if (IN_FLIGHT.containsKey(key)) return false;
        if (QUEUED.size() >= CACHE_LIMIT && !QUEUED.containsKey(key)) return false;
        boolean first = !QUEUED.containsKey(key);
        QUEUED.merge(key, cachedOnly, (before, after) -> before && after);
        return first;
    }

    static MapNetwork.CostSettings settings() { return settings; }
    static MapNetwork.PerformanceSettings performance() { return performance; }
    static MapNetwork.RefreshProgress refreshProgress() { return refresh; }
    static void acceptPerformance(MapNetwork.PerformanceSettings value) {
        if (value.workers() >= 2 && value.workers() <= 8
                && value.budgetMs() >= 2 && value.budgetMs() <= 8) performance = value;
    }
    static void acceptRefreshProgress(MapNetwork.RefreshProgress value) {
        if (value.state() < 0 || value.state() > 4 || value.done() < 0
                || value.total() < 0 || value.done() > value.total()
                || value.failed() < 0 || value.failed() > value.done()) return;
        refresh = value;
    }
    static void sendPerformance(int workers, int budget) {
        PacketDistributor.sendToServer(new MapNetwork.SetPerformance(workers, budget));
    }
    static void refreshControl(int action) {
        PacketDistributor.sendToServer(new MapNetwork.RefreshControl(atlasId(), action));
    }
    public static void acceptSettings(MapNetwork.CostSettings value) { if (value.rates().valid()) settings = value; }
    static void sendSettings(MapNetwork.MaterialRates rates) {
        PacketDistributor.sendToServer(new MapNetwork.SetCostSettings(rates));
    }
    static void sendMarker(MapNetwork.EditMarker value) { PacketDistributor.sendToServer(value); }
    static void sendRegion(MapNetwork.EditRegion value) { PacketDistributor.sendToServer(value); }
    static void requestAnnotations() { PacketDistributor.sendToServer(new MapNetwork.RequestAnnotations(atlasId())); }
    static void sendRefill(int slot) { PacketDistributor.sendToServer(new MapNetwork.Refill(atlasId(), slot)); }
    static void sendAddPage() { PacketDistributor.sendToServer(new MapNetwork.AddPage(atlasId())); }
    static List<NamedRegion> regions() { return regions; }
    static List<AtlasMarker> markers() { return markers; }
    public static void acceptAnnotations(MapNetwork.Annotations value) {
        if (value.atlasId().equals(atlasId()) && value.dimension().equals(dimension())) {
            regions = value.entries();
            markers = value.markers();
        }
    }

    static void redrawMaps() { REDRAW.addAll(PAGES.keySet()); }
    static List<MapNetwork.Page> cachedPages() {
        String atlas = atlasId(), dim = dimension();
        List<MapNetwork.Page> result = new ArrayList<>();
        for (var e : PAGES.entrySet())
            if (e.getKey().atlasId.equals(atlas) && e.getKey().dimension.equals(dim) && e.getValue().page().inRange())
                result.add(e.getValue().page());
        return result;
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (!PAGES.isEmpty() || coverage != null || !IN_FLIGHT.isEmpty() || !QUEUED.isEmpty()) clear();
            return;
        }
        String dim = dimension(), id = atlasId();
        if (!lastDimension.equals(dim) || !lastAtlasId.equals(id)) {
            clear();
            lastDimension = dim; lastAtlasId = id;
        }
        while (OPEN.consumeClick()) if (hasAtlas()) open();
        ticks++;
        if (hasAtlas() && (ClientMapSettings.get().hud(ClientMapSettings.ENABLED) || holdingAtlas())
                && (mc.screen == null || mc.screen instanceof HudAdjustScreen) && ticks % 10 == 1) {
            int radius = (int) Math.ceil(120 * Math.sqrt(2) / ClientMapSettings.get().hudZoom);
            int bx = mc.player.getBlockX(), bz = mc.player.getBlockZ();
            queue(Math.floorDiv(bx, 128), Math.floorDiv(bz, 128), false);
            for (int z = Math.floorDiv(bz - radius, 128); z <= Math.floorDiv(bz + radius, 128); z++)
                for (int x = Math.floorDiv(bx - radius, 128); x <= Math.floorDiv(bx + radius, 128); x++) queue(x, z, false);
        }
        for (int i = 0; i < 16; i++) {
            ClientPageStore.Loaded cached = ClientPageStore.poll();
            if (cached == null) break;
            CacheKey k = new CacheKey(cached.key().atlas(), cached.key().dimension(),
                    cached.key().x(), cached.key().z());
            if (cached.page() != null && diskKey(k).world().equals(cached.key().world())
                    && k.atlasId().equals(atlasId()) && k.dimension().equals(dimension())
                    && !PAGES.containsKey(k) && covered(k.px(), k.pz())) {
                // CPU pixels are immediately available. GPU upload follows the
                // 2-tiles/tick budget without blocking world rendering.
                PAGES.put(k, new Entry(cached.page(), null, null, null,
                        ticks - SURFACE_RECHECK_TICKS));
                REDRAW.add(k);
                QUEUED.remove(k);
                textureRevision++;
            }
        }
        Iterator<java.util.Map.Entry<CacheKey, Integer>> active = IN_FLIGHT.entrySet().iterator();
        while (active.hasNext()) {
            var request = active.next();
            if (ticks - request.getValue() < 800) continue;
            CacheKey expired = request.getKey();
            active.remove();
            QUEUED.put(expired, false); // Retry crashed/aborted request.
        }
        Iterator<CacheKey> redraw = REDRAW.iterator();
        int budget = 2;
        while (redraw.hasNext() && budget-- > 0) {
            CacheKey k = redraw.next(); redraw.remove();
            Entry old = PAGES.get(k);
            if (old == null || !drawable(old.page())) continue;
            float[] light = old.light() == null ? PixelPageTexture.lightMap(heightWindow(k, old.page())) : old.light();
            DynamicTexture texture = PixelPageTexture.create(old.page(), light, ClientMapSettings.get());
            ResourceLocation location = mc.getTextureManager().register("cartograph/page", texture);
            PAGES.put(k, new Entry(old.page(), texture, location, light, old.updated()));
            release(old); textureRevision++;
        }
        if (ticks % 2 == 1) {
            while (IN_FLIGHT.size() < MAX_PARALLEL_PAGES && !QUEUED.isEmpty()) {
                var it = QUEUED.entrySet().iterator();
                var job = it.next();
                CacheKey k = job.getKey();
                boolean cached = job.getValue();
                it.remove();
                if (k.atlasId().equals(atlasId()) && k.dimension().equals(dimension()) && covered(k.px(), k.pz())) {
                    IN_FLIGHT.put(k, ticks);
                    PacketDistributor.sendToServer(new MapNetwork.Request(k.atlasId(), k.px(), k.pz(), cached));
                }
            }
        }
        trimGpuCache(mc);
    }

    /** Preserve CPU map data when panning, and cap expensive GPU textures.
     * Returning to a previously visited area reconstructs texture locally,
     * without network packets or rescanning any saved chunks. */
    private static void trimGpuCache(Minecraft mc) {
        int loadedTextures = 0;
        for (Entry e : PAGES.values()) if (e.texture() != null) loadedTextures++;
        if (loadedTextures <= GPU_TEXTURE_LIMIT) return;
        List<CacheKey> toRelease = new ArrayList<>();
        for (var e : PAGES.entrySet()) {
            if (loadedTextures <= GPU_TEXTURE_LIMIT) break;
            if (e.getValue().texture() == null) continue;
            if (REDRAW.contains(e.getKey())) continue;
            toRelease.add(e.getKey()); loadedTextures--;
        }
        for (CacheKey k : toRelease) {
            Entry e = PAGES.get(k);
            if (e == null || e.texture() == null) continue;
            PAGES.put(k, new Entry(e.page(), null, null, e.light(), e.updated()));
            release(e);
        }
    }

    private static short[] heightWindow(CacheKey key, MapNetwork.Page page) {
        int side = AtlasPage.SIDE_BLOCKS, halo = TerrainRelief.HALO, stride = side + 2 * halo;
        short[] window = new short[stride * stride];
        Arrays.fill(window, TerrainRelief.UNKNOWN_HEIGHT);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            MapNetwork.Page source = page;
            if (dx != 0 || dz != 0) {
                Entry neighbor = PAGES.get(adjacent(key, dx, dz));
                if (neighbor == null) continue;
                source = neighbor.page();
            }
            if (!source.inRange() || source.heights().length != MapNetwork.Page.PIXELS) continue;
            int originX = dx * side, originZ = dz * side;
            int x0 = Math.max(-halo, originX), x1 = Math.min(side + halo, originX + side);
            int z0 = Math.max(-halo, originZ), z1 = Math.min(side + halo, originZ + side);
            for (int z = z0; z < z1; z++) {
                int src = (z - originZ) * side + x0 - originX;
                int dst = (z + halo) * stride + x0 + halo;
                System.arraycopy(source.heights(), src, window, dst, x1 - x0);
            }
        }
        return window;
    }

    private static CacheKey adjacent(CacheKey k, int dx, int dz) {
        return new CacheKey(k.atlasId(), k.dimension(), k.px() + dx, k.pz() + dz);
    }

    private static void redrawNeighbors(CacheKey key) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dz == 0) continue;
            CacheKey k = adjacent(key, dx, dz);
            Entry entry = PAGES.get(k);
            if (entry == null || !drawable(entry.page())) continue;
            PAGES.put(k, new Entry(entry.page(), entry.texture(), entry.id(), null, entry.updated()));
            REDRAW.add(k);
        }
    }

    private static boolean drawable(MapNetwork.Page page) { return page.inRange() && page.painted() > 0; }
    private static void release(Entry entry) {
        if (entry != null && entry.id() != null) Minecraft.getInstance().getTextureManager().release(entry.id());
    }

    public static void acceptFeedback(MapNetwork.Feedback result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AtlasScreen screen)
            screen.feedback(result.ok(), result.message(), result.surveyChanged());
        else if (mc.player != null) mc.player.displayClientMessage(Component.literal(result.message()), true);
    }

    /** World changes invalidate only one native 128x128 tile.
     * Keep the old texture visible while the server refreshes it. */
    public static void invalidatePage(MapNetwork.InvalidatePage invalidation) {
        if (!hasAtlas() || !invalidation.atlasId().equals(atlasId())
                || !invalidation.dimension().equals(dimension())) return;
        CacheKey key = new CacheKey(invalidation.atlasId(), invalidation.dimension(),
                invalidation.pageX(), invalidation.pageZ());
        STALE.add(key);
        if (!IN_FLIGHT.containsKey(key)) {
            QUEUED.remove(key);
            QUEUED.put(key, false);
        }
    }

    public static void acceptPage(MapNetwork.Page page) {
        Minecraft mc = Minecraft.getInstance();
        String requested = atlasId(), dim = dimension();
        if (!hasAtlas() || !page.dimension().equals(dim) || !requested.isBlank() && !requested.equals(page.atlasId())) return;
        boolean first = coverage() == null;
        int previousPageCount = first ? -1 : coverage.totalPages();
        if (!page.atlasId().equals(lastAtlasId) || !dim.equals(lastDimension)) {
            QUEUED.clear();
            previousAtlas = currentAtlas();
            lastAtlasId = page.atlasId();
            lastDimension = dim;
        }
        CacheKey currentRequest = new CacheKey(page.atlasId(), dim, page.pageX(), page.pageZ());
        if (IN_FLIGHT.containsKey(currentRequest)) {
            if (page.complete()) {
                IN_FLIGHT.remove(currentRequest);
                if (STALE.remove(currentRequest)) QUEUED.put(currentRequest, false);
            } else IN_FLIGHT.put(currentRequest, ticks);
        }
        coverage = page;
        QUEUED.keySet().removeIf(k -> !covered(k.px(), k.pz()));

        CacheKey k = new CacheKey(page.atlasId(), dim, page.pageX(), page.pageZ());
        Entry previous = PAGES.get(k);
        MapNetwork.Page old = previous == null ? null : previous.page();
        if (old == null || (!page.cachedOnly() || (page.surveyedChunks() & old.surveyedChunks()) == old.surveyedChunks())
                && (page.complete() || !old.complete() || !drawable(old))) {
            boolean changedHeights = old == null || !Arrays.equals(old.heights(), page.heights());
            boolean changed = changedHeights || old == null || page.coloredChunks() != old.coloredChunks()
                    || !Arrays.equals(old.pixels(), page.pixels()) || !Arrays.equals(old.features(), page.features())
                    || !Arrays.equals(old.waterDepths(), page.waterDepths());
            DynamicTexture texture = previous == null ? null : previous.texture();
            ResourceLocation id = previous == null ? null : previous.id();
            if (drawable(page)) {
                if (changed || texture == null) REDRAW.add(k);
            } else {
                REDRAW.remove(k);
                release(previous); texture = null; id = null;
                if (previous != null) textureRevision++;
            }
            PAGES.put(k, new Entry(page, texture, id, changedHeights || previous == null ? null : previous.light(), ticks));
            // Avoid recomputing adjacent GPU textures at every partial
            // milestone: wait for the completed height field.
            if (changedHeights && page.complete() &&
                    (page.heights().length != 0 || old != null && old.heights().length != 0))
                redrawNeighbors(k);
            while (PAGES.size() > CACHE_LIMIT) {
                CacheKey oldest = PAGES.keySet().iterator().next();
                Entry removed = PAGES.remove(oldest);
                REDRAW.remove(oldest); release(removed); textureRevision++;
                if (removed.page().heights().length != 0) redrawNeighbors(oldest);
            }
        }
        if (page.complete() && page.inRange() && page.painted() > 0
                && !page.cachedOnly() && !page.atlasId().isBlank())
            ClientPageStore.save(mc.gameDirectory.toPath(), diskKey(k), page);
        if (mc.screen instanceof AtlasScreen screen) {
            if (first) screen.coverageReady();
            else if (previousPageCount != page.totalPages()) screen.coverageChanged();
        }
    }

    public static void clear() {
        for (Entry e : PAGES.values()) release(e);
        PAGES.clear(); QUEUED.clear(); REDRAW.clear(); IN_FLIGHT.clear(); STALE.clear();
        coverage = null; regions = List.of(); markers = List.of(); previousAtlas = ItemStack.EMPTY;
        lastAtlasId = ""; lastDimension = ""; textureRevision++;
        settings = new MapNetwork.CostSettings(MapNetwork.MaterialRates.DEFAULT, false);
        performance = new MapNetwork.PerformanceSettings(4, 4, false);
        refresh = new MapNetwork.RefreshProgress(0, 0, 0, 0);
        AtlasHud.clear();
        ClientPageStore.reset();
    }

    private static void hud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || !hasAtlas()) return;
        if (holdingAtlas()) AtlasHud.drawHeld(event.getGuiGraphics());
        else if (ClientMapSettings.get().hud(ClientMapSettings.ENABLED))
            AtlasHud.draw(event.getGuiGraphics(), false);
    }
}
