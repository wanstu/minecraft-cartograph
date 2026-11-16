package com.wanstu.cartographv3;

import java.util.Locale;

/** Backend-controlled frontend destination for the map-open intent. */
enum CartographFrontendTarget {
    WEB("web"),
    IN_GAME("in_game");

    private final String id;

    CartographFrontendTarget(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static CartographFrontendTarget parse(String raw) {
        if (raw == null) return WEB;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CartographFrontendTarget value : values()) {
            if (value.id.equals(normalized)) return value;
        }
        return WEB;
    }
}
