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
    private static final KeyMapping OPEN = new KeyMapping(
            "key.cartograph_v3.open", GLFW.GLFW_KEY_M, "key.categories.cartograph_v3");

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
        while (OPEN.consumeClick()) {
            if (mc.screen instanceof MapScreenV3) {
                mc.setScreen(null);
            } else if (mc.level != null) {
                mc.setScreen(new MapScreenV3());
            }
        }
    }
}
