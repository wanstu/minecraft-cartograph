package com.wanstu.cartographv3;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry. The shortcut emits a generic "open map frontend" intent.
 * Which frontend handles it is resolved by the backend-controlled router.
 */
final class CartographClient {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.client");
    private static final CartographRuntime RUNTIME = new CartographRuntime();
    private static boolean qaOpened;

    static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.cartograph_v3.open",
            GLFW.GLFW_KEY_M,
            "key.categories.cartograph_v3");

    private CartographClient() {}

    static void tick(Minecraft mc) {
        RUNTIME.tick();

        if (mc.level == null) {
            qaOpened = false;
            return;
        }

        if (Boolean.getBoolean("cartograph.v3.qaAutoOpen") && !qaOpened) {
            qaOpened = true;
            CartographFrontend.OpenResult opened =
                    RUNTIME.openFrontend(CartographFrontendTarget.WEB);
            LOG.info(
                    "V3_WEBUI_QA_AUTO_OPEN target={} detail={}",
                    opened.target().id(),
                    opened.detail());
        }

        while (OPEN_KEY.consumeClick()) {
            CartographFrontend.OpenResult opened = RUNTIME.openConfiguredFrontend();
            LOG.info(
                    "V3_FRONTEND_OPEN target={} detail={}",
                    opened.target().id(),
                    opened.detail());
        }
    }
}
