package com.wanstu.cartographv3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Owns the one Minecraft backend instance shared by every frontend adapter.
 *
 * Map collection is world-scoped, not frontend-scoped: while a Minecraft world
 * is active, the backend stays open and continues collecting/caching map facts
 * even if no map frontend is currently visible. Frontend sessions only express
 * presentation lifetime.
 */
final class CartographBackendRuntime implements AutoCloseable {
    private final CartographBackendApi backend;
    private final Set<String> activeFrontends = new HashSet<>();
    private final LinkedHashMap<String, CartographBackendApi.Viewport> viewports = new LinkedHashMap<>();
    private boolean worldActive;
    private boolean backendOpen;

    CartographBackendRuntime(CartographBackendApi backend) {
        this.backend = backend;
    }

    CartographBackendApi api() {
        return backend;
    }

    synchronized void setWorldActive(boolean active) {
        if (worldActive == active) return;
        worldActive = active;
        reconcile();
    }

    synchronized void acquire(String frontendId) {
        if (!activeFrontends.add(frontendId)) return;
        reconcile();
    }

    synchronized void release(String frontendId) {
        if (!activeFrontends.remove(frontendId)) return;
        var current = viewports.lastEntry();
        viewports.remove(frontendId);
        if (current != null && current.getKey().equals(frontendId)) {
            var next = viewports.lastEntry();
            backend.updateViewport(next == null ? null : next.getValue(), false);
        }
        reconcile();
    }

    synchronized void updateViewport(String frontendId, CartographBackendApi.Viewport viewport, boolean force) {
        if (!activeFrontends.contains(frontendId)) return;
        viewports.putLast(frontendId, viewport);
        backend.updateViewport(viewport, force);
    }

    synchronized boolean active(String frontendId) {
        return activeFrontends.contains(frontendId);
    }

    void tick() {
        synchronized (this) {
            if (!backendOpen) return;
        }
        backend.tick();
    }

    private void reconcile() {
        boolean shouldOpen = worldActive || !activeFrontends.isEmpty();
        if (shouldOpen && !backendOpen) {
            backend.open();
            backendOpen = true;
        } else if (!shouldOpen && backendOpen) {
            backend.close();
            backendOpen = false;
        }
    }

    @Override
    public synchronized void close() {
        worldActive = false;
        activeFrontends.clear();
        viewports.clear();
        if (backendOpen) {
            backend.close();
            backendOpen = false;
        }
    }
}
