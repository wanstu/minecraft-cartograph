package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Runtime settings shared by all frontends. No UI presentation state lives here. */
final class CartographSettings {
    private static final int SCHEMA = 1;
    private static final int DEFAULT_REQUEST_BATCH = 64;
    private static final int DEFAULT_LIVE_SCAN_INTERVAL = 10;

    private static boolean loaded;
    private static int requestBatchSize = DEFAULT_REQUEST_BATCH;
    private static int liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;

    private CartographSettings() {}

    static synchronized int requestBatchSize() {
        ensureLoaded();
        return requestBatchSize;
    }

    static synchronized int liveScanIntervalTicks() {
        ensureLoaded();
        return liveScanIntervalTicks;
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
        } catch (IOException | RuntimeException ignored) {
            requestBatchSize = DEFAULT_REQUEST_BATCH;
            liveScanIntervalTicks = DEFAULT_LIVE_SCAN_INTERVAL;
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("schema", Integer.toString(SCHEMA));
        p.setProperty("requestBatchSize", Integer.toString(requestBatchSize));
        p.setProperty("liveScanIntervalTicks", Integer.toString(liveScanIntervalTicks));
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                p.store(output, "Cartograph V3.1 runtime settings");
            }
        } catch (IOException ignored) {}
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("cartograph-v31.properties");
    }
}
