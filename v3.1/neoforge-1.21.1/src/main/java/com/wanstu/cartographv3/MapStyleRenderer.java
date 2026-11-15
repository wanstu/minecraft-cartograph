package com.wanstu.cartographv3;

/**
 * Loader-neutral frontend map style.
 *
 * The backend owns only immutable world facts. A style turns those facts into
 * pixels for one visual presentation. Rendered pixels are disposable derived
 * data and must never become the authoritative map cache.
 */
interface MapStyleRenderer {
    String id();

    String displayName();

    /**
     * Signature for resources/configuration that affect this style. Changing
     * it invalidates only this style's derived texture cache, never world facts.
     */
    long signature();

    int[] renderTile(SurfaceTile facts);
}
