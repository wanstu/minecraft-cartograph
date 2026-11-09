package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Loader-separated client renderer. Multiple @Mod classes for one ID are supported by NeoForge. */
@Mod(value = CartographMod.MOD_ID, dist = Dist.CLIENT)
public final class CartographClient {
    public static final KeyMapping OPEN =
            new KeyMapping("key.cartograph.open", GLFW.GLFW_KEY_M, "key.categories.cartograph");
    private static final int CACHE_LIMIT = 24;
    private static final LinkedHashMap<CacheKey, Entry> PAGES = new LinkedHashMap<>(32, 0.75f, true);
    private static final Set<CacheKey> QUEUED = new LinkedHashSet<>();
    private static int ticks;
    private static CacheKey inFlight;
    private static int sentAt;
    private static String lastDimension = "";
    private static String lastAtlasId = "";
    private static ItemStack previousAtlas = ItemStack.EMPTY;
    private static MapNetwork.CostSettings settings = new MapNetwork.CostSettings(10,10,10,false);

    public record CacheKey(String atlasId, String dimension, int px, int pz) {}
    public record Entry(MapNetwork.Page page, DynamicTexture texture, ResourceLocation textureId, int updated) {}

    public CartographClient(IEventBus bus) {
        bus.addListener(CartographClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(CartographClient::tick);
        NeoForge.EVENT_BUS.addListener(CartographClient::hud);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN); }

