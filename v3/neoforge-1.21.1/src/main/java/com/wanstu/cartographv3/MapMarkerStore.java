package com.wanstu.cartographv3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MapMarkerStore {
    static final int DEFAULT_PRIMARY_COLOR = 0xe8eef5;
    static final int DEFAULT_ACCENT_COLOR = 0xf2bd45;
    static final int DEFAULT_LINE_COLOR = 0xb8c3cc;

    enum Origin { CUSTOM, AUTO }

    enum StructurePreset {
        VILLAGE("村庄", Icon.VILLAGE, 0xfff1cf, 0xdba637, 0x765d2c),
        MANSION("林地府邸", Icon.MANSION, 0xe7e0d6, 0x765844, 0x40352f),
        TEMPLE("神殿 / 遗迹", Icon.TEMPLE, 0xffefbf, 0xd8a238, 0x816126),
        OUTPOST("前哨站", Icon.OUTPOST, 0xe8e1d7, 0x8a6951, 0x463831),
        MINE("废弃矿井", Icon.MINE, 0xdce1e6, 0xa97843, 0x44494e),
        PORTAL("传送门", Icon.PORTAL, 0xeee6ff, 0x8151d2, 0x4a326d),
        SHIP("沉船", Icon.SHIP, 0xe1f0f7, 0x3f87a4, 0x35525f),
        CITY("城市", Icon.CITY, 0xe8ebee, 0x7e8994, 0x434950),
        FORTRESS("要塞 / 堡垒", Icon.FORTRESS, 0xead9d3, 0xa65347, 0x5c342d),
        OTHER("其他结构", Icon.STAR, 0xfff2c7, 0xe4b13c, 0x735a24);

        final String label;
        final Icon defaultIcon;
        final int defaultPrimary;
        final int defaultAccent;
        final int defaultLine;

        StructurePreset(
                String label, Icon defaultIcon,
                int defaultPrimary, int defaultAccent, int defaultLine) {
            this.label = label;
            this.defaultIcon = defaultIcon;
            this.defaultPrimary = defaultPrimary;
            this.defaultAccent = defaultAccent;
            this.defaultLine = defaultLine;
        }
    }

    enum Icon {
        PIN("标点"),
        VILLAGE("村庄"),
        MANSION("林地府邸"),
        TEMPLE("神殿"),
        OUTPOST("前哨站"),
        MINE("矿井"),
        PORTAL("传送门"),
        SHIP("沉船"),
        CITY("城市"),
        FORTRESS("要塞"),
        HOME("住宅"),
        FLAG("旗帜"),
        CAMP("营地"),
        FARM("农场"),
        SHOP("商店"),
        RAIL("铁路"),
        HARBOR("港口"),
        FACTORY("工厂"),
        TOWER("高塔"),
        CAVE("洞穴"),
        MOUNTAIN("山峰"),
        MOUNTAIN_RANGE("山脉"),
        HILL("丘陵"),
        BASIN("盆地"),
        LAKE("湖泊"),
        RIVER("河流"),
        LARGE_RIVER("大河"),
        SMALL_RIVER("小河"),
        COAST("海岸"),
        BEACH("沙滩"),
        TREE("森林"),
        SWAMP("沼泽"),
        DESERT("沙漠"),
        VOLCANO("火山"),
        SINKHOLE("天坑"),
        ISLAND("岛屿"),
        DELTA("三角洲"),
        WATERFALL("瀑布"),
        WATER("水域"),
        DANGER("危险"),
        INFO("信息"),
        FOOD("食物"),
        STAR("星标");

        final String label;
        Icon(String label) { this.label = label; }
    }

    static final class Marker {
        String id;
        String dimension;
        int x;
        int z;
        String name;
        Icon icon;
        // Kept as "color" for backward compatibility: this is the accent channel.
        int color;
        Integer primaryColor;
        Integer lineColor;
        String description;
        Origin origin;
        String autoKey;
        String structureId;
        boolean modifiedAuto;
        boolean deleted;

        Marker copy() {
            Marker next = new Marker();
            next.id = id;
            next.dimension = dimension;
            next.x = x;
            next.z = z;
            next.name = name;
            next.icon = icon;
            next.color = color;
            next.primaryColor = primaryColor;
            next.lineColor = lineColor;
            next.description = description;
            next.origin = origin;
            next.autoKey = autoKey;
            next.structureId = structureId;
            next.modifiedAuto = modifiedAuto;
            next.deleted = deleted;
            return next;
        }

        UUID uuid() { return UUID.fromString(id); }
        boolean isAuto() { return origin == Origin.AUTO; }
        boolean appearsAsCustom() { return origin == Origin.CUSTOM || modifiedAuto; }
        int primary() {
            return primaryColor == null ? DEFAULT_PRIMARY_COLOR : primaryColor & 0x00ffffff;
        }
        int accent() { return color & 0x00ffffff; }
        int line() {
            return lineColor == null ? DEFAULT_LINE_COLOR : lineColor & 0x00ffffff;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Marker>>() {}.getType();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cartograph-v3-marker-store");
        thread.setDaemon(true);
        return thread;
    });

    private static UUID worldId;
    private static final LinkedHashMap<UUID, Marker> MARKERS = new LinkedHashMap<>();
    private static final Map<String, UUID> AUTO_INDEX = new HashMap<>();

    private MapMarkerStore() {}

    static synchronized void ensureWorld(UUID nextWorldId) {
        if (Objects.equals(worldId, nextWorldId)) return;
        worldId = nextWorldId;
        MARKERS.clear();
        AUTO_INDEX.clear();
        if (worldId == null) return;

        Path file = file(worldId);
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Marker> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded == null) return;
            for (Marker marker : loaded) {
                if (!valid(marker)) continue;
                if (marker.origin == Origin.AUTO && !marker.modifiedAuto) {
                    marker.icon = defaultStructureIcon(marker.structureId);
                    marker.primaryColor = defaultStructurePrimaryColor(marker.structureId);
                    marker.color = defaultStructureColor(marker.structureId);
                    marker.lineColor = defaultStructureLineColor(marker.structureId);
                }
                UUID id = marker.uuid();
                MARKERS.put(id, marker);
                if (marker.autoKey != null && !marker.autoKey.isBlank()) {
                    AUTO_INDEX.put(marker.autoKey, id);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            MARKERS.clear();
            AUTO_INDEX.clear();
        }
    }

    static synchronized Marker createCustom(
            String dimension, int x, int z,
            String name, Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description) {
        Marker marker = new Marker();
        UUID id = UUID.randomUUID();
        marker.id = id.toString();
        marker.dimension = dimension;
        marker.x = x;
        marker.z = z;
        marker.name = normalizeName(name, "标点");
        marker.icon = icon == null ? Icon.PIN : icon;
        marker.primaryColor = primaryColor & 0x00ffffff;
        marker.color = accentColor & 0x00ffffff;
        marker.lineColor = lineColor & 0x00ffffff;
        marker.description = normalizeDescription(description);
        marker.origin = Origin.CUSTOM;
        marker.autoKey = null;
        marker.structureId = null;
        marker.modifiedAuto = false;
        marker.deleted = false;
        MARKERS.put(id, marker);
        saveAsync();
        return marker.copy();
    }

    static synchronized void acceptAutomatic(
            String dimension, List<MapProtocol.StructureHit> hits) {
        if (worldId == null || hits == null || hits.isEmpty()) return;
        boolean changed = false;

        for (MapProtocol.StructureHit hit : hits) {
            String autoKey = dimension + "|" + hit.structureId()
                    + "|" + hit.startChunkX() + "|" + hit.startChunkZ();
            UUID existingId = AUTO_INDEX.get(autoKey);
            if (existingId != null) {
                Marker marker = MARKERS.get(existingId);
                if (marker != null && !marker.modifiedAuto) {
                    String nextName = defaultStructureName(hit.structureId());
                    Icon nextIcon = defaultStructureIcon(hit.structureId());
                    if (marker.x != hit.x() || marker.z != hit.z()
                            || !Objects.equals(marker.name, nextName)
                            || marker.icon != nextIcon) {
                        marker.x = hit.x();
                        marker.z = hit.z();
                        marker.name = nextName;
                        marker.icon = nextIcon;
                        marker.primaryColor = defaultStructurePrimaryColor(hit.structureId());
                        marker.color = defaultStructureColor(hit.structureId());
                        marker.lineColor = defaultStructureLineColor(hit.structureId());
                        marker.description = defaultStructureDescription(hit.structureId());
                        marker.structureId = hit.structureId();
                        changed = true;
                    }
                }
                continue;
            }

            Marker marker = new Marker();
            UUID id = UUID.nameUUIDFromBytes(autoKey.getBytes(StandardCharsets.UTF_8));
            marker.id = id.toString();
            marker.dimension = dimension;
            marker.x = hit.x();
            marker.z = hit.z();
            marker.name = defaultStructureName(hit.structureId());
            marker.icon = defaultStructureIcon(hit.structureId());
            marker.primaryColor = defaultStructurePrimaryColor(hit.structureId());
            marker.color = defaultStructureColor(hit.structureId());
            marker.lineColor = defaultStructureLineColor(hit.structureId());
            marker.description = defaultStructureDescription(hit.structureId());
            marker.origin = Origin.AUTO;
            marker.autoKey = autoKey;
            marker.structureId = hit.structureId();
            marker.modifiedAuto = false;
            marker.deleted = false;
            MARKERS.put(id, marker);
            AUTO_INDEX.put(autoKey, id);
            changed = true;
        }

        if (changed) saveAsync();
    }

    static synchronized List<Marker> visible(String dimension) {
        List<Marker> out = new ArrayList<>();
        for (Marker marker : MARKERS.values()) {
            if (!marker.deleted && Objects.equals(marker.dimension, dimension)) out.add(marker.copy());
        }
        return out;
    }

    static synchronized List<Marker> customManagerEntries() {
        List<Marker> out = new ArrayList<>();
        for (Marker marker : MARKERS.values()) {
            if (!marker.deleted && marker.appearsAsCustom()) out.add(marker.copy());
        }
        out.sort(Comparator.comparing(m -> m.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    static synchronized List<Marker> recycleCustom() {
        return recycle(false);
    }

    static synchronized List<Marker> recycleAuto() {
        return recycle(true);
    }

    private static List<Marker> recycle(boolean auto) {
        List<Marker> out = new ArrayList<>();
        for (Marker marker : MARKERS.values()) {
            if (marker.deleted && marker.isAuto() == auto) out.add(marker.copy());
        }
        out.sort(Comparator.comparing(m -> m.name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    static synchronized Optional<Marker> find(UUID id) {
        Marker marker = MARKERS.get(id);
        return marker == null ? Optional.empty() : Optional.of(marker.copy());
    }

    static synchronized void update(
            UUID id, String name, Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.name = normalizeName(name, marker.name);
        marker.icon = icon == null ? marker.icon : icon;
        marker.primaryColor = primaryColor & 0x00ffffff;
        marker.color = accentColor & 0x00ffffff;
        marker.lineColor = lineColor & 0x00ffffff;
        marker.description = normalizeDescription(description);
        if (marker.origin == Origin.AUTO) marker.modifiedAuto = true;
        marker.deleted = false;
        saveAsync();
    }

    static synchronized void delete(UUID id) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.deleted = true;
        saveAsync();
    }

    static synchronized void restore(UUID id) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.deleted = false;
        saveAsync();
    }

    static synchronized void resetAutomatic(UUID id) {
        Marker marker = MARKERS.get(id);
        if (marker == null || marker.origin != Origin.AUTO) return;
        marker.name = defaultStructureName(marker.structureId);
        marker.icon = defaultStructureIcon(marker.structureId);
        marker.primaryColor = defaultStructurePrimaryColor(marker.structureId);
        marker.color = defaultStructureColor(marker.structureId);
        marker.lineColor = defaultStructureLineColor(marker.structureId);
        marker.description = defaultStructureDescription(marker.structureId);
        marker.modifiedAuto = false;
        marker.deleted = false;
        saveAsync();
    }

    private static boolean valid(Marker marker) {
        if (marker == null || marker.id == null || marker.dimension == null || marker.origin == null) return false;
        try { UUID.fromString(marker.id); } catch (IllegalArgumentException invalid) { return false; }
        if (marker.icon == null) marker.icon = Icon.PIN;
        if (marker.primaryColor == null) marker.primaryColor = DEFAULT_PRIMARY_COLOR;
        if (marker.lineColor == null) marker.lineColor = DEFAULT_LINE_COLOR;
        marker.color &= 0x00ffffff;
        marker.primaryColor &= 0x00ffffff;
        marker.lineColor &= 0x00ffffff;
        marker.name = normalizeName(marker.name, "标点");
        marker.description = normalizeDescription(marker.description);
        return true;
    }

    private static String normalizeName(String value, String fallback) {
        String next = value == null ? "" : value.trim();
        if (next.isEmpty()) next = fallback == null || fallback.isBlank() ? "标点" : fallback;
        return next.length() > 64 ? next.substring(0, 64) : next;
    }

    private static String normalizeDescription(String value) {
        String next = value == null ? "" : value.trim();
        return next.length() > 512 ? next.substring(0, 512) : next;
    }

    private static void saveAsync() {
        if (worldId == null) return;
        UUID snapshotWorld = worldId;
        List<Marker> snapshot = MARKERS.values().stream().map(Marker::copy).toList();
        WRITER.execute(() -> {
            Path target = file(snapshotWorld);
            try {
                Files.createDirectories(target.getParent());
                try (Writer writer = Files.newBufferedWriter(
                        target, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    GSON.toJson(snapshot, LIST_TYPE, writer);
                }
            } catch (IOException ignored) {}
        });
    }

    private static Path file(UUID id) {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("cartograph_v3_markers")
                .resolve(id + ".json");
    }

    static String defaultStructureName(String id) {
        String path = id == null ? "" : id.substring(id.indexOf(':') + 1);
        if (path.startsWith("village")) return "村庄";
        if (path.contains("mansion")) return "林地府邸";
        if (path.contains("monument")) return "海底神殿";
        if (path.contains("stronghold")) return "要塞";
        if (path.contains("mineshaft")) return "废弃矿井";
        if (path.contains("pillager_outpost")) return "掠夺者前哨站";
        if (path.contains("desert_pyramid")) return "沙漠神殿";
        if (path.contains("jungle_pyramid")) return "丛林神庙";
        if (path.contains("swamp_hut")) return "沼泽小屋";
        if (path.contains("igloo")) return "雪屋";
        if (path.contains("shipwreck")) return "沉船";
        if (path.contains("ocean_ruin")) return "海底废墟";
        if (path.contains("ruined_portal")) return "废弃传送门";
        if (path.contains("ancient_city")) return "远古城市";
        if (path.contains("trail_ruins")) return "古迹废墟";
        if (path.contains("trial_chambers")) return "试炼密室";
        if (path.contains("fortress")) return "下界要塞";
        if (path.contains("bastion")) return "堡垒遗迹";
        if (path.contains("end_city")) return "末地城";
        return humanize(path);
    }

    static StructurePreset structurePreset(String id) {
        String path = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (path.contains("village")) return StructurePreset.VILLAGE;
        if (path.contains("mansion")) return StructurePreset.MANSION;
        if (path.contains("pyramid") || path.contains("temple") || path.contains("monument")
                || path.contains("hut") || path.contains("igloo")
                || path.contains("ruin") || path.contains("trial_chambers")) {
            return StructurePreset.TEMPLE;
        }
        if (path.contains("outpost")) return StructurePreset.OUTPOST;
        if (path.contains("mineshaft")) return StructurePreset.MINE;
        if (path.contains("portal")) return StructurePreset.PORTAL;
        if (path.contains("shipwreck")) return StructurePreset.SHIP;
        if (path.contains("city")) return StructurePreset.CITY;
        if (path.contains("fortress") || path.contains("stronghold") || path.contains("bastion")) {
            return StructurePreset.FORTRESS;
        }
        return StructurePreset.OTHER;
    }

    static Icon defaultStructureIcon(String id) {
        StructurePreset preset = structurePreset(id);
        return MapUiSettings.structurePresetIcon(preset);
    }

    static synchronized void applyStructurePreset(StructurePreset preset) {
        if (preset == null) return;
        Icon nextIcon = MapUiSettings.structurePresetIcon(preset);
        int nextPrimary = MapUiSettings.structurePresetPrimaryColor(preset);
        int nextAccent = MapUiSettings.structurePresetAccentColor(preset);
        int nextLine = MapUiSettings.structurePresetLineColor(preset);
        boolean changed = false;
        for (Marker marker : MARKERS.values()) {
            if (marker.origin != Origin.AUTO || marker.modifiedAuto) continue;
            if (structurePreset(marker.structureId) != preset) continue;
            if (marker.icon != nextIcon
                    || marker.primary() != nextPrimary
                    || marker.accent() != nextAccent
                    || marker.line() != nextLine) {
                marker.icon = nextIcon;
                marker.primaryColor = nextPrimary;
                marker.color = nextAccent;
                marker.lineColor = nextLine;
                changed = true;
            }
        }
        if (changed) saveAsync();
    }

    static synchronized void applyAllStructurePresets() {
        for (StructurePreset preset : StructurePreset.values()) {
            applyStructurePreset(preset);
        }
    }

    static int defaultStructurePrimaryColor(String id) {
        return MapUiSettings.structurePresetPrimaryColor(structurePreset(id));
    }

    static int defaultStructureColor(String id) {
        return MapUiSettings.structurePresetAccentColor(structurePreset(id));
    }

    static int defaultStructureLineColor(String id) {
        return MapUiSettings.structurePresetLineColor(structurePreset(id));
    }

    static String defaultStructureDescription(String id) {
        String full = id == null ? "未知结构" : id;
        return "自动识别结构：" + full;
    }

    private static String humanize(String path) {
        if (path == null || path.isBlank()) return "结构";
        String[] pieces = path.replace('/', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String piece : pieces) {
            if (piece.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(piece.charAt(0))).append(piece.substring(1));
        }
        return out.isEmpty() ? "结构" : out.toString();
    }
}
