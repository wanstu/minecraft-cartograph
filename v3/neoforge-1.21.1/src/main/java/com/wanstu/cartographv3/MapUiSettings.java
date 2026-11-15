package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Properties;

final class MapUiSettings {
    enum MarkerType { ARROW, CROSS, DOT }
    private static final int SCHEMA_VERSION = 4;

    private static final int DEFAULT_PRIMARY_COLOR = MapMarkerStore.DEFAULT_PRIMARY_COLOR;
    private static final int DEFAULT_COLOR = 0xe53935;
    private static final int DEFAULT_LINE_COLOR = MapMarkerStore.DEFAULT_LINE_COLOR;
    private static final int DEFAULT_SIZE = 12;
    private static final int DEFAULT_REQUEST_BATCH = 64;
    private static final int DEFAULT_LIVE_SCAN_INTERVAL = 10;
    private static final int DEFAULT_MARKER_SCALE = 35;
    private static final int DEFAULT_CUSTOM_MARKER_SIZE = 22;
    private static final int DEFAULT_CUSTOM_MARKER_COLOR = 0xffd34e;

    private static boolean loaded;
    private static MarkerType markerType = MarkerType.ARROW;
    private static int markerPrimaryColor = DEFAULT_PRIMARY_COLOR;
    private static int markerColor = DEFAULT_COLOR;
    private static int markerLineColor = DEFAULT_LINE_COLOR;
    private static int markerSize = DEFAULT_SIZE;
    private static boolean markerLayerEnabled = true;
    private static boolean structureLayerEnabled = true;
    private static boolean showCoords = true;
    private static boolean playerRing = true;
    private static boolean labels = true;
    private static boolean grid = false;
    private static boolean dragMap = true;
    private static boolean hideLabelsLowZoom = true;
    private static boolean avoidLabels = true;
    private static int markerScalePercent = DEFAULT_MARKER_SCALE;
    private static MapMarkerStore.Icon customMarkerDefaultIcon = MapMarkerStore.Icon.PIN;
    private static int customMarkerDefaultColor = DEFAULT_CUSTOM_MARKER_COLOR;
    private static int customMarkerDefaultSize = DEFAULT_CUSTOM_MARKER_SIZE;
    private static int requestBatchSize = DEFAULT_REQUEST_BATCH;
    private static int liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;
    private static final EnumMap<MapMarkerStore.StructurePreset, MapMarkerStore.Icon>
            STRUCTURE_PRESET_ICONS = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private static final EnumMap<MapMarkerStore.StructurePreset, Integer>
            STRUCTURE_PRESET_PRIMARY = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private static final EnumMap<MapMarkerStore.StructurePreset, Integer>
            STRUCTURE_PRESET_ACCENT = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private static final EnumMap<MapMarkerStore.StructurePreset, Integer>
            STRUCTURE_PRESET_LINE = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private static final EnumMap<MapMarkerStore.StructurePreset, Boolean>
            STRUCTURE_VISIBLE = new EnumMap<>(MapMarkerStore.StructurePreset.class);

    private MapUiSettings() {}

    static synchronized MarkerType markerType() {
        ensureLoaded();
        return markerType;
    }

    static synchronized int markerPrimaryColor() {
        ensureLoaded();
        return markerPrimaryColor;
    }

    static synchronized int markerColor() {
        ensureLoaded();
        return markerColor;
    }

    static synchronized int markerLineColor() {
        ensureLoaded();
        return markerLineColor;
    }

    static synchronized int markerSize() {
        ensureLoaded();
        return markerSize;
    }

    static synchronized boolean markerLayerEnabled() {
        ensureLoaded();
        return markerLayerEnabled;
    }

    static synchronized boolean structureLayerEnabled() {
        ensureLoaded();
        return structureLayerEnabled;
    }

