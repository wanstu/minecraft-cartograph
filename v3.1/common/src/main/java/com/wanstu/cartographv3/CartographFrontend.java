package com.wanstu.cartographv3;

/**
 * Presentation adapter. Frontends render and collect UI input only; game facts
 * and semantic actions stay behind CartographBackendApi.
 */
interface CartographFrontend extends AutoCloseable {
    record OpenResult(CartographFrontendTarget target, String detail) {}

    CartographFrontendTarget target();

    OpenResult open();

    void tick();

    @Override
    void close();
}
