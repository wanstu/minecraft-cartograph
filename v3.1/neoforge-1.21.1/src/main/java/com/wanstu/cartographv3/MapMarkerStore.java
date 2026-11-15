package com.wanstu.cartographv3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MapMarkerStore {
    private static final int STORE_SCHEMA = 3;
    static final int DEFAULT_PRIMARY_COLOR = 0xe8eef5;
    static final int DEFAULT_ACCENT_COLOR = 0xf2bd45;
    static final int DEFAULT_LINE_COLOR = 0xb8c3cc;

    enum Origin { CUSTOM, AUTO }

    enum StructurePreset {
        VILLAGE("村庄", Icon.VILLAGE, 0xf4c84f, 0xf4c84f, 0xf4c84f),
        MANSION("林地府邸", Icon.MANSION, 0xe7d7c0, 0xe7d7c0, 0xe7d7c0),
        DESERT_PYRAMID("沙漠神殿", Icon.DESERT_PYRAMID, 0xddb85e, 0xddb85e, 0xddb85e),
        JUNGLE_PYRAMID("丛林神庙", Icon.JUNGLE_TEMPLE, 0x8fcb73, 0x8fcb73, 0x8fcb73),
        SWAMP_HUT("沼泽小屋", Icon.SWAMP_HUT, 0xa6b17f, 0xa6b17f, 0xa6b17f),
        IGLOO("雪屋", Icon.IGLOO, 0xedf4f6, 0xedf4f6, 0xedf4f6),
        OCEAN_MONUMENT("海底神殿", Icon.OCEAN_MONUMENT, 0x70c7ce, 0x70c7ce, 0x70c7ce),
        OUTPOST("掠夺者前哨站", Icon.OUTPOST, 0xc89a76, 0xc89a76, 0xc89a76),
        MINESHAFT("废弃矿井", Icon.MINE, 0xc08a55, 0xc08a55, 0xc08a55),
        RUINED_PORTAL("废弃传送门", Icon.RUINED_PORTAL, 0xa77ce8, 0xa77ce8, 0xa77ce8),
        SHIPWRECK("沉船", Icon.SHIPWRECK, 0x6eb5d1, 0x6eb5d1, 0x6eb5d1),
        OCEAN_RUIN("海底废墟", Icon.OCEAN_RUIN, 0x64c0d3, 0x64c0d3, 0x64c0d3),
        BURIED_TREASURE("埋藏的宝藏", Icon.BURIED_TREASURE, 0xf3d66a, 0xf3d66a, 0xf3d66a),
        TRAIL_RUINS("古迹废墟", Icon.TRAIL_RUINS, 0xba9673, 0xba9673, 0xba9673),
        TRIAL_CHAMBERS("试炼密室", Icon.TRIAL_CHAMBERS, 0xd6a06a, 0xd6a06a, 0xd6a06a),
        ANCIENT_CITY("远古城市", Icon.ANCIENT_CITY, 0x6daba8, 0x6daba8, 0x6daba8),
        STRONGHOLD("要塞", Icon.STRONGHOLD, 0xb7ada0, 0xb7ada0, 0xb7ada0),
        NETHER_FORTRESS("下界要塞", Icon.NETHER_FORTRESS, 0xb8554e, 0xb8554e, 0xb8554e),
        BASTION("堡垒遗迹", Icon.BASTION_REMNANT, 0x84695a, 0x84695a, 0x84695a),
        NETHER_FOSSIL("下界化石", Icon.NETHER_FOSSIL, 0xd7c2a3, 0xd7c2a3, 0xd7c2a3),
        END_CITY("末地城", Icon.END_CITY, 0xc7a3d5, 0xc7a3d5, 0xc7a3d5);

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
        DESERT_PYRAMID("沙漠神殿"),
        JUNGLE_TEMPLE("丛林神庙"),
        SWAMP_HUT("沼泽小屋"),
        IGLOO("雪屋"),
        OCEAN_MONUMENT("海底神殿"),
        RUINED_PORTAL("废弃传送门"),
        SHIPWRECK("沉船"),
        OCEAN_RUIN("海底废墟"),
        BURIED_TREASURE("埋藏的宝藏"),
        TRAIL_RUINS("古迹废墟"),
        TRIAL_CHAMBERS("试炼密室"),
        ANCIENT_CITY("远古城市"),
        STRONGHOLD("要塞"),
        NETHER_FORTRESS("下界要塞"),
        BASTION_REMNANT("堡垒遗迹"),
        NETHER_FOSSIL("下界化石"),
        END_CITY("末地城"),
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
        int y;
        int z;
        String name;
        Icon icon;
        int primaryColor;
        int accentColor;
        int lineColor;
        String description;
        Origin origin;
        String autoKey;
        String structureId;
        boolean modifiedAuto;
        boolean deleted;
        long deletedAt;

        Marker copy() {
            Marker next = new Marker();
            next.id = id;
            next.dimension = dimension;
            next.x = x;
            next.y = y;
            next.z = z;
            next.name = name;
            next.icon = icon;
            next.accentColor = accentColor;
            next.primaryColor = primaryColor;
            next.lineColor = lineColor;
            next.description = description;
            next.origin = origin;
            next.autoKey = autoKey;
            next.structureId = structureId;
            next.modifiedAuto = modifiedAuto;
            next.deleted = deleted;
            next.deletedAt = deletedAt;
            return next;
        }

        UUID uuid() { return UUID.fromString(id); }
        boolean isAuto() { return origin == Origin.AUTO; }
        boolean appearsAsCustom() { return origin == Origin.CUSTOM || modifiedAuto; }
        int primary() { return primaryColor & 0x00ffffff; }
        int accent() { return accentColor & 0x00ffffff; }
        int line() { return lineColor & 0x00ffffff; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class StoreFile {
        int schema = STORE_SCHEMA;
        List<Marker> markers = List.of();

        StoreFile() {}

        StoreFile(List<Marker> markers) {
            this.schema = STORE_SCHEMA;
            this.markers = markers;
        }
    }
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
            StoreFile store = GSON.fromJson(reader, StoreFile.class);
            if (store == null || store.schema != STORE_SCHEMA || store.markers == null) return;
            for (Marker marker : store.markers) {
                if (!valid(marker)) continue;
                if (marker.origin == Origin.AUTO && !marker.modifiedAuto) {
                    marker.name = defaultStructureName(marker.structureId);
                    marker.icon = defaultStructureIcon(marker.structureId);
                    marker.primaryColor = defaultStructurePrimaryColor(marker.structureId);
                    marker.accentColor = defaultStructureColor(marker.structureId);
                    marker.lineColor = defaultStructureLineColor(marker.structureId);
                    marker.description = defaultStructureDescription(marker.structureId);
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
            String dimension, int x, int y, int z,
            String name, Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description) {
        Marker marker = new Marker();
        UUID id = UUID.randomUUID();
        marker.id = id.toString();
        marker.dimension = dimension;
        marker.x = x;
        marker.y = y;
        marker.z = z;
        marker.name = normalizeName(name, "标点");
        marker.icon = icon == null ? Icon.PIN : icon;
        marker.primaryColor = primaryColor & 0x00ffffff;
        marker.accentColor = accentColor & 0x00ffffff;
        marker.lineColor = lineColor & 0x00ffffff;
        marker.description = normalizeDescription(description);
        marker.origin = Origin.CUSTOM;
        marker.autoKey = null;
        marker.structureId = null;
        marker.modifiedAuto = false;
        marker.deleted = false;
        marker.deletedAt = 0L;
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
                    int nextPrimary = defaultStructurePrimaryColor(hit.structureId());
                    int nextAccent = defaultStructureColor(hit.structureId());
                    int nextBorder = defaultStructureLineColor(hit.structureId());
                    String nextDescription = defaultStructureDescription(hit.structureId());

                    if (marker.x != hit.x() || marker.z != hit.z()
                            || !Objects.equals(marker.name, nextName)
                            || marker.icon != nextIcon
                            || marker.primary() != nextPrimary
                            || marker.accent() != nextAccent
                            || marker.line() != nextBorder
                            || !Objects.equals(marker.description, nextDescription)
                            || !Objects.equals(marker.structureId, hit.structureId())) {
                        marker.x = hit.x();
                        marker.z = hit.z();
                        marker.name = nextName;
                        marker.icon = nextIcon;
                        marker.primaryColor = nextPrimary;
                        marker.accentColor = nextAccent;
                        marker.lineColor = nextBorder;
                        marker.description = nextDescription;
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
            marker.y = 64;
            marker.z = hit.z();
            marker.name = defaultStructureName(hit.structureId());
            marker.icon = defaultStructureIcon(hit.structureId());
            marker.primaryColor = defaultStructurePrimaryColor(hit.structureId());
            marker.accentColor = defaultStructureColor(hit.structureId());
            marker.lineColor = defaultStructureLineColor(hit.structureId());
            marker.description = defaultStructureDescription(hit.structureId());
            marker.origin = Origin.AUTO;
            marker.autoKey = autoKey;
            marker.structureId = hit.structureId();
            marker.modifiedAuto = false;
            marker.deleted = false;
            marker.deletedAt = 0L;
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

    static synchronized List<Marker> recycleAll() {
        List<Marker> out = new ArrayList<>();
        for (Marker marker : MARKERS.values()) {
            if (marker.deleted) out.add(marker.copy());
        }
        out.sort(Comparator.comparingLong((Marker m) -> m.deletedAt).reversed());
        return out;
    }

    static synchronized int recycleCount() {
        int count = 0;
        for (Marker marker : MARKERS.values()) if (marker.deleted) count++;
        return count;
    }

    static synchronized void clearRecycle() {
        Iterator<Map.Entry<UUID, Marker>> iterator = MARKERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Marker marker = iterator.next().getValue();
            if (!marker.deleted) continue;
            if (marker.autoKey != null) AUTO_INDEX.remove(marker.autoKey);
            iterator.remove();
        }
        saveAsync();
    }

    private static List<Marker> recycle(boolean auto) {
        List<Marker> out = new ArrayList<>();
        for (Marker marker : MARKERS.values()) {
            if (marker.deleted && marker.isAuto() == auto) out.add(marker.copy());
        }
        out.sort(Comparator.comparingLong((Marker m) -> m.deletedAt).reversed());
        return out;
    }

    static synchronized Optional<Marker> find(UUID id) {
        Marker marker = MARKERS.get(id);
        return marker == null ? Optional.empty() : Optional.of(marker.copy());
    }

    static synchronized void update(
            UUID id, int x, int y, int z,
            String name, Icon icon,
            int primaryColor, int accentColor, int lineColor,
            String description) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.x = x;
        marker.y = y;
        marker.z = z;
        marker.name = normalizeName(name, marker.name);
        marker.icon = icon == null ? marker.icon : icon;
        marker.primaryColor = primaryColor & 0x00ffffff;
        marker.accentColor = accentColor & 0x00ffffff;
        marker.lineColor = lineColor & 0x00ffffff;
        marker.description = normalizeDescription(description);
        if (marker.origin == Origin.AUTO) marker.modifiedAuto = true;
        marker.deleted = false;
        marker.deletedAt = 0L;
        saveAsync();
    }

    static synchronized void delete(UUID id) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.deleted = true;
        marker.deletedAt = System.currentTimeMillis();
        enforceRecycleLimit();
        saveAsync();
    }

    static synchronized void restore(UUID id) {
        Marker marker = MARKERS.get(id);
        if (marker == null) return;
        marker.deleted = false;
        marker.deletedAt = 0L;
        saveAsync();
    }

    static synchronized void resetAutomatic(UUID id) {
        Marker marker = MARKERS.get(id);
        if (marker == null || marker.origin != Origin.AUTO) return;
        marker.name = defaultStructureName(marker.structureId);
        marker.icon = defaultStructureIcon(marker.structureId);
        marker.primaryColor = defaultStructurePrimaryColor(marker.structureId);
        marker.accentColor = defaultStructureColor(marker.structureId);
        marker.lineColor = defaultStructureLineColor(marker.structureId);
        marker.description = defaultStructureDescription(marker.structureId);
        marker.modifiedAuto = false;
        marker.deleted = false;
        marker.deletedAt = 0L;
        saveAsync();
    }

    private static void enforceRecycleLimit() {
        List<Map.Entry<UUID, Marker>> deleted = MARKERS.entrySet().stream()
                .filter(entry -> entry.getValue().deleted)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().deletedAt))
                .toList();
        int remove = deleted.size() - 100;
        for (int i = 0; i < remove; i++) {
            Map.Entry<UUID, Marker> entry = deleted.get(i);
            Marker marker = entry.getValue();
            if (marker.autoKey != null) AUTO_INDEX.remove(marker.autoKey);
            MARKERS.remove(entry.getKey());
        }
    }

    private static boolean valid(Marker marker) {
        if (marker == null || marker.id == null || marker.dimension == null || marker.origin == null) return false;
        try { UUID.fromString(marker.id); } catch (IllegalArgumentException invalid) { return false; }
        if (marker.icon == null) return false;
        if (marker.deleted && marker.deletedAt <= 0L) return false;
        if (!marker.deleted) marker.deletedAt = 0L;
        marker.primaryColor &= 0x00ffffff;
        marker.accentColor &= 0x00ffffff;
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
                    GSON.toJson(new StoreFile(snapshot), writer);
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
        if (path.contains("jungle_pyramid") || path.contains("jungle_temple")) {
            return "丛林神庙";
        }
        if (path.contains("swamp_hut")) return "沼泽小屋";
        if (path.contains("igloo")) return "雪屋";
        if (path.contains("shipwreck")) return "沉船";
        if (path.contains("ocean_ruin")) return "海底废墟";
        if (path.contains("buried_treasure")) return "埋藏的宝藏";
        if (path.contains("ruined_portal")) return "废弃传送门";
        if (path.contains("ancient_city")) return "远古城市";
        if (path.contains("trail_ruins")) return "古迹废墟";
        if (path.contains("trial_chambers")) return "试炼密室";
        if (path.contains("fortress")) return "下界要塞";
        if (path.contains("bastion")) return "堡垒遗迹";
        if (path.contains("nether_fossil")) return "下界化石";
        if (path.contains("end_city")) return "末地城";
        return humanize(path);
    }

    static StructurePreset structurePreset(String id) {
        String path = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (path.contains("village")) return StructurePreset.VILLAGE;
        if (path.contains("mansion")) return StructurePreset.MANSION;
        if (path.contains("desert_pyramid")) return StructurePreset.DESERT_PYRAMID;
        if (path.contains("jungle_pyramid") || path.contains("jungle_temple")) {
            return StructurePreset.JUNGLE_PYRAMID;
        }
        if (path.contains("swamp_hut")) return StructurePreset.SWAMP_HUT;
        if (path.contains("igloo")) return StructurePreset.IGLOO;
        if (path.contains("monument")) return StructurePreset.OCEAN_MONUMENT;
        if (path.contains("outpost")) return StructurePreset.OUTPOST;
        if (path.contains("mineshaft")) return StructurePreset.MINESHAFT;
        if (path.contains("ruined_portal")) return StructurePreset.RUINED_PORTAL;
        if (path.contains("shipwreck")) return StructurePreset.SHIPWRECK;
        if (path.contains("ocean_ruin")) return StructurePreset.OCEAN_RUIN;
        if (path.contains("buried_treasure")) return StructurePreset.BURIED_TREASURE;
        if (path.contains("trail_ruins")) return StructurePreset.TRAIL_RUINS;
        if (path.contains("trial_chambers")) return StructurePreset.TRIAL_CHAMBERS;
        if (path.contains("ancient_city")) return StructurePreset.ANCIENT_CITY;
        if (path.contains("stronghold")) return StructurePreset.STRONGHOLD;
        if (path.contains("fortress")) return StructurePreset.NETHER_FORTRESS;
        if (path.contains("bastion")) return StructurePreset.BASTION;
        if (path.contains("nether_fossil")) return StructurePreset.NETHER_FOSSIL;
        if (path.contains("end_city")) return StructurePreset.END_CITY;
        return null;
    }

    static Icon defaultStructureIcon(String id) {
        StructurePreset preset = structurePreset(id);
        return preset == null ? Icon.STAR : preset.defaultIcon;
    }

    static int defaultStructurePrimaryColor(String id) {
        StructurePreset preset = structurePreset(id);
        return preset == null ? DEFAULT_PRIMARY_COLOR : preset.defaultPrimary;
    }

    static int defaultStructureColor(String id) {
        StructurePreset preset = structurePreset(id);
        return preset == null ? DEFAULT_ACCENT_COLOR : preset.defaultAccent;
    }

    static int defaultStructureLineColor(String id) {
        StructurePreset preset = structurePreset(id);
        return preset == null ? DEFAULT_LINE_COLOR : preset.defaultLine;
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
