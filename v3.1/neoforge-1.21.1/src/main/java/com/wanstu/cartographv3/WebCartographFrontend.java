package com.wanstu.cartographv3;

/** Browser/WebUI presentation adapter. */
final class WebCartographFrontend implements CartographFrontend {
    private final CartographWebUiServer server;

    WebCartographFrontend(CartographBackendRuntime backendRuntime) {
        this.server = new CartographWebUiServer(backendRuntime);
    }

    @Override
    public CartographFrontendTarget target() {
        return CartographFrontendTarget.WEB;
    }

    @Override
    public OpenResult open() {
        return new OpenResult(target(), server.openInBrowser());
    }

    @Override
    public void tick() {
        server.tick();
    }

    @Override
    public void close() {
        server.close();
    }
}
