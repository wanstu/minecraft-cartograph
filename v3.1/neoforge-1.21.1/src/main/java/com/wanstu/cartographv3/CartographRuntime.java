package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

/**
 * Client-side composition root. Owns backend services and frontend adapters.
 */
final class CartographRuntime implements AutoCloseable {
    private final CartographBackendRuntime backend =
            new CartographBackendRuntime(new MinecraftCartographBackend());
    private final CartographFrontendRouter frontends =
            new CartographFrontendRouter(new WebCartographFrontend(backend));

    void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        MapClientCache.ensureWorld(minecraft.level);
        MapClientCache.tick();
        frontends.tick();
        backend.tick();
    }

    CartographFrontend.OpenResult openConfiguredFrontend() {
        return frontends.openConfigured();
    }

    CartographFrontend.OpenResult openFrontend(CartographFrontendTarget target) {
        return frontends.open(target);
    }

    @Override
    public void close() {
        frontends.close();
        backend.close();
    }
}
