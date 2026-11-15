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
    enum MarkerType { ARROW, CROSS }

    private static final int DEFAULT_PRIMARY_COLOR = MapMarkerStore.DEFAULT_PRIMARY_COLOR;
    private static final int DEFAULT_COLOR = 0xe53935;
    private static final int DEFAULT_LINE_COLOR = MapMarkerStore.DEFAULT_LINE_COLOR;
    private static final int DEFAULT_SIZE = 12;
    private static final int DEFAULT_REQUEST_BATCH = 64;
    private static final int DEFAULT_LIVE_SCAN_INTERVAL = 10;
    private static final int STRUCTURE_PRESET_VERSION = 3;

    private static boolean loaded;
    private static MarkerType markerType = MarkerType.ARROW;
    private static int markerPrimaryColor = DEFAULT_PRIMARY_COLOR;
    private static int markerColor = DEFAULT_COLOR;
    private static int markerLineColor = DEFAULT_LINE_COLOR;
    private static int markerSize = DEFAULT_SIZE;
    private static boolean markerLayerEnabled = true;
    private static boolean structureLayerEnabled = true;
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
            int presetVersion = 0;
            try {
                presetVersion = Integer.parseInt(
                        props.getProperty("structurePresetVersion", "0"));
            } catch (NumberFormatException ignored) {}
            if (presetVersion >= STRUCTURE_PRESET_VERSION) {
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
                }
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
        props.setProperty("markerType", markerType.name());
        props.setProperty("markerPrimaryColor", Integer.toString(markerPrimaryColor));
        props.setProperty("markerColor", Integer.toString(markerColor));
        props.setProperty("markerLineColor", Integer.toString(markerLineColor));
        props.setProperty("markerSize", Integer.toString(markerSize));
        props.setProperty("markerLayerEnabled", Boolean.toString(markerLayerEnabled));
        props.setProperty("structureLayerEnabled", Boolean.toString(structureLayerEnabled));
        props.setProperty("requestBatchSize", Integer.toString(requestBatchSize));
        props.setProperty("liveScanIntervalTicks", Integer.toString(liveScanIntervalTicks));
        props.setProperty(
                "structurePresetVersion",
                Integer.toString(STRUCTURE_PRESET_VERSION));
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
