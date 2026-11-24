package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

/** An opaque fullscreen map does not need the 3D scene underneath it. */
public final class MapWorldRenderPolicy {
    private static long worldFrames;
    private static long skippedWorldFrames;

    private MapWorldRenderPolicy() {}

    public static boolean shouldRenderWorld(boolean requested) {
        Minecraft mc = Minecraft.getInstance();
        if (!requested || mc.level == null) return requested;
        if (MapClientAccess.screen(mc) instanceof InGameMapScreen && !MapClientAccess.hasOverlay(mc)
                && (MapPresentationRenderer.palette().background() >>> 24) == 255) {
            skippedWorldFrames++;
            return false;
        }
        worldFrames++;
        return true;
    }
}
