package com.wanstu.cartographv3;

import java.util.Locale;

/**
 * The map representation model. This is intentionally closed: Cartograph has
 * exactly two representation modes, satellite and hand-drawn.
 */
enum MapDrawingMode {
    SATELLITE("satellite", "卫星"),
    HAND_DRAWN("hand_drawn", "手绘");

    private final String id;
    private final String label;

    MapDrawingMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    MapDrawingMode next() {
        return this == SATELLITE ? HAND_DRAWN : SATELLITE;
    }

    static MapDrawingMode parse(String raw) {
        if (raw == null) return SATELLITE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MapDrawingMode value : values()) {
            if (value.id.equals(normalized)) return value;
        }
        return SATELLITE;
    }
}