    public static ItemStack currentAtlas() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        if (mc.player.getMainHandItem().is(CartographMod.ATLAS.get())) return mc.player.getMainHandItem();
        if (mc.player.getOffhandItem().is(CartographMod.ATLAS.get())) return mc.player.getOffhandItem();
        for (ItemStack stack : mc.player.getInventory().items) {
            if (stack.is(CartographMod.ATLAS.get())) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static String atlasId() {
        ItemStack current = currentAtlas();
        if (current.isEmpty()) {
            previousAtlas = ItemStack.EMPTY;
            return "";
        }
        String id = current.getOrDefault(CartographMod.ATLAS_STATE.get(), AtlasState.blank()).id();
        // A newly crafted book has no ID until the server processes its first
        // request. Keep the returned ID while this exact item awaits syncing.
        if (id.isBlank() && current == previousAtlas && !lastAtlasId.isBlank()) return lastAtlasId;
        previousAtlas = current;
        return id;
    }

    public static boolean hasAtlas() { return !currentAtlas().isEmpty(); }

    public static boolean held() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.getMainHandItem().is(CartographMod.ATLAS.get())
                || mc.player.getOffhandItem().is(CartographMod.ATLAS.get()));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && hasAtlas()) mc.setScreen(new AtlasScreen());
    }

    public static String dimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    public static CacheKey key(int pageX, int pageZ) {
        return new CacheKey(atlasId(), dimension(), pageX, pageZ);
    }

    public static MapNetwork.Page page(int px, int pz) {
        CacheKey key = key(px, pz);
        Entry e = PAGES.get(key);
        return e == null ? null : e.page();
    }

    public static ResourceLocation texture(int px, int pz) {
        Entry e = PAGES.get(key(px, pz));
        return e == null ? null : e.textureId();
    }

    public static void requestPage(int pageX, int pageZ) {
        if (hasAtlas()) PacketDistributor.sendToServer(
                new MapNetwork.Request(atlasId(), pageX, pageZ));
    }

    public static void queue(int px, int pz, boolean force) {
        if (Math.abs((long) px) > 234375 || Math.abs((long) pz) > 234375 || !hasAtlas()) return;
        CacheKey key = key(px, pz);
        Entry entry = PAGES.get(key);
        if (!force && entry != null && ticks - entry.updated() < 200) return;
        QUEUED.add(key);
    }

    public static MapNetwork.CostSettings settings() { return settings; }
    public static void acceptSettings(MapNetwork.CostSettings newSettings) {
        settings = newSettings;
    }
    public static void sendSettings(int ink, int pigment, int glow) {
        if (hasAtlas()) PacketDistributor.sendToServer(
                new MapNetwork.SetCostSettings(ink, pigment, glow));
    }

    public static void sendMarker(MapNetwork.EditMarker request) {
        if (hasAtlas()) PacketDistributor.sendToServer(request);
    }

    public static void exportAt(int blockX, int blockZ) {
        if (!hasAtlas()) return;
        PacketDistributor.sendToServer(new MapNetwork.ExportMap(atlasId(),
                Math.floorDiv(blockX, 128), Math.floorDiv(blockZ, 128)));
    }

    public static void sendRefill(int slot) {
        if (hasAtlas()) PacketDistributor.sendToServer(new MapNetwork.Refill(atlasId(), slot));
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (!PAGES.isEmpty()) clear();
            return;
        }
        String dim = dimension(), atlas = atlasId();
        if (!lastDimension.equals(dim) || !lastAtlasId.equals(atlas)) {
            clear();
            lastDimension = dim;
            lastAtlasId = atlas;
        }
        while (OPEN.consumeClick()) if (hasAtlas()) open();
        ticks++;

        if (held() && mc.screen == null && ticks % 200 == 1) {
            int px = Math.floorDiv(mc.player.getBlockX(), 128);
            int pz = Math.floorDiv(mc.player.getBlockZ(), 128);
            queue(px, pz, true);
        }

        // One request in flight per player. Without this guard, opening a
        // multi-page book with several online viewers can replace an
        // incomplete 64-chunk request forever on busy servers.
        if (inFlight != null && ticks - sentAt >= 220) inFlight = null;
        if (ticks % 6 == 1 && inFlight == null && !QUEUED.isEmpty()) {
            Iterator<CacheKey> it = QUEUED.iterator();
            CacheKey q = it.next();
            it.remove();
            if (q.atlasId().equals(atlasId()) && q.dimension().equals(dimension())) {
                inFlight = q;
                sentAt = ticks;
                requestPage(q.px(), q.pz());
            }
        }
    }

    public static void acceptPage(MapNetwork.Page page) {
        if (page.pixels().length != 128 * 128 || page.features().length != 128 * 128) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(page.dimension()))
            return;
        String requestedId = atlasId();
        if (inFlight != null && inFlight.px() == page.pageX()
                && inFlight.pz() == page.pageZ()
                && inFlight.dimension().equals(page.dimension())) {
            inFlight = null;
        }
        // Fresh books are assigned an ID on first server access and sync later.
        if (!requestedId.isBlank() && !requestedId.equals(page.atlasId())) return;
        lastAtlasId = page.atlasId();
        CacheKey key = new CacheKey(page.atlasId(), page.dimension(), page.pageX(), page.pageZ());
        Entry previous = PAGES.remove(key);
        if (previous != null) mc.getTextureManager().release(previous.textureId());

        NativeImage pixels = new NativeImage(128, 128, false);
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int packed = Byte.toUnsignedInt(page.pixels()[x + z * 128]);
            int rgb;
            if (!page.inRange() || packed == 0 || packed > 64) rgb = 0xe4d5b4;
            else {
                int mapId = packed - 1;
                rgb = MapColor.byId(mapId).col & 0xffffff;
                int chunkX = x / 16, chunkZ = z / 16;
                boolean colorized = (page.coloredChunks() & (1L << (chunkZ * 8 + chunkX))) != 0;
                if (!colorized) {
                    int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                    int lightness = (r * 30 + g * 59 + b * 11) / 100;
                    rgb = lightness >= 132 ? 0xf4efdf : 0x292724;
                }
                int feature = Byte.toUnsignedInt(page.features()[x + z * 128]);
                if (feature == 1) rgb = colorized ? 0xa58352 : 0x282724; // surface path
                if (feature == 2) rgb = colorized ? 0x353e42 : 0x111b1a; // vanilla rail
                if (feature == 3) rgb = colorized ? 0x5b8790 : 0x111b1a; // Create track
            }
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            pixels.setPixelRGBA(x, z, 0xff000000 | (b << 16) | (g << 8) | r);
        }
        DynamicTexture texture = new DynamicTexture(pixels);
        ResourceLocation textureId = mc.getTextureManager().register("cartograph/page", texture);
        PAGES.put(key, new Entry(page, texture, textureId, ticks));
        while (PAGES.size() > CACHE_LIMIT) {
            CacheKey oldest = PAGES.keySet().iterator().next();
            Entry removed = PAGES.remove(oldest);
            mc.getTextureManager().release(removed.textureId());
        }
    }

    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Entry e : PAGES.values()) mc.getTextureManager().release(e.textureId());
        PAGES.clear();
        QUEUED.clear();
        inFlight = null;
        lastDimension = "";
        lastAtlasId = "";
        previousAtlas = ItemStack.EMPTY;
        settings = new MapNetwork.CostSettings(10,10,10,false);
    }

    public static void drawPlayer(GuiGraphics g, int left, int top, int display, int px, int pz) {
        Minecraft mc = Minecraft.getInstance();
        MapNetwork.Page page = page(px, pz);
        if (mc.player == null || page == null || !page.compass()) return;
        int playerPX = Math.floorDiv(mc.player.getBlockX(), 128);
        int playerPZ = Math.floorDiv(mc.player.getBlockZ(), 128);
        if (playerPX != px || playerPZ != pz) return;
        double tx = Math.floorMod(mc.player.getBlockX(), 128) / 128.0;
        double tz = Math.floorMod(mc.player.getBlockZ(), 128) / 128.0;
        int x = left + (int) (tx * display), y = top + (int) (tz * display);
        g.fill(x - 2, y - 2, x + 3, y + 3, 0xffed4536);
        g.fill(x - 1, y - 1, x + 2, y + 2, 0xfffff2c0);
    }

    private static void hud(RenderGuiEvent.Post e) {
        if (!held()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        int px = Math.floorDiv(mc.player.getBlockX(), 128);
        int pz = Math.floorDiv(mc.player.getBlockZ(), 128);
        ResourceLocation t = texture(px, pz);
        MapNetwork.Page page = page(px, pz);
        if (t == null || page == null || !page.inRange()) return;
        GuiGraphics g = e.getGuiGraphics();
        int left = g.guiWidth() - 108, top = 15;
        g.fill(left - 5, top - 5, left + 101, top + 110, 0xe01a2420);
        g.fill(left - 2, top - 2, left + 98, top + 98, 0xffc8af7d);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        g.pose().scale(0.75f, 0.75f, 1);
        g.blit(t, 0, 0, 0, 0, 128, 128, 128, 128);
        g.pose().popPose();
        drawPlayer(g, left, top, 96, px, pz);
        g.drawString(mc.font, "Cartograph", left + 2, top + 99, 0xffe9d2a5, false);
    }
}
