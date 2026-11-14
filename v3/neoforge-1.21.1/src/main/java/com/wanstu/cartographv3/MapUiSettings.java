package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

final class MapUiSettings {
    enum MarkerType { ARROW, CROSS }

    private static final int DEFAULT_COLOR = 0xe53935;
    private static final int DEFAULT_SIZE = 12;
    private static final int DEFAULT_REQUEST_BATCH = 64;
    private static final int DEFAULT_LIVE_SCAN_INTERVAL = 10;

    private static boolean loaded;
    private static MarkerType markerType = MarkerType.ARROW;
    private static int markerColor = DEFAULT_COLOR;
    private static int markerSize = DEFAULT_SIZE;
    private static boolean markerLayerEnabled = true;
    private static boolean structureLayerEnabled = true;
    private static int requestBatchSize = DEFAULT_REQUEST_BATCH;
    private static int liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;

    private MapUiSettings() {}

    static synchronized MarkerType markerType() {
        ensureLoaded();
        return markerType;
    }

    static synchronized int markerColor() {
        ensureLoaded();
        return markerColor;
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

    static synchronized void setMarkerType(MarkerType next) {
        ensureLoaded();
        markerType = next == null ? MarkerType.ARROW : next;
        save();
    }

    static synchronized void setMarkerColor(int rgb) {
        ensureLoaded();
        markerColor = rgb & 0x00ffffff;
        save();
    }

    static synchronized void setMarkerSize(int size) {
        ensureLoaded();
        markerSize = Math.max(6, Math.min(32, size));
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
                markerColor = Integer.parseInt(
                        props.getProperty("markerColor", Integer.toString(DEFAULT_COLOR)))
                        & 0x00ffffff;
            } catch (NumberFormatException ignored) {
                markerColor = DEFAULT_COLOR;
            }
            try {
                markerSize = Math.max(6, Math.min(32,
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
        } catch (IOException ignored) {}
    }

    private static void save() {
        Properties props = new Properties();
        props.setProperty("markerType", markerType.name());
        props.setProperty("markerColor", Integer.toString(markerColor));
        props.setProperty("markerSize", Integer.toString(markerSize));
        props.setProperty("markerLayerEnabled", Boolean.toString(markerLayerEnabled));
        props.setProperty("structureLayerEnabled", Boolean.toString(structureLayerEnabled));
        props.setProperty("requestBatchSize", Integer.toString(requestBatchSize));
        props.setProperty("liveScanIntervalTicks", Integer.toString(liveScanIntervalTicks));

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
