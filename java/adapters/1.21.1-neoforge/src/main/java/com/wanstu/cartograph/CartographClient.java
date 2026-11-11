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
    // High-zoom map view may contain hundreds of pages. Previously GPU LRU
    // evicted visible textures every frame at 140, causing a moving sea of
    // paper holes while waiting for the 2-uploads/tick budget. Pin precisely
    // the page textures currently visible; they remain bounded by CACHE_LIMIT.
    private static final Set<CacheKey> VISIBLE_GPU = new LinkedHashSet<>();
    private static final int MAX_PARALLEL_PAGES = 4;
    private static final int DISK_READ_GRACE_TICKS = 16; // 0.8 seconds, never blocks render/tick
    private static final int DISK_VALIDATION_DELAY_TICKS = 200; // show cached terrain first
    private static final LinkedHashMap<CacheKey, Integer> DISK_LOADING = new LinkedHashMap<>();
    // Each restored disk image gets ONE server validation after becoming
    // visible, not another timer every 30s or 10min. Ongoing changes arrive
    // from the WORLD chunk lifecycle and genuine mutation notifications.
    private static final Set<CacheKey> DISK_RECHECK_ONCE = new LinkedHashSet<>();
    private static final LinkedHashMap<CacheKey, Entry> PAGES = new LinkedHashMap<>(32, 0.75f, true);
    private static final LinkedHashMap<CacheKey, Boolean> QUEUED = new LinkedHashMap<>();
    private static final Set<CacheKey> REDRAW = new LinkedHashSet<>();
    private static final LinkedHashMap<CacheKey, Integer> IN_FLIGHT = new LinkedHashMap<>();
    private static final Set<CacheKey> STALE = new LinkedHashSet<>();
    // Only a dye refill activates this lane. It never forces terrain reload.
    private static final Set<String> COLOR_REFILL_ATLASES = new LinkedHashSet<>();
    private static final Set<CacheKey> COLOR_REQUESTED = new LinkedHashSet<>();
    private static final Set<CacheKey> COLOR_QUEUED = new LinkedHashSet<>();
    private static final LinkedHashMap<CacheKey, Integer> COLOR_IN_FLIGHT = new LinkedHashMap<>();

    private static ItemStack previousAtlas = ItemStack.EMPTY;
    private static String lastDimension = "", lastAtlasId = "", lastWorldNamespace = "";
    private static String cachedWorldIdentity = "", cachedWorldNamespace = "";
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
        NeoForge.EVENT_BUS.addListener(CartographClient::hideAtlasDuringHudEditing);
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
        if (!key.equals(cachedWorldIdentity)) {
            cachedWorldIdentity = key;
            cachedWorldNamespace = ClientPageStore.namespace(key);
        }
        return cachedWorldNamespace;
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
        if (entry == null && !key.atlasId().isBlank() && !force) {
            // Disk-first: when returning to a covered/known area, allow the
            // async local cache to return before scheduling a 64-chunk server
            // scan. This is bounded by DISK_READ_GRACE_TICKS; a slow disk or
            // missing file cannot make the map wait indefinitely.
            Integer started = DISK_LOADING.get(key);
            if (started == null && DISK_LOADING.size() < CACHE_LIMIT) {
                ClientPageStore.Key disk = diskKey(key);
                if (ClientPageStore.request(Minecraft.getInstance().gameDirectory.toPath(), disk)) {
                    DISK_LOADING.put(key, ticks);
                    // IMPORTANT for zoomed-out 1024-page maps: disk reads
                    // count toward the viewport's 64 new jobs per pass too.
                    // Without this, a frame could request EVERY visible tile.
                    return true;
                }
                if (ClientPageStore.isPending(disk) || ClientPageStore.readsAtCapacity())
                    return true; // budget saturated: retry on next viewport tick
                // Already attempted and no longer in flight: fall back to the
                // server, which verifies any existing survey entitlement.
            }
            if (started != null && ticks - started < DISK_READ_GRACE_TICKS) return false;
            DISK_LOADING.remove(key);
        } else if (force) {
            // User-triggered refresh and server invalidation always bypass
            // pending disk loads. A late disk result cannot replace live data.
            DISK_LOADING.remove(key);
        }
        if (!force && entry != null) {
            if (!DISK_RECHECK_ONCE.contains(key)
                    || ticks - entry.updated() < DISK_VALIDATION_DELAY_TICKS)
                return false;
        }
        if (IN_FLIGHT.containsKey(key)) return false;
        if (QUEUED.size() >= CACHE_LIMIT && !QUEUED.containsKey(key)) return false;
        boolean first = !QUEUED.containsKey(key);
        QUEUED.merge(key, cachedOnly, (before, after) -> before && after);
        // When a saved display image is restored, validate it just ONCE.
        // Newly generated/updated chunks notify the client independently.
        if (first && entry != null) DISK_RECHECK_ONCE.remove(key);
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

    /** Recolor only tiles already present in RAM. Further pan/zoom will
     * discover other cached pages and schedule their own small color deltas. */
    static void colorRefilled() {
        String id = atlasId();
        if (id.isBlank()) return;
        COLOR_REFILL_ATLASES.add(id);
        COLOR_REQUESTED.removeIf(k -> k.atlasId().equals(id));
        COLOR_QUEUED.removeIf(k -> k.atlasId().equals(id));
    }

    static void queueColorVisible(int x0, int x1, int z0, int z1) {
        if (!COLOR_REFILL_ATLASES.contains(atlasId())) return;
        int budget = 64;
        for (var entry : PAGES.entrySet()) {
            if (budget == 0) break;
            CacheKey k = entry.getKey();
            if (!k.atlasId().equals(atlasId()) || !k.dimension().equals(dimension())
                    || k.px() < x0 || k.px() > x1 || k.pz() < z0 || k.pz() > z1) continue;
            MapNetwork.Page page = entry.getValue().page();
            if (!page.complete() || !page.inRange()
                    || (page.surveyedChunks() != 0
                        && (page.surveyedChunks() & ~page.coloredChunks()) == 0)
                    || COLOR_REQUESTED.contains(k) || COLOR_IN_FLIGHT.containsKey(k)
                    || IN_FLIGHT.containsKey(k)) continue;
            COLOR_REQUESTED.add(k);
            COLOR_QUEUED.add(k);
            budget--;
        }
    }

    static void acceptColorDelta(MapNetwork.ColorDelta delta) {
        if (!hasAtlas() || !delta.atlasId().equals(atlasId())
                || !delta.dimension().equals(dimension())) return;
        CacheKey k = new CacheKey(delta.atlasId(), delta.dimension(),
                delta.pageX(), delta.pageZ());
        COLOR_IN_FLIGHT.remove(k);
        if (IN_FLIGHT.containsKey(k)) {
            // A full-page survey racing the pigment change can overwrite its
            // newly colored cells. Wait for that server response, then request
            // one fresh color delta instead of uploading a stale texture.
            COLOR_REQUESTED.remove(k);
            return;
        }
        Entry before = PAGES.get(k);
        if (before == null) return;
        MapNetwork.Page recolored = ColorPatchApplier.apply(before.page(), delta);
        if (recolored == null) return;
        boolean pixelsChanged = !Arrays.equals(
                recolored.pixels(), before.page().pixels());
        boolean bitsChanged = recolored.coloredChunks() != before.page().coloredChunks();
        boolean changed = bitsChanged || pixelsChanged
                || recolored.pigmentUnits() != before.page().pigmentUnits()
                || recolored.totalColored() != before.page().totalColored();
        if (!changed) return;
        PAGES.put(k, new Entry(recolored, before.texture(), before.id(),
                before.light(), before.updated()));
        if (bitsChanged || pixelsChanged) REDRAW.add(k);
        ClientPageStore.save(Minecraft.getInstance().gameDirectory.toPath(), diskKey(k), recolored);
    }

    static void redrawMaps() { REDRAW.addAll(PAGES.keySet()); }
    /** Called from AtlasScreen.renderMap after calculating its visible pages.
     * No terrain scan or network packet is generated; only protects GPU
     * textures currently required on screen from the LRU trimmer. */
    static void pinVisiblePages(List<MapNetwork.Page> visible) {
        VISIBLE_GPU.clear();
        String atlas = atlasId(), dim = dimension();
        for (MapNetwork.Page page : visible)
            if (page.atlasId().equals(atlas) && page.dimension().equals(dim))
                VISIBLE_GPU.add(new CacheKey(atlas, dim, page.pageX(), page.pageZ()));
    }

    static List<MapNetwork.Page> cachedPages() {
        return visiblePages(Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** One bounded pass through the LRU cache per rendered frame. A 1024-page
     * atlas at 0.2x zoom must not build and iterate the entire page collection
     * again for split layers and map labels. */
    static List<MapNetwork.Page> visiblePages(int x0, int x1, int z0, int z1) {
        String atlas = atlasId(), dim = dimension();
        List<MapNetwork.Page> result = new ArrayList<>();
        for (var e : PAGES.entrySet()) {
            CacheKey key = e.getKey();
            if (key.px() >= x0 && key.px() <= x1 && key.pz() >= z0 && key.pz() <= z1
                    && key.atlasId().equals(atlas) && key.dimension().equals(dim)
                    && e.getValue().page().inRange())
                result.add(e.getValue().page());
        }
        return result;
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (!PAGES.isEmpty() || coverage != null || !IN_FLIGHT.isEmpty() || !QUEUED.isEmpty()) clear();
            return;
        }
        String dim = dimension();
        String currentWorld = worldNamespace();
        // All atlas IDs are scoped to this world. Even a copied item carrying
        // the SAME atlas ID in another save must never reuse its CPU/GPU pixels.
        // A fast server/world transition need not expose a null player tick.
        // Item changes within one world are NOT a hard boundary.
        if (!lastDimension.equals(dim) || !lastWorldNamespace.equals(currentWorld)) {
            clear();
            lastDimension = dim;
            lastWorldNamespace = currentWorld;
        }
        if (!hasAtlas()) {
            // Server cancels map scans when the item leaves inventory. Drop
            // stale transport jobs, NOT the expensive surveyed pixels and GPU
            // textures. Same atlas re-picked immediately uses local cache.
            IN_FLIGHT.clear();
            QUEUED.clear();
            STALE.clear();
            DISK_LOADING.clear();
            while (OPEN.consumeClick()) { } // no atlas: key cannot open
            ticks++;
            return;
        }
        String id = atlasId();
        if (!lastAtlasId.equals(id)) {
            // Switching between atlas items in the SAME world should keep
            // each atlas's paid CPU raster and bounded GPU textures. The cache
            // keys already include atlas ID; only session metadata and active
            // transport are invalid across the switch.
            resetAtlasView();
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
            boolean awaitedDisk = DISK_LOADING.remove(k) != null;
            if (cached.page() != null && diskKey(k).world().equals(cached.key().world())
                    && k.atlasId().equals(atlasId()) && k.dimension().equals(dimension())
                    && !PAGES.containsKey(k) && covered(k.px(), k.pz())) {
                // CPU pixels are immediately available. GPU upload follows the
                // 2-tiles/tick budget without blocking world rendering.
                // Persisted image is visible immediately. Background
                // One-time verification after initial disk restore (not a
                // periodic rescan of old pixels every 30s or 10 minutes).
                PAGES.put(k, new Entry(cached.page(), null, null, null, ticks));
                DISK_RECHECK_ONCE.add(k);
                REDRAW.add(k);
                QUEUED.remove(k);
                textureRevision++;
            } else if (awaitedDisk && !PAGES.containsKey(k) && !IN_FLIGHT.containsKey(k)
                    && k.atlasId().equals(atlasId()) && k.dimension().equals(dimension())
                    && covered(k.px(), k.pz())) {
                // A legitimate cache miss should fetch real data immediately;
                // it must not retry disk reading or remain blank forever.
                QUEUED.put(k, false);
            }
        }
        Iterator<java.util.Map.Entry<CacheKey, Integer>> colors = COLOR_IN_FLIGHT.entrySet().iterator();
        while (colors.hasNext()) {
            var request = colors.next();
            if (ticks - request.getValue() < 800) continue;
            CacheKey expired = request.getKey();
            colors.remove();
            COLOR_REQUESTED.remove(expired); // stale ACK: allow bounded retry
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
        if (ticks % 2 == 0) {
            while (COLOR_IN_FLIGHT.size() < MAX_PARALLEL_PAGES && !COLOR_QUEUED.isEmpty()) {
                CacheKey next = COLOR_QUEUED.iterator().next();
                COLOR_QUEUED.remove(next);
                if (!next.atlasId().equals(atlasId()) || !next.dimension().equals(dimension())
                        || !covered(next.px(), next.pz())) continue;
                if (IN_FLIGHT.containsKey(next)) {
                    COLOR_REQUESTED.remove(next);
                    continue;
                }
                COLOR_IN_FLIGHT.put(next, ticks);
                PacketDistributor.sendToServer(new MapNetwork.RequestColorPage(
                        next.atlasId(), next.px(), next.pz()));
            }
        }
        trimGpuCache(mc);
    }

    /** Preserve CPU map data when panning, and cap expensive GPU textures.
     * Returning to a previously visited area reconstructs texture locally,
     * without network packets or rescanning any saved chunks. */
    private static void trimGpuCache(Minecraft mc) {
        // Pin scope only exists while a large map screen is actively
        // displayed; outside it we release extras to the normal 140 limit.
        if (!(mc.screen instanceof AtlasScreen)) VISIBLE_GPU.clear();
        int loadedTextures = 0;
        for (Entry e : PAGES.values()) if (e.texture() != null) loadedTextures++;
        if (loadedTextures <= GPU_TEXTURE_LIMIT) return;
        List<CacheKey> toRelease = new ArrayList<>();
        for (var e : PAGES.entrySet()) {
            if (loadedTextures <= GPU_TEXTURE_LIMIT) break;
            if (e.getValue().texture() == null) continue;
            if (REDRAW.contains(e.getKey()) || VISIBLE_GPU.contains(e.getKey())) continue;
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
        if (result.ok() && result.colorChanged()) colorRefilled();
        if (mc.screen instanceof AtlasScreen screen)
            screen.feedback(result.ok(), result.message(),
                    result.surveyChanged(), result.colorChanged());
        else if (mc.player != null) mc.player.displayClientMessage(Component.literal(result.message()), true);
    }

    /** World changes invalidate only one native 128x128 tile.
     * Keep the old texture visible while the server refreshes it. */
    public static void invalidatePage(MapNetwork.InvalidatePage invalidation) {
        if (!hasAtlas() || !invalidation.atlasId().equals(atlasId())
                || !invalidation.dimension().equals(dimension())) return;
        CacheKey key = new CacheKey(invalidation.atlasId(), invalidation.dimension(),
                invalidation.pageX(), invalidation.pageZ());
        DISK_LOADING.remove(key);
        if (IN_FLIGHT.containsKey(key)) {
            // A second world edit raced an already-running request. Request
            // once MORE only in this case, after its old snapshot completes.
            STALE.add(key);
        } else {
            // The next request already includes this edit: do not schedule
            // a redundant second full 64-chunk scan.
            STALE.remove(key);
            QUEUED.remove(key);
            QUEUED.put(key, false);
        }
    }

    /** A real world edit replaces only the 16x16 paid terrain pixels.
     * No paint charges, no 64-chunk rescan, and no blank intermediate frame. */
    public static void acceptTerrainPatch(MapNetwork.TerrainPatch patch) {
        if (!hasAtlas() || !patch.atlasId().equals(atlasId())
                || !patch.dimension().equals(dimension())) return;
        CacheKey k = new CacheKey(patch.atlasId(), patch.dimension(),
                Math.floorDiv(patch.chunkX(), 8), Math.floorDiv(patch.chunkZ(), 8));
        Entry old = PAGES.get(k);
        if (old == null) return; // Not viewed yet: future full page request is authoritative.
        if (IN_FLIGHT.containsKey(k)) {
            // A full-page snapshot taken before this edit may still be on the
            // wire. Retry once after it completes, rather than lose the edit.
            STALE.add(k);
            return;
        }
        MapNetwork.Page next = TerrainPatchApplier.apply(old.page(), patch);
        if (next == null) {
            invalidatePage(new MapNetwork.InvalidatePage(patch.atlasId(), patch.dimension(),
                    k.px(), k.pz()));
            return;
        }
        REDRAW.add(k);
        PAGES.put(k, new Entry(next, old.texture(), old.id(), null, ticks));
        // Elevation lighting at an atlas page edge depends on the adjacent
        // page's heights. Interior changes need repainting only THIS page.
        int x = Math.floorMod(patch.chunkX(), 8), z = Math.floorMod(patch.chunkZ(), 8);
        if (x == 0 || x == 7 || z == 0 || z == 7) redrawNeighbors(k);
        if (!next.atlasId().isBlank())
            ClientPageStore.save(Minecraft.getInstance().gameDirectory.toPath(), diskKey(k), next);
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
        // IMPORTANT: a refresh's 8/32-chunk milestones contain blank pixels
        // for every unscanned chunk. Never replace an already complete tile
        // with a partial snapshot: doing so creates moving square holes.
        // Partial pages remain useful for the FIRST discovery of a new area.
        boolean mayReplace = old == null
                || (page.complete()
                    && (!page.cachedOnly() || (page.surveyedChunks() & old.surveyedChunks()) == old.surveyedChunks()))
                || (!old.complete() && !page.cachedOnly() && page.scanned() >= old.scanned())
                || (!drawable(old) && page.scanned() >= old.scanned());
        if (mayReplace) {
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
        if (page.complete() && page.inRange()
                && !page.cachedOnly() && !page.atlasId().isBlank())
            ClientPageStore.save(mc.gameDirectory.toPath(), diskKey(k), page);
        if (mc.screen instanceof AtlasScreen screen) {
            if (first) screen.coverageReady();
            else if (previousPageCount != page.totalPages()) screen.coverageChanged();
        }
    }

    /** Release active atlas metadata/transport, but not paid terrain pixels
     * belonging to other atlas items in this SAME world and dimension. */
    private static void resetAtlasView() {
        QUEUED.clear(); REDRAW.clear(); IN_FLIGHT.clear(); STALE.clear();
        COLOR_QUEUED.clear(); COLOR_IN_FLIGHT.clear(); COLOR_REQUESTED.clear();
        DISK_LOADING.clear();
        coverage = null; regions = List.of(); markers = List.of(); previousAtlas = ItemStack.EMPTY;
        textureRevision++;
        settings = new MapNetwork.CostSettings(MapNetwork.MaterialRates.DEFAULT, false);
        performance = new MapNetwork.PerformanceSettings(4, 4, false);
        refresh = new MapNetwork.RefreshProgress(0, 0, 0, 0);
        AtlasHud.clear();
        ClientPageStore.reset();
    }

    /** Hard boundary on world/dimension change or disconnect. */
    public static void clear() {
        for (Entry e : PAGES.values()) release(e);
        PAGES.clear();
        VISIBLE_GPU.clear();
        DISK_RECHECK_ONCE.clear();
        resetAtlasView();
        lastAtlasId = ""; lastDimension = ""; lastWorldNamespace = "";
        cachedWorldIdentity = ""; cachedWorldNamespace = "";
        COLOR_REFILL_ATLASES.clear();
    }

    private static void hideAtlasDuringHudEditing(
            net.neoforged.neoforge.client.event.RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        // Vanilla would draw a flat inventory icon in front of our custom
        // book folio. Suppress that item-only first-person render (the
        // inventory/hotbar icon is unaffected), avoiding duplicate visuals.
        if (event.getItemStack().is(CartographMod.ATLAS.get())) {
            // The dynamic open folio is rendered at the correct fixed hand
            // anchor by AtlasHud. Do not also render the flat inventory icon.
            event.setCanceled(true);
        }
    }

    private static void hud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || !hasAtlas()) return;
        if (holdingAtlas()) AtlasHud.drawHeld(event.getGuiGraphics());
        else if (ClientMapSettings.get().hud(ClientMapSettings.ENABLED))
            AtlasHud.draw(event.getGuiGraphics(), false);
    }
}
