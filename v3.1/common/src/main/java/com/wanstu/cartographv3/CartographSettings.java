package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/** Settings shared by all frontends. */
final class CartographSettings {
    private static final int SCHEMA = 3;
    private static final int DEFAULT_REQUEST_BATCH = 64;
    private static final int DEFAULT_LIVE_SCAN_INTERVAL = 10;

    private static boolean loaded;
    private static int requestBatchSize = DEFAULT_REQUEST_BATCH;
    private static int liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;
    private static CartographFrontendTarget frontendTarget = CartographFrontendTarget.IN_GAME;
    private static MapDrawingMode drawingMode = MapDrawingMode.SATELLITE;
    private static String visualStyleId = MapVisualStyleRegistry.defaultStyle().id();
    private static final Map<MapMarkerStore.StructurePreset, StructureAppearance> STRUCTURE_PRESETS =
            new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private static long structurePresetRevision;

    record StructureAppearance(MapMarkerStore.Icon icon, int color) {}

    private CartographSettings() {}

    static synchronized int requestBatchSize() {
        ensureLoaded();
        return requestBatchSize;
    }

    static synchronized int liveScanIntervalTicks() {
        ensureLoaded();
        return liveScanIntervalTicks;
    }

    static synchronized CartographFrontendTarget frontendTarget() {
        ensureLoaded();
        return frontendTarget;
    }

    static synchronized MapDrawingMode drawingMode() {
        ensureLoaded();
        return drawingMode;
    }

    static synchronized MapVisualStyleRegistry.Style visualStyle() {
        ensureLoaded();
        return MapVisualStyleRegistry.find(visualStyleId);
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

    static synchronized void setFrontendTarget(CartographFrontendTarget value) {
        ensureLoaded();
        frontendTarget = value == null ? CartographFrontendTarget.IN_GAME : value;
        save();
    }

    static synchronized void setDrawingMode(MapDrawingMode value) {
        ensureLoaded();
        drawingMode = value == null ? MapDrawingMode.SATELLITE : value;
        save();
    }

    static synchronized void setVisualStyle(String id) {
        ensureLoaded();
        visualStyleId = MapVisualStyleRegistry.find(id).id();
        save();
    }

    static synchronized StructureAppearance structureAppearance(
            MapMarkerStore.StructurePreset preset) {
        ensureLoaded();
        StructureAppearance appearance = STRUCTURE_PRESETS.get(preset);
        return appearance != null ? appearance
                : new StructureAppearance(preset.defaultIcon, preset.defaultAccent);
    }

    static synchronized long structurePresetRevision() {
        return structurePresetRevision;
    }

    static synchronized void updateStructurePresets(
            boolean reset,
            Map<MapMarkerStore.StructurePreset, StructureAppearance> changes) throws IOException {
        ensureLoaded();
        var updated = new EnumMap<MapMarkerStore.StructurePreset, StructureAppearance>(
                MapMarkerStore.StructurePreset.class);
        if (!reset) updated.putAll(STRUCTURE_PRESETS);
        changes.forEach((preset, appearance) -> {
            if (appearance.icon() == preset.defaultIcon && appearance.color() == preset.defaultAccent) {
                updated.remove(preset);
            } else {
                updated.put(preset, appearance);
            }
        });
        if (updated.equals(STRUCTURE_PRESETS)) return;
        writeSettings(updated);
        STRUCTURE_PRESETS.clear();
        STRUCTURE_PRESETS.putAll(updated);
        structurePresetRevision++;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) return;
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            p.load(input);
            if (Integer.parseInt(p.getProperty("schema", "-1")) != SCHEMA) return;
            requestBatchSize = Math.max(8, Math.min(256,
                    Integer.parseInt(p.getProperty(
                            "requestBatchSize", Integer.toString(DEFAULT_REQUEST_BATCH)))));
            liveScanIntervalTicks = Math.max(5, Math.min(100,
                    Integer.parseInt(p.getProperty(
                            "liveScanIntervalTicks", Integer.toString(DEFAULT_LIVE_SCAN_INTERVAL)))));
            frontendTarget = CartographFrontendTarget.parse(
                    p.getProperty("mapFrontend", CartographFrontendTarget.IN_GAME.id()));
            drawingMode = MapDrawingMode.parse(
                    p.getProperty("drawingMode", MapDrawingMode.SATELLITE.id()));
            visualStyleId = MapVisualStyleRegistry.find(
                    p.getProperty("visualStyle", MapVisualStyleRegistry.defaultStyle().id())).id();
            for (var preset : MapMarkerStore.StructurePreset.values()) {
                String key = "structurePreset." + preset.name();
                String icon = p.getProperty(key + ".icon");
                String color = p.getProperty(key + ".color");
                if (icon == null || color == null) continue;
                try {
                    int rgb = Integer.parseInt(color, 16);
                    if (rgb >= 0 && rgb <= 0xffffff) {
                        STRUCTURE_PRESETS.put(preset,
                                new StructureAppearance(MapMarkerStore.Icon.valueOf(icon), rgb));
                    }
                } catch (IllegalArgumentException ignored) {
                    // An invalid entry leaves that structure on its built-in preset.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            requestBatchSize = DEFAULT_REQUEST_BATCH;
            liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;
            frontendTarget = CartographFrontendTarget.IN_GAME;
            drawingMode = MapDrawingMode.SATELLITE;
            visualStyleId = MapVisualStyleRegistry.defaultStyle().id();
        }
    }

    private static void save() {
        try {
            writeSettings(STRUCTURE_PRESETS);
        } catch (IOException ignored) {}
    }

    private static void writeSettings(
            Map<MapMarkerStore.StructurePreset, StructureAppearance> presets) throws IOException {
        Properties p = new Properties();
        p.setProperty("schema", Integer.toString(SCHEMA));
        p.setProperty("requestBatchSize", Integer.toString(requestBatchSize));
        p.setProperty("liveScanIntervalTicks", Integer.toString(liveScanIntervalTicks));
        p.setProperty("mapFrontend", frontendTarget.id());
        p.setProperty("drawingMode", drawingMode.id());
        p.setProperty("visualStyle", visualStyleId);
        presets.forEach((preset, appearance) -> {
            String key = "structurePreset." + preset.name();
            p.setProperty(key + ".icon", appearance.icon().name());
            p.setProperty(key + ".color", Integer.toHexString(appearance.color()));
        });

        Path path = path();
        Files.createDirectories(path.getParent());
        Path pending = Files.createTempFile(path.getParent(), "cartograph-v31-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(pending)) {
                p.store(output, "Cartograph V3.1 settings");
            }
            try {
                Files.move(pending, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("cartograph-v31.properties");
    }
}
