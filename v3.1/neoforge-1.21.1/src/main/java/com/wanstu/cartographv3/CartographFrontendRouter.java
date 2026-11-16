package com.wanstu.cartographv3;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves the backend-configured frontend for the generic "open map" intent.
 *
 * The key binding never knows about browser or Minecraft Screen implementations.
 */
final class CartographFrontendRouter implements AutoCloseable {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.frontend");

    private final Map<CartographFrontendTarget, CartographFrontend> frontends =
            new EnumMap<>(CartographFrontendTarget.class);

    CartographFrontendRouter(CartographFrontend... adapters) {
        for (CartographFrontend adapter : adapters) {
            frontends.put(adapter.target(), adapter);
        }
    }

    CartographFrontend.OpenResult openConfigured() {
        return open(CartographSettings.frontendTarget());
    }

    CartographFrontend.OpenResult open(CartographFrontendTarget requested) {
        CartographFrontend frontend = frontends.get(requested);
        if (frontend == null) {
            CartographFrontend fallback = frontends.get(CartographFrontendTarget.WEB);
            if (fallback == null) {
                throw new IllegalStateException(
                        "No Cartograph frontend registered for " + requested.id());
            }
            LOG.warn(
                    "Configured frontend '{}' is not registered in this build; using '{}'",
                    requested.id(),
                    fallback.target().id());
            frontend = fallback;
        }
        return frontend.open();
    }

    void tick() {
        for (CartographFrontend frontend : frontends.values()) {
            frontend.tick();
        }
    }

    @Override
    public void close() {
        for (CartographFrontend frontend : frontends.values()) {
            frontend.close();
        }
    }
}
