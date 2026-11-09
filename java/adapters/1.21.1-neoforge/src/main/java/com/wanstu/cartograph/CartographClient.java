package com.wanstu.cartograph;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** All Minecraft client-only objects live behind the client-side @Mod entry. */
@Mod(value = CartographMod.MOD_ID, dist = Dist.CLIENT)
public final class CartographClient {
    private static final KeyMapping OPEN =
            new KeyMapping("key.cartograph.open", GLFW.GLFW_KEY_M, "key.categories.cartograph");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(CartographMod.MOD_ID, "live_page");
    private static DynamicTexture texture;
    private static MapNetwork.Page last;
    private static int ticks;

    public CartographClient(IEventBus bus) {
        bus.addListener(CartographClient::registerKeys);
        NeoForge.EVENT_BUS.addListener(CartographClient::tick);
        NeoForge.EVENT_BUS.addListener(CartographClient::hud);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) { event.register(OPEN); }

    public static boolean hasAtlas() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (mc.player.getMainHandItem().is(CartographMod.ATLAS.get())
                || mc.player.getOffhandItem().is(CartographMod.ATLAS.get())) return true;
        for (ItemStack stack : mc.player.getInventory().items)
            if (stack.is(CartographMod.ATLAS.get())) return true;
        return false;
    }

    public static boolean held() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.getMainHandItem().is(CartographMod.ATLAS.get())
                || mc.player.getOffhandItem().is(CartographMod.ATLAS.get()));
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && hasAtlas()) mc.setScreen(new AtlasScreen());
    }

    public static void requestPage(int pageX, int pageZ) {
        if (hasAtlas()) PacketDistributor.sendToServer(new MapNetwork.Request(pageX, pageZ));
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (last != null) clear();
            return;
        }
        while (OPEN.consumeClick()) {
            if (hasAtlas()) open();
        }
        // A held book tracks only the current 128x128 page, without loading or revealing others.
        if (held() && mc.screen == null && (++ticks % 200 == 1)) {
            int px = Math.floorDiv(mc.player.getBlockX(), 128);
            int pz = Math.floorDiv(mc.player.getBlockZ(), 128);
            requestPage(px, pz);
        }
    }

    public static void acceptPage(MapNetwork.Page page) {
        if (page.pixels().length != 128 * 128) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(page.dimension())) return;
        last = page;
        if (texture != null) {
            mc.getTextureManager().release(TEXTURE);
            texture = null;
        }
        NativeImage pixels = new NativeImage(128, 128, false);
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int packed = Byte.toUnsignedInt(page.pixels()[x + z * 128]);
            int rgb;
            if (!page.inRange() || packed == 0) rgb = 0xe4d5b4;
            else {
                int mapId = packed - 1;
                if (mapId >= 64) { rgb = 0xe4d5b4; }
                else { rgb = MapColor.byId(mapId).col & 0xffffff; }
                int chunkX = x / 16, chunkZ = z / 16;
                boolean colorized = (page.coloredChunks() & (1L << (chunkZ * 8 + chunkX))) != 0;
                if (!colorized) {
                    int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                    int lightness = (r * 30 + g * 59 + b * 11) / 100;
                    // Base atlas is strictly two-color, not grayscale.
                    rgb = lightness >= 132 ? 0xf4efdf : 0x292724;
                }
            }
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            pixels.setPixelRGBA(x, z, 0xff000000 | (b << 16) | (g << 8) | r);
        }
        texture = new DynamicTexture(pixels);
        mc.getTextureManager().register(TEXTURE, texture);
    }

    private static void clear() {
        if (texture != null) Minecraft.getInstance().getTextureManager().release(TEXTURE);
        texture = null; last = null;
    }

    public static MapNetwork.Page lastPage() { return last; }
    public static ResourceLocation textureId() { return TEXTURE; }
    public static boolean hasTextureFor(int px, int pz) {
        Minecraft mc = Minecraft.getInstance();
        return texture != null && last != null && mc.level != null
                && last.dimension().equals(mc.level.dimension().location().toString())
                && last.pageX() == px && last.pageZ() == pz;
    }

    public static void drawPlayer(GuiGraphics g, int left, int top, int displayedSize, int px, int pz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || last == null || !last.compass()) return;
        int playerPX = Math.floorDiv(mc.player.getBlockX(), 128);
        int playerPZ = Math.floorDiv(mc.player.getBlockZ(), 128);
        if (playerPX != px || playerPZ != pz) return;
        double tx = Math.floorMod(mc.player.getBlockX(), 128) / 128.0;
        double tz = Math.floorMod(mc.player.getBlockZ(), 128) / 128.0;
        int x = left + (int) (tx * displayedSize), y = top + (int) (tz * displayedSize);
        g.fill(x - 2, y - 2, x + 3, y + 3, 0xffed4536);
        g.fill(x - 1, y - 1, x + 2, y + 2, 0xfffff2c0);
    }

    private static void hud(RenderGuiEvent.Post e) {
        if (!held()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        int px = Math.floorDiv(mc.player.getBlockX(), 128);
        int pz = Math.floorDiv(mc.player.getBlockZ(), 128);
        if (!hasTextureFor(px, pz) || !last.inRange()) return;
        GuiGraphics g = e.getGuiGraphics();
        int left = g.guiWidth() - 108, top = 15;
        g.fill(left - 5, top - 5, left + 101, top + 110, 0xe01a2420);
        g.fill(left - 2, top - 2, left + 98, top + 98, 0xffc8af7d);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        g.pose().scale(0.75f, 0.75f, 1);
        g.blit(TEXTURE, 0, 0, 0, 0, 128, 128, 128, 128);
        g.pose().popPose();
        drawPlayer(g, left, top, 96, px, pz);
        g.drawString(mc.font, "Cartograph", left + 2, top + 99, 0xffe9d2a5, false);
    }
}
