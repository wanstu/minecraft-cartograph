package com.wanstu.cartographv3;

import net.ethrocky.pane.core.Component;
import net.ethrocky.pane.core.style.Theme;

/**
 * A complete Cartograph frontend theme.
 *
 * A theme owns the entire large-map presentation: map renderer, layout,
 * controls, interaction model, icons and dialogs. The backend API is shared.
 */
interface CartographThemeFrontend extends AutoCloseable {
    String id();

    String displayName();

    Component root();

    Theme paneTheme();

    void tick();

    @Override
    void close();
}
