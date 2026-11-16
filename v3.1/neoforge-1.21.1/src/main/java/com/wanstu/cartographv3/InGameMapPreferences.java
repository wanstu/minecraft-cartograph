package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Properties;
import java.util.function.Consumer;

final class InGameMapPreferences {
    enum Flag {
        CUSTOM("custom", true), STRUCTURES("structures", true), NAMES("names", true),
        GRID("grid", false), COORDS("coords", true), PLAYER_RING("playerRing", true),
        DRAG_MAP("dragMap", true), FIX_PLAYER_SIZE("fixedPlayerSize", true),
        AVOID_LABELS("avoidLabels", true), HIDE_LOW_ZOOM("hideLabelsLowZoom", true),
        DEBUG("debug", false), CROSS_PLAYER("crossPlayer", false);

        final String key;
        final boolean initial;
        Flag(String key, boolean initial) { this.key = key; this.initial = initial; }
    }

    private static final EnumSet<MapMarkerStore.StructurePreset> UNDERGROUND = EnumSet.of(
            MapMarkerStore.StructurePreset.MINESHAFT, MapMarkerStore.StructurePreset.TRIAL_CHAMBERS,
            MapMarkerStore.StructurePreset.ANCIENT_CITY, MapMarkerStore.StructurePreset.STRONGHOLD,
            MapMarkerStore.StructurePreset.BURIED_TREASURE);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("cartograph.v3.native");

    private final Path path = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve("cartograph-v31-native-ui.properties");
    private final Properties values = new Properties();

    InGameMapPreferences() {
        if (!Files.isRegularFile(path)) return;
        try (var input = Files.newInputStream(path)) {
            values.load(input);
        } catch (IOException | IllegalArgumentException failure) {
            LOG.warn("Cannot read native map settings", failure);
        }
    }

    boolean enabled(Flag flag) {
        return Boolean.parseBoolean(values.getProperty(flag.key, Boolean.toString(flag.initial)));
    }

    void toggle(Flag flag) {
        change(p -> p.setProperty(flag.key, Boolean.toString(!enabled(flag))));
    }

    boolean layer(String id, MapMarkerStore.StructurePreset preset) {
        return Boolean.parseBoolean(values.getProperty("layer." + id,
                Boolean.toString(preset == null || !UNDERGROUND.contains(preset))));
    }

    void toggleLayer(String id, MapMarkerStore.StructurePreset preset) {
        change(p -> p.setProperty("layer." + id, Boolean.toString(!layer(id, preset))));
    }

    int number(String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(values.getProperty(key)), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    void number(String key, int value) {
        change(p -> p.setProperty(key, Integer.toString(value)));
    }

    MapMarkerStore.Icon markerIcon() {
        try {
            return MapMarkerStore.Icon.valueOf(values.getProperty("markerIcon", "PIN"));
        } catch (IllegalArgumentException ignored) {
            return MapMarkerStore.Icon.PIN;
        }
    }

    void markerIcon(MapMarkerStore.Icon icon) {
        change(p -> p.setProperty("markerIcon", icon.name()));
    }

    private void change(Consumer<Properties> update) {
        update.accept(values);
        try {
            Files.createDirectories(path.getParent());
            Path pending = Files.createTempFile(path.getParent(), "cartograph-native-", ".tmp");
            try {
                try (var output = Files.newOutputStream(pending)) {
                    values.store(output, "Cartograph native map display settings");
                }
                try {
                    Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException failure) {
            LOG.warn("Cannot save native map settings", failure);
        }
    }
}
