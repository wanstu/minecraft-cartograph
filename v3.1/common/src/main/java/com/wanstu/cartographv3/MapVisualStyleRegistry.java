package com.wanstu.cartographv3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extensible visual-style registry. DrawingMode stays closed while this registry
 * may gain additional styles over time.
 */
final class MapVisualStyleRegistry {
    record Style(
            String id,
            String label,
            String layout,
            String iconFamily,
            String density,
            String typography,
            MapVisualStyleProcessor processor) {}

    private static final Map<String, Style> STYLES = new LinkedHashMap<>();

    static {
        register(new Style(
                "modern", "现代", "modern-atlas", "modern", "comfortable", "sans",
                MapVisualStyleProcessors.MODERN));
        register(new Style(
                "ancient", "古式", "ancient-atlas", "engraved", "comfortable", "serif",
                MapVisualStyleProcessors.ANCIENT));
        register(new Style(
                "anime", "动漫", "modern-atlas", "modern", "comfortable", "sans",
                MapVisualStyleProcessors.ANIME));
    }

    private MapVisualStyleRegistry() {}

    static Style defaultStyle() {
        return STYLES.get("modern");
    }

    static Style find(String id) {
        if (id == null) return defaultStyle();
        return STYLES.getOrDefault(id.trim().toLowerCase(), defaultStyle());
    }

    static List<Style> styles() {
        return List.copyOf(STYLES.values());
    }

    static Style next(String currentId) {
        List<Style> styles = styles();
        Style current = find(currentId);
        int index = styles.indexOf(current);
        return styles.get((index + 1) % styles.size());
    }

    private static void register(Style style) {
        if (STYLES.putIfAbsent(style.id(), style) != null) {
            throw new IllegalStateException("Duplicate map visual style: " + style.id());
        }
    }
}
