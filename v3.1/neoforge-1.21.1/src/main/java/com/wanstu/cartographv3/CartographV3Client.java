package com.wanstu.cartographv3;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry. There is deliberately no in-game map Screen in V3.1 WebUI.
 * M starts/opens the loopback WebUI in the user's normal browser.
 */
@Mod(value = CartographV3Mod.MOD_ID, dist = Dist.CLIENT)
public final class CartographV3Client {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.client");
    private static final CartographWebUiServer WEB_UI = new CartographWebUiServer();
    private static boolean qaOpened;

    private static final KeyMapping OPEN = new KeyMapping(
            "key.cartograph_v3.open",
            GLFW.GLFW_KEY_M,
            "key.categories.cartograph_v3");

    public CartographV3Client(IEventBus modBus) {
        modBus.addListener(CartographV3Client::registerKeys);
        NeoForge.EVENT_BUS.addListener(CartographV3Client::tick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        MapClientCache.ensureWorld(mc.level);
        MapClientCache.tick();
        WEB_UI.tick();

        if (mc.level == null) {
            qaOpened = false;
            return;
        }

        if (Boolean.getBoolean("cartograph.v3.qaAutoOpen") && !qaOpened) {
            qaOpened = true;
            String url = WEB_UI.openInBrowser();
            LOG.info("V3_WEBUI_QA_AUTO_OPEN url={}", url);
        }

        while (OPEN.consumeClick()) {
            String url = WEB_UI.openInBrowser();
            LOG.info("V3_WEBUI_OPEN url={}", url);
        }
    }
}