    static synchronized boolean showCoords() { ensureLoaded(); return showCoords; }
    static synchronized boolean playerRing() { ensureLoaded(); return playerRing; }
    static synchronized boolean labels() { ensureLoaded(); return labels; }
    static synchronized boolean grid() { ensureLoaded(); return grid; }
    static synchronized boolean dragMap() { ensureLoaded(); return dragMap; }
    static synchronized boolean hideLabelsLowZoom() { ensureLoaded(); return hideLabelsLowZoom; }
    static synchronized boolean avoidLabels() { ensureLoaded(); return avoidLabels; }
    static synchronized int markerScalePercent() { ensureLoaded(); return markerScalePercent; }
    static synchronized MapMarkerStore.Icon customMarkerDefaultIcon() {
        ensureLoaded(); return customMarkerDefaultIcon;
    }
    static synchronized int customMarkerDefaultColor() {
        ensureLoaded(); return customMarkerDefaultColor;
    }
    static synchronized int customMarkerDefaultSize() {
        ensureLoaded(); return customMarkerDefaultSize;
    }
    static synchronized boolean structureVisible(MapMarkerStore.StructurePreset preset) {
        ensureLoaded();
        return preset == null || STRUCTURE_VISIBLE.getOrDefault(preset, true);
    }

    static synchronized int requestBatchSize() {
        ensureLoaded();
        return requestBatchSize;
    }

    static synchronized int liveScanIntervalTicks() {
        ensureLoaded();
        return liveScanIntervalTicks;
    }


    static synchronized MapMarkerStore.Icon structurePresetIcon(
            MapMarkerStore.StructurePreset preset) {
        ensureLoaded();
        if (preset == null) return MapMarkerStore.Icon.STAR;
        return STRUCTURE_PRESET_ICONS.getOrDefault(preset, preset.defaultIcon);
    }


    static synchronized int structurePresetPrimaryColor(
            MapMarkerStore.StructurePreset preset) {
        ensureLoaded();
        if (preset == null) return MapMarkerStore.DEFAULT_PRIMARY_COLOR;
        return STRUCTURE_PRESET_PRIMARY.getOrDefault(preset, preset.defaultPrimary);
    }

    static synchronized int structurePresetAccentColor(
            MapMarkerStore.StructurePreset preset) {
        ensureLoaded();
        if (preset == null) return MapMarkerStore.DEFAULT_ACCENT_COLOR;
        return STRUCTURE_PRESET_ACCENT.getOrDefault(preset, preset.defaultAccent);
    }

    static synchronized int structurePresetLineColor(
            MapMarkerStore.StructurePreset preset) {
        ensureLoaded();
        if (preset == null) return MapMarkerStore.DEFAULT_LINE_COLOR;
        return STRUCTURE_PRESET_LINE.getOrDefault(preset, preset.defaultLine);
    }

    static synchronized int structurePresetColor(MapMarkerStore.StructurePreset preset) {
        return structurePresetAccentColor(preset);
    }

    static synchronized void setMarkerType(MarkerType next) {
        ensureLoaded();
        markerType = next == null ? MarkerType.ARROW : next;
        save();
    }

    static synchronized void setMarkerPrimaryColor(int rgb) {
        ensureLoaded();
        markerPrimaryColor = rgb & 0x00ffffff;
        save();
    }

    static synchronized void setMarkerColor(int rgb) {
        ensureLoaded();
        markerColor = rgb & 0x00ffffff;
        save();
    }

    static synchronized void setMarkerLineColor(int rgb) {
        ensureLoaded();
        markerLineColor = rgb & 0x00ffffff;
        save();
    }

    static synchronized void setMarkerSize(int size) {
        ensureLoaded();
        markerSize = Math.max(6, Math.min(48, size));
        save();
    }

    static synchronized void setMarkerLayerEnabled(boolean enabled) {
        ensureLoaded();
        markerLayerEnabled = enabled;
        save();
    }

    static synchronized void setStructureLayerEnabled(boolean enabled) {
        ensureLoaded();
        structureLayerEnabled = enabled;
        save();
    }

