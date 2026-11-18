package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

final class InGameCartographFrontend implements CartographFrontend {
    private final CartographBackendRuntime runtime;
    private final Runnable openWeb;
    private InGameMapPreferences preferences;
    private InGameMapScreen screen;
    private InGameMapTiles tiles;
    private ClientLevel level;
    private InGameMapCamera.Position lastPosition;

    InGameCartographFrontend(CartographBackendRuntime runtime, Runnable openWeb) {
        this.runtime = runtime;
        this.openWeb = openWeb;
    }

    @Override public CartographFrontendTarget target() { return CartographFrontendTarget.IN_GAME; }

    @Override
    public OpenResult open() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return new OpenResult(target(), "no world");
        if (screen != null && client.screen == screen) return new OpenResult(target(), "already open");

        if (level != client.level) {
            close();
            level = client.level;
            lastPosition = null;
        }
        if (preferences == null) preferences = new InGameMapPreferences();
        SatelliteSurfaceRenderer.beginMapSession();
        if (tiles == null) tiles = new InGameMapTiles(runtime.api());
        InGameMapScreen next = new InGameMapScreen(runtime.api(), preferences, tiles, lastPosition,
                viewport -> runtime.updateViewport(target().id(), viewport, false),
                this::closedScreen, openWeb);
        runtime.acquire(target().id());
        screen = next;
        try {
            client.setScreen(next);
        } catch (RuntimeException failure) {
            next.removed();
            throw failure;
        }
        return new OpenResult(target(), "Minecraft Screen");
    }

    private void closedScreen(InGameMapCamera.Position position) {
        lastPosition = position;
        screen = null;
        runtime.release(target().id());
    }

    @Override
    public void tick() {
        if (level == Minecraft.getInstance().level) return;
        close();
        level = null;
        lastPosition = null;
    }

    @Override
    public void close() {
        if (screen != null && Minecraft.getInstance().screen == screen) {
            Minecraft.getInstance().setScreen(null);
        }
        if (screen != null) screen.removed();
        if (tiles != null) {
            tiles.close();
            tiles = null;
        }
    }
}
