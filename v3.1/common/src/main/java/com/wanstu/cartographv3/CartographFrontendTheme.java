package com.wanstu.cartographv3;

import java.util.Locale;

/** Shared frontend theme identity used by both in-game settings and WebUI. */
enum CartographFrontendTheme {
    SATELLITE("satellite", "原生 · 卫星"),
    MODERN("modern", "手绘 · 现代"),
    ANCIENT("ancient", "手绘 · 古式");

    private final String id;
    private final String label;

    CartographFrontendTheme(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    CartographFrontendTheme next() {
        CartographFrontendTheme[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    static CartographFrontendTheme parse(String raw) {
        if (raw == null) return SATELLITE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CartographFrontendTheme value : values()) {
            if (value.id.equals(normalized)) return value;
        }
        return SATELLITE;
    }
}