    static synchronized void setShowCoords(boolean value) { ensureLoaded(); showCoords = value; save(); }
    static synchronized void setPlayerRing(boolean value) { ensureLoaded(); playerRing = value; save(); }
    static synchronized void setLabels(boolean value) { ensureLoaded(); labels = value; save(); }
    static synchronized void setGrid(boolean value) { ensureLoaded(); grid = value; save(); }
    static synchronized void setDragMap(boolean value) { ensureLoaded(); dragMap = value; save(); }
    static synchronized void setHideLabelsLowZoom(boolean value) { ensureLoaded(); hideLabelsLowZoom = value; save(); }
    static synchronized void setAvoidLabels(boolean value) { ensureLoaded(); avoidLabels = value; save(); }
    static synchronized void setMarkerScalePercent(int value) {
        ensureLoaded(); markerScalePercent = Math.max(0, Math.min(100, value)); save();
    }
    static synchronized void setCustomMarkerDefaultIcon(MapMarkerStore.Icon icon) {
        ensureLoaded(); if (icon != null) customMarkerDefaultIcon = icon; save();
    }
    static synchronized void setCustomMarkerDefaultColor(int rgb) {
        ensureLoaded(); customMarkerDefaultColor = rgb & 0x00ffffff; save();
    }
    static synchronized void setCustomMarkerDefaultSize(int value) {
        ensureLoaded(); customMarkerDefaultSize = Math.max(8, Math.min(32, value)); save();
    }
    static synchronized void setStructureVisible(
            MapMarkerStore.StructurePreset preset, boolean visible) {
        ensureLoaded(); if (preset == null) return;
        STRUCTURE_VISIBLE.put(preset, visible); save();
    }
    static synchronized void setPlayerColor(int rgb) {
        ensureLoaded();
        int color = rgb & 0x00ffffff;
        markerPrimaryColor = color;
        markerColor = color;
        markerLineColor = color;
        save();
    }

    static synchronized void setRequestBatchSize(int value) {
        ensureLoaded();
        requestBatchSize = Math.max(8, Math.min(256, value));
        save();
    }

    static synchronized void setLiveScanIntervalTicks(int value) {
        ensureLoaded();
        liveScanIntervalTicks = Math.max(5, Math.min(100, value));
        save();
    }


    static synchronized void setStructurePresetIcon(
            MapMarkerStore.StructurePreset preset,
            MapMarkerStore.Icon icon) {
        ensureLoaded();
        if (preset == null || icon == null) return;
        STRUCTURE_PRESET_ICONS.put(preset, icon);
        save();
    }


    static synchronized void setStructurePresetPrimaryColor(
            MapMarkerStore.StructurePreset preset, int rgb) {
        ensureLoaded();
        if (preset == null) return;
        STRUCTURE_PRESET_PRIMARY.put(preset, rgb & 0x00ffffff);
        save();
    }

    static synchronized void setStructurePresetAccentColor(
            MapMarkerStore.StructurePreset preset, int rgb) {
        ensureLoaded();
        if (preset == null) return;
        STRUCTURE_PRESET_ACCENT.put(preset, rgb & 0x00ffffff);
        save();
    }

    static synchronized void setStructurePresetLineColor(
            MapMarkerStore.StructurePreset preset, int rgb) {
        ensureLoaded();
        if (preset == null) return;
        STRUCTURE_PRESET_LINE.put(preset, rgb & 0x00ffffff);
        save();
    }

    static synchronized void setStructurePresetColor(
            MapMarkerStore.StructurePreset preset, int rgb) {
        ensureLoaded();
        if (preset == null) return;
        int color = rgb & 0x00ffffff;
        STRUCTURE_PRESET_PRIMARY.put(preset, color);
        STRUCTURE_PRESET_ACCENT.put(preset, color);
        STRUCTURE_PRESET_LINE.put(preset, color);
        save();
    }

