package com.wanstu.cartograph;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class ClientMapSettings {
    enum Style {
        PIXEL("像素 · Minecraft"), REALISTIC("写实 · 现代地形图"), ILLUSTRATED("手绘 · 古式地图"), ORIGINAL("上一版");
        final String label;
        Style(String label) { this.label = label; }
    }

    static final int ROADS = 0, RAILS = 1, STRUCTURES = 2, WAYPOINTS = 3, REGIONS = 4, BOUNDARIES = 5, CONTOURS = 6;
    static final int ENABLED = 0, CARDINALS = 1, NORTH_UP = 2, COORDS = 3, REGION_NAMES = 4, ICONS = 5;
    private static ClientMapSettings instance;

    Style style = Style.PIXEL;
    int layers = 31 | 1 << CONTOURS;
    // Compact default: map + landmarks + compass arrow; extra overlays opt-in.
    int hudFlags = (1 << ENABLED) | (1 << ICONS);
    boolean round = true;
    double hudX = .96, hudY = .06, hudZoom = 1.5;
    int hudSize = 130;

    static ClientMapSettings get() {
        if (instance == null) instance = load();
        return instance;
    }

    boolean layer(int index) { return (layers & 1 << index) != 0; }
    boolean hud(int index) { return (hudFlags & 1 << index) != 0; }

    void toggleLayer(int index) {
        layers ^= 1 << index;
        if (index == ROADS || index == RAILS || index == CONTOURS) CartographClient.redrawMaps();
        save();
    }

    void toggleHud(int index) { hudFlags ^= 1 << index; save(); }

    void resetHud() {
        hudFlags = (1 << ENABLED) | (1 << ICONS);
        round = true;
        hudSize = 130;
        hudX = .96; hudY = .06; hudZoom = 1.5;
        save();
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/cartograph-client-map.properties");
    }

    private static ClientMapSettings load() {
        ClientMapSettings out = new ClientMapSettings();
        Properties p = new Properties();
        if (Files.isRegularFile(file())) {
            try (var in = Files.newInputStream(file())) { p.load(in); }
            catch (IOException | IllegalArgumentException ignored) { return out; }
        }
        try {
            Style candidate = Style.valueOf(p.getProperty("style", Style.PIXEL.name()));
            if (candidate != Style.ORIGINAL) out.style = candidate;
        } catch (IllegalArgumentException ignored) { }
        out.layers = (int) value(p, "layers", out.layers, 0, 127);
        out.hudFlags = (int) value(p, "hudFlags", out.hudFlags, 0, 63);
        out.round = Boolean.parseBoolean(p.getProperty("round", "true"));
        out.hudX = value(p, "hudX", out.hudX, 0, 1);
        out.hudY = value(p, "hudY", out.hudY, 0, 1);
        out.hudZoom = value(p, "hudZoom", out.hudZoom, .5, 4);
        out.hudSize = (int) value(p, "hudSize", out.hudSize, 80, 360);
        return out;
    }

    private static double value(Properties p, String key, double fallback, double min, double max) {
        try {
            double n = Double.parseDouble(p.getProperty(key, Double.toString(fallback)));
            return Double.isFinite(n) ? Math.clamp(n, min, max) : fallback;
        } catch (NumberFormatException invalid) { return fallback; }
    }

    void save() {
        Path dest = file(), tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Properties p = new Properties();
        p.setProperty("style", style.name());
        p.setProperty("layers", Integer.toString(layers));
        p.setProperty("hudFlags", Integer.toString(hudFlags));
        p.setProperty("round", Boolean.toString(round));
        p.setProperty("hudX", Double.toString(hudX)); p.setProperty("hudY", Double.toString(hudY));
        p.setProperty("hudZoom", Double.toString(hudZoom)); p.setProperty("hudSize", Integer.toString(hudSize));
        try {
            Files.createDirectories(dest.getParent());
            try (var out = Files.newOutputStream(tmp)) { p.store(out, "Cartograph map settings"); }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger("cartograph.client").warn("Could not save map preferences", failure);
        }
    }
}
