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

@Mod(value = CartographV3Mod.MOD_ID, dist = Dist.CLIENT)
public final class CartographV3Client {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.client.qa");
    private static boolean qaOpened;
    private static net.minecraft.client.multiplayer.ClientLevel qaWorld;
    private static int qaTicks;
    private static final KeyMapping OPEN = new KeyMapping(
            "key.cartograph_v3.open", GLFW.GLFW_KEY_M, "key.categories.cartograph_v3");

    public CartographV3Client(IEventBus modBus) {
        modBus.addListener(CartographV3Client::registerKeys);
        NeoForge.EVENT_BUS.addListener(CartographV3Client::tick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    static boolean matchesOpenKey(int keyCode, int scanCode) {
        return OPEN.matches(keyCode, scanCode);
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        MapClientCache.ensureWorld(mc.level);
        if (mc.level == null) {
            qaOpened = false;
            qaWorld = null;
            qaTicks = 0;
            return;
        }
        if (qaWorld != mc.level) {
            qaWorld = mc.level;
            qaOpened = false;
            qaTicks = 0;
        }
        qaTicks++;
        if (Boolean.getBoolean("cartograph.v3.qaAutoOpen") && !qaOpened) {
            qaOpened = true;
            mc.setScreen(new MapScreenV3());
            LOG.info("V3_QA_AUTO_OPEN tick={}", qaTicks);
        }
        if (Boolean.getBoolean("cartograph.v3.qaReopen")) {
            int closeTick = Integer.getInteger("cartograph.v3.qaCloseTick", 100);
            int reopenTick = Integer.getInteger("cartograph.v3.qaReopenTick", 220);
            if (qaTicks == closeTick && mc.screen instanceof MapScreenV3) {
                mc.setScreen(null);
                LOG.info("V3_QA_AUTO_CLOSE tick={}", qaTicks);
            } else if (qaTicks == reopenTick && !(mc.screen instanceof MapScreenV3)) {
                mc.setScreen(new MapScreenV3());
                LOG.info("V3_QA_AUTO_REOPEN tick={}", qaTicks);
            }
        }
        while (OPEN.consumeClick()) {
            if (mc.screen instanceof MapScreenV3) {
                mc.setScreen(null);
            } else if (mc.level != null) {
                mc.setScreen(new MapScreenV3());
            }
        }
    }
}