    static synchronized void resetStructurePresets() {
        ensureLoaded();
        STRUCTURE_PRESET_ICONS.clear();
        STRUCTURE_PRESET_PRIMARY.clear();
        STRUCTURE_PRESET_ACCENT.clear();
        STRUCTURE_PRESET_LINE.clear();
        save();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) return;

        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            props.load(input);
            int schema;
            try {
                schema = Integer.parseInt(props.getProperty("schemaVersion", "-1"));
            } catch (NumberFormatException invalid) {
                return;
            }
            if (schema != SCHEMA_VERSION) return;
            try {
                markerType = MarkerType.valueOf(
                        props.getProperty("markerType", MarkerType.ARROW.name()));
            } catch (IllegalArgumentException ignored) {
                markerType = MarkerType.ARROW;
            }
            try {
                markerPrimaryColor = Integer.parseInt(
                        props.getProperty(
                                "markerPrimaryColor",
                                Integer.toString(DEFAULT_PRIMARY_COLOR)))
                        & 0x00ffffff;
            } catch (NumberFormatException ignored) {
                markerPrimaryColor = DEFAULT_PRIMARY_COLOR;
            }
            try {
                markerColor = Integer.parseInt(
                        props.getProperty("markerColor", Integer.toString(DEFAULT_COLOR)))
                        & 0x00ffffff;
            } catch (NumberFormatException ignored) {
                markerColor = DEFAULT_COLOR;
            }
            try {
                markerLineColor = Integer.parseInt(
                        props.getProperty(
                                "markerLineColor",
                                Integer.toString(DEFAULT_LINE_COLOR)))
                        & 0x00ffffff;
            } catch (NumberFormatException ignored) {
                markerLineColor = DEFAULT_LINE_COLOR;
            }
            try {
                markerSize = Math.max(6, Math.min(48,
                        Integer.parseInt(props.getProperty(
                                "markerSize", Integer.toString(DEFAULT_SIZE)))));
            } catch (NumberFormatException ignored) {
                markerSize = DEFAULT_SIZE;
            }
            markerLayerEnabled = Boolean.parseBoolean(
                    props.getProperty("markerLayerEnabled", "true"));
            structureLayerEnabled = Boolean.parseBoolean(
                    props.getProperty("structureLayerEnabled", "true"));
            showCoords = Boolean.parseBoolean(props.getProperty("showCoords", "true"));
            playerRing = Boolean.parseBoolean(props.getProperty("playerRing", "true"));
            labels = Boolean.parseBoolean(props.getProperty("labels", "true"));
            grid = Boolean.parseBoolean(props.getProperty("grid", "false"));
            dragMap = Boolean.parseBoolean(props.getProperty("dragMap", "true"));
            hideLabelsLowZoom = Boolean.parseBoolean(
                    props.getProperty("hideLabelsLowZoom", "true"));
            avoidLabels = Boolean.parseBoolean(props.getProperty("avoidLabels", "true"));
            try {
                markerScalePercent = Math.max(0, Math.min(100,
                        Integer.parseInt(props.getProperty(
                                "markerScalePercent", Integer.toString(DEFAULT_MARKER_SCALE)))));
            } catch (NumberFormatException ignored) {
                markerScalePercent = DEFAULT_MARKER_SCALE;
            }
            try {
                customMarkerDefaultIcon = MapMarkerStore.Icon.valueOf(
                        props.getProperty("customMarkerDefaultIcon", MapMarkerStore.Icon.PIN.name()));
            } catch (IllegalArgumentException ignored) {
                customMarkerDefaultIcon = MapMarkerStore.Icon.PIN;
            }
            try {
                customMarkerDefaultColor = Integer.parseInt(props.getProperty(
                        "customMarkerDefaultColor", Integer.toString(DEFAULT_CUSTOM_MARKER_COLOR)))
                        & 0x00ffffff;
            } catch (NumberFormatException ignored) {
                customMarkerDefaultColor = DEFAULT_CUSTOM_MARKER_COLOR;
            }
            try {
                customMarkerDefaultSize = Math.max(8, Math.min(32,
                        Integer.parseInt(props.getProperty(
                                "customMarkerDefaultSize",
                                Integer.toString(DEFAULT_CUSTOM_MARKER_SIZE)))));
            } catch (NumberFormatException ignored) {
                customMarkerDefaultSize = DEFAULT_CUSTOM_MARKER_SIZE;
            }
            try {
                requestBatchSize = Math.max(8, Math.min(256,
                        Integer.parseInt(props.getProperty(
                                "requestBatchSize", Integer.toString(DEFAULT_REQUEST_BATCH)))));
            } catch (NumberFormatException ignored) {
                requestBatchSize = DEFAULT_REQUEST_BATCH;
            }
            try {
                liveScanIntervalTicks = Math.max(5, Math.min(100,
                        Integer.parseInt(props.getProperty(
                                "liveScanIntervalTicks", Integer.toString(DEFAULT_LIVE_SCAN_INTERVAL)))));
            } catch (NumberFormatException ignored) {
                liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;
            }
            for (MapMarkerStore.StructurePreset preset
                    : MapMarkerStore.StructurePreset.values()) {
                String prefix = "structurePreset." + preset.name();
                String rawIcon = props.getProperty(prefix + ".icon");
                if (rawIcon != null && !rawIcon.isBlank()) {
                    try {
                        STRUCTURE_PRESET_ICONS.put(
                                preset, MapMarkerStore.Icon.valueOf(rawIcon));
                    } catch (IllegalArgumentException ignored) {}
                }
                loadPresetColor(props, prefix + ".primary",
                        STRUCTURE_PRESET_PRIMARY, preset);
                loadPresetColor(props, prefix + ".accent",
                        STRUCTURE_PRESET_ACCENT, preset);
                loadPresetColor(props, prefix + ".line",
                        STRUCTURE_PRESET_LINE, preset);
                STRUCTURE_VISIBLE.put(
                        preset,
                        Boolean.parseBoolean(props.getProperty(prefix + ".visible", "true")));
            }
        } catch (IOException ignored) {}
    }

    private static void loadPresetColor(
            Properties props, String key,
            EnumMap<MapMarkerStore.StructurePreset, Integer> target,
            MapMarkerStore.StructurePreset preset) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return;
        try {
            target.put(preset, Integer.parseInt(raw) & 0x00ffffff);
        } catch (NumberFormatException ignored) {}
    }

    private static void save() {
        Properties props = new Properties();
        props.setProperty("schemaVersion", Integer.toString(SCHEMA_VERSION));
        props.setProperty("markerType", markerType.name());
        props.setProperty("markerPrimaryColor", Integer.toString(markerPrimaryColor));
        props.setProperty("markerColor", Integer.toString(markerColor));
        props.setProperty("markerLineColor", Integer.toString(markerLineColor));
        props.setProperty("markerSize", Integer.toString(markerSize));
        props.setProperty("markerLayerEnabled", Boolean.toString(markerLayerEnabled));
        props.setProperty("structureLayerEnabled", Boolean.toString(structureLayerEnabled));
        props.setProperty("showCoords", Boolean.toString(showCoords));
        props.setProperty("playerRing", Boolean.toString(playerRing));
        props.setProperty("labels", Boolean.toString(labels));
        props.setProperty("grid", Boolean.toString(grid));
        props.setProperty("dragMap", Boolean.toString(dragMap));
        props.setProperty("hideLabelsLowZoom", Boolean.toString(hideLabelsLowZoom));
        props.setProperty("avoidLabels", Boolean.toString(avoidLabels));
        props.setProperty("markerScalePercent", Integer.toString(markerScalePercent));
        props.setProperty("customMarkerDefaultIcon", customMarkerDefaultIcon.name());
        props.setProperty("customMarkerDefaultColor", Integer.toString(customMarkerDefaultColor));
        props.setProperty("customMarkerDefaultSize", Integer.toString(customMarkerDefaultSize));
        props.setProperty("requestBatchSize", Integer.toString(requestBatchSize));
        props.setProperty("liveScanIntervalTicks", Integer.toString(liveScanIntervalTicks));
        for (MapMarkerStore.StructurePreset preset
                : MapMarkerStore.StructurePreset.values()) {
            String prefix = "structurePreset." + preset.name();
            props.setProperty(prefix + ".icon", structurePresetIcon(preset).name());
            props.setProperty(prefix + ".primary",
                    Integer.toString(structurePresetPrimaryColor(preset)));
            props.setProperty(prefix + ".accent",
                    Integer.toString(structurePresetAccentColor(preset)));
            props.setProperty(prefix + ".line",
                    Integer.toString(structurePresetLineColor(preset)));
            props.setProperty(prefix + ".visible",
                    Boolean.toString(structureVisible(preset)));
        }

        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(
                    path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(output, "Cartograph V3 map UI settings");
            }
        } catch (IOException ignored) {}
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("cartograph_v3.properties");
    }
}
