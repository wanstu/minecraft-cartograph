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
    enum Origin { CUSTOM, AUTO }

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
        TREE("森林"),
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
        int color;
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
            String name, Icon icon, int color, String description) {
        Marker marker = new Marker();
        UUID id = UUID.randomUUID();
        marker.id = id.toString();
        marker.dimension = dimension;
        marker.x = x;
        marker.z = z;
        marker.name = normalizeName(name, "标点");
        marker.icon = icon == null ? Icon.PIN : icon;
        marker.color = color & 0x00ffffff;
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
                        marker.color = defaultStructureColor(hit.structureId());
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
            marker.color = defaultStructureColor(hit.structureId());
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
            UUID id, String name, Icon icon, int color, String description) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.name = normalizeName(name, marker.name);
        marker.icon = icon == null ? marker.icon : icon;
        marker.color = color & 0x00ffffff;
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
        marker.color = defaultStructureColor(marker.structureId);
        marker.description = defaultStructureDescription(marker.structureId);
        marker.modifiedAuto = false;
        marker.deleted = false;
        saveAsync();
    }

    private static boolean valid(Marker marker) {
        if (marker == null || marker.id == null || marker.dimension == null || marker.origin == null) return false;
        try { UUID.fromString(marker.id); } catch (IllegalArgumentException invalid) { return false; }
        if (marker.icon == null) marker.icon = Icon.PIN;
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

    static Icon defaultStructureIcon(String id) {
        String path = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (path.contains("village")) return Icon.VILLAGE;
        if (path.contains("mansion")) return Icon.MANSION;
        if (path.contains("pyramid") || path.contains("temple") || path.contains("monument")
                || path.contains("hut") || path.contains("igloo")) return Icon.TEMPLE;
        if (path.contains("outpost")) return Icon.OUTPOST;
        if (path.contains("mineshaft")) return Icon.MINE;
        if (path.contains("portal")) return Icon.PORTAL;
        if (path.contains("shipwreck")) return Icon.SHIP;
        if (path.contains("city")) return Icon.CITY;
        if (path.contains("fortress") || path.contains("stronghold") || path.contains("bastion"))
            return Icon.FORTRESS;
        return Icon.STAR;
    }

    static int defaultStructureColor(String id) {
        return 0xf0c54a;
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
