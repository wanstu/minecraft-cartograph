package com.wanstu.cartographv3;

import java.util.HashSet;
import java.util.Set;

/**
 * Owns the one Minecraft backend instance shared by every frontend adapter.
 *
 * Frontends acquire/release named sessions. The backend map session is opened
 * by the first active frontend and closed only after the last frontend leaves.
 */
final class CartographBackendRuntime implements AutoCloseable {
    private final CartographBackendApi backend;
    private final Set<String> activeFrontends = new HashSet<>();

    CartographBackendRuntime(CartographBackendApi backend) {
        this.backend = backend;
    }

    CartographBackendApi api() {
        return backend;
    }

    synchronized void acquire(String frontendId) {
        if (!activeFrontends.add(frontendId)) return;
        if (activeFrontends.size() == 1) backend.open();
    }

    synchronized void release(String frontendId) {
        if (!activeFrontends.remove(frontendId)) return;
        if (activeFrontends.isEmpty()) backend.close();
    }

    synchronized boolean active(String frontendId) {
        return activeFrontends.contains(frontendId);
    }

    void tick() {
        synchronized (this) {
            if (activeFrontends.isEmpty()) return;
        }
        backend.tick();
    }

    @Override
    public synchronized void close() {
        activeFrontends.clear();
        backend.close();
    }
}
