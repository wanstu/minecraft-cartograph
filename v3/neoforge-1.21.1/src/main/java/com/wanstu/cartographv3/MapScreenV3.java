package com.wanstu.cartographv3;

import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

public final class MapScreenV3 extends Screen {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.screen");

    private static final int MENU_W = 24;
    private static final int MENU_H = 20;
    private static final int MENU_MARGIN = 6;
    private static final int MENU_ITEM_W = 46;
    private static final int MENU_ITEM_GAP = 2;
    private static final int MENU_DROP_W = 72;
    private static final int MENU_DROP_H = 20;

    private static final int SETTINGS_TAB_W = 118;

    private static final int CONTEXT_W = 152;
    private static final int CONTEXT_H = 24;

    private static final int HUD_MARGIN = 6;
    private static final int ZOOM_HUD_W = 82;
    private static final int ZOOM_HUD_H = 16;
    private static final int LOCATE_HUD_SIZE = 12;

    private static final int MIN_PENDING_REQUESTS = 32;

    private static final float Z_MAP_MARKERS = 80.0f;
    private static final float Z_PLAYER = 100.0f;
    private static final float Z_HUD = 200.0f;
    private static final float Z_MENU = 300.0f;
    private static final float Z_CONTEXT = 340.0f;
    private static final float Z_SETTINGS = 400.0f;
    private static final float Z_MARKER_OVERLAY = 500.0f;

    private static final double[] ZOOM_LEVELS = {
            0.0625, 0.0833333333, 0.1, 0.125, 0.1666666667, 0.2, 0.25, 0.3333333333,
            0.5, 0.625, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0,
            4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0,
            32.0, 40.0, 48.0, 64.0
    };

    private enum SettingsTab { PLAYER, STRUCTURE, MAP, CONTROL }
    private enum MenuSection { NONE, MARKERS, LAYERS }

    private double centerX;
    private double centerZ;
    private double zoom = 2.0;
    private boolean dragging;
    private boolean initialized;
    private final MapRegionTextureCache regionTextures = new MapRegionTextureCache();

    private int lastRequestMinChunkX = Integer.MIN_VALUE;
    private int lastRequestMaxChunkX = Integer.MIN_VALUE;
    private int lastRequestMinChunkZ = Integer.MIN_VALUE;
    private int lastRequestMaxChunkZ = Integer.MIN_VALUE;
    private int requestCenterChunkX = Integer.MIN_VALUE;
    private int requestCenterChunkZ = Integer.MIN_VALUE;
    private int requestRing;
    private int requestRingOffset;
    private int requestTicks;

    private boolean menuExpanded;
    private MenuSection menuSection = MenuSection.NONE;
    private boolean settingsOpen;
    private boolean contextOpen;
    private int contextX;
    private int contextY;
    private int contextBlockX;
    private int contextBlockY;
    private int contextBlockZ;
    private MapMarkerStore.Marker contextMarker;

    private final MapLayerPanelUi layerPanelUi = new MapLayerPanelUi();
    private final MapHifiSettingsUi hifiSettingsUi = new MapHifiSettingsUi();
    private boolean aboutOpen;

    private final MapUiControls.PlayerMarkerTypeSelect markerTypeSelect;
    private final MapUiControls.ColorInput markerPrimaryColorInput;
    private final MapUiControls.ColorInput markerColorInput;
    private final MapUiControls.ColorInput markerLineColorInput;
    private final MapUiControls.Slider markerSizeSlider;
    private final EnumMap<MapMarkerStore.StructurePreset, MapUiControls.MarkerIconSelect>
            structurePresetSelects = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private final EnumMap<MapMarkerStore.StructurePreset, MapUiControls.ColorInput>
            structurePrimaryInputs = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private final EnumMap<MapMarkerStore.StructurePreset, MapUiControls.ColorInput>
            structureAccentInputs = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private final EnumMap<MapMarkerStore.StructurePreset, MapUiControls.ColorInput>
            structureLineInputs = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private final MapUiControls.Table structureResetTable;
    private int structureSettingsScroll;

    private final MapUiControls.Table mapContextMenuTable;
    private final MapUiControls.Table markerContextMenuTable;

    private final MapUiControls.Slider requestBatchSlider;
    private final MapUiControls.Slider liveScanIntervalSlider;
    private final MapUiControls.P requestBatchHelp;
    private final MapUiControls.P liveScanHelp;

    private final MapUiControls.Slider zoomSlider;
    private final MapUiControls.Span zoomLabel;
    private final MapUiControls.Table zoomHudTable;
    private final MapUiControls.Table locateHudTable;

    private final MapUiControls.Span playerTabLabel;
    private final MapUiControls.Span structureTabLabel;
    private final MapUiControls.Span mapTabLabel;
    private final MapUiControls.Span controlTabLabel;
    private final MapUiControls.Table settingsTabsTable;
    private final MapUiControls.P emptyTabText;
    private final MapUiControls.Icon settingsCloseIcon;
    private final MapMarkerUi markerUi = new MapMarkerUi();
    private SettingsTab settingsTab = SettingsTab.PLAYER;

    public MapScreenV3() {
        super(Component.literal("Cartograph V3"));
        markerTypeSelect = new MapUiControls.PlayerMarkerTypeSelect(
                110, MapUiSettings.markerType(), MapUiSettings::setMarkerType);
        markerPrimaryColorInput = new MapUiControls.ColorInput(
                110, MapUiSettings.markerPrimaryColor(), MapUiSettings::setMarkerPrimaryColor);
        markerColorInput = new MapUiControls.ColorInput(
                110, MapUiSettings.markerColor(), MapUiSettings::setMarkerColor);
        markerLineColorInput = new MapUiControls.ColorInput(
                110, MapUiSettings.markerLineColor(), MapUiSettings::setMarkerLineColor);
        markerSizeSlider = new MapUiControls.Slider(
                126, 6, 48, MapUiSettings.markerSize(), MapUiSettings::setMarkerSize);
        for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
            structurePresetSelects.put(
                    preset,
                    new MapUiControls.MarkerIconSelect(
                            180,
                            MapUiSettings.structurePresetIcon(preset),
                            icon -> {
                                MapUiSettings.setStructurePresetIcon(preset, icon);
                                MapMarkerStore.applyStructurePreset(preset);
                            }));
            MapUiControls.ColorInput primary = new MapUiControls.ColorInput(
                    24,
                    MapUiSettings.structurePresetPrimaryColor(preset),
                    color -> {
                        MapUiSettings.setStructurePresetPrimaryColor(preset, color);
                        MapMarkerStore.applyStructurePreset(preset);
                    });
            primary.setCompact(true);
            structurePrimaryInputs.put(preset, primary);

            MapUiControls.ColorInput accent = new MapUiControls.ColorInput(
                    24,
                    MapUiSettings.structurePresetAccentColor(preset),
                    color -> {
                        MapUiSettings.setStructurePresetAccentColor(preset, color);
                        MapMarkerStore.applyStructurePreset(preset);
                    });
            accent.setCompact(true);
            structureAccentInputs.put(preset, accent);

            MapUiControls.ColorInput line = new MapUiControls.ColorInput(
                    24,
                    MapUiSettings.structurePresetLineColor(preset),
                    color -> {
                        MapUiSettings.setStructurePresetLineColor(preset, color);
                        MapMarkerStore.applyStructurePreset(preset);
                    });
            line.setCompact(true);
            structureLineInputs.put(preset, line);
        }
        var resetStructureLabel = new MapUiControls.Span("恢复默认");
        resetStructureLabel.setCentered(true);
        structureResetTable = new MapUiControls.Table(70)
                .background(0xff303840)
                .hoverBackground(0xff414b55)
                .border(0xff48535d)
                .padding(4);
        structureResetTable.addRow(new MapUiControls.Table.Row(
                20, true, this::resetStructurePresets, resetStructureLabel));

        var addMarkerIcon = new MapUiControls.Icon(12, MapUiControls.IconType.ADD);
        var addMarkerLabel = new MapUiControls.Span("增加标点");
        var teleportIcon = new MapUiControls.Icon(12, MapUiControls.IconType.TELEPORT);
        var teleportLabel = new MapUiControls.Span("传送");
        mapContextMenuTable = new MapUiControls.Table(CONTEXT_W)
                .background(0xf21f242a)
                .hoverBackground(0xff343c45)
                .padding(7)
                .columnGap(5);
        mapContextMenuTable.addRow(new MapUiControls.Table.Row(
                CONTEXT_H, true, this::addContextMarker, addMarkerIcon, addMarkerLabel));
        mapContextMenuTable.addRow(new MapUiControls.Table.Row(
                CONTEXT_H, true, this::teleportToContext, teleportIcon, teleportLabel));

        var markerTeleportIcon = new MapUiControls.Icon(12, MapUiControls.IconType.TELEPORT);
        var markerTeleportLabel = new MapUiControls.Span("传送");
        var markerEditIcon = new MapUiControls.Icon(12, MapUiControls.IconType.EDIT);
        var markerEditLabel = new MapUiControls.Span("编辑");
        var markerDeleteIcon = new MapUiControls.Icon(12, MapUiControls.IconType.TRASH);
        var markerDeleteLabel = new MapUiControls.Span("删除");
        markerContextMenuTable = new MapUiControls.Table(CONTEXT_W)
                .background(0xf21f242a)
                .hoverBackground(0xff343c45)
                .padding(7)
                .columnGap(5);
        markerContextMenuTable.addRow(new MapUiControls.Table.Row(
                CONTEXT_H, true, this::teleportToContext, markerTeleportIcon, markerTeleportLabel));
        markerContextMenuTable.addRow(new MapUiControls.Table.Row(
                CONTEXT_H, true, this::editContextMarker, markerEditIcon, markerEditLabel));
        markerContextMenuTable.addRow(new MapUiControls.Table.Row(
                CONTEXT_H, true, this::deleteContextMarker, markerDeleteIcon, markerDeleteLabel));

        requestBatchSlider = new MapUiControls.Slider(
                180, 8, 256, MapUiSettings.requestBatchSize(), MapUiSettings::setRequestBatchSize);
        liveScanIntervalSlider = new MapUiControls.Slider(
                180, 5, 100, MapUiSettings.liveScanIntervalTicks(), value -> {
                    MapUiSettings.setLiveScanIntervalTicks(value);
                    if (initialized) {
                        PacketDistributor.sendToServer(new MapProtocol.MapViewState(true, value));
                    }
                });
        requestBatchHelp = new MapUiControls.P(
                420, 42,
                "控制客户端一次预取多少区块。数值越大，远处空白补全越积极；服务端会再按独立安全预算分批处理，不会把 256 个请求塞进同一个 tick。默认 64；通常建议 32～64。");
        requestBatchHelp.setColor(0xff8f989f);
        liveScanHelp = new MapUiControls.P(
                420, 42,
                "控制实时地表变化检查的平均频率。数值越小更新越及时；扫描会自动平摊到多个 server tick、每 tick 限量执行，避免集中扫描拖慢实体/TNT。默认 10 tick（0.5 秒）。");
        liveScanHelp.setColor(0xff8f989f);

        zoomLabel = new MapUiControls.Span(formatZoom(zoom));
        zoomLabel.setCentered(true);
        zoomSlider = new MapUiControls.Slider(
                46, 0, ZOOM_LEVELS.length - 1, nearestZoomIndex(zoom),
                this::setZoomFromSlider);
        zoomHudTable = new MapUiControls.Table(ZOOM_HUD_W)
                .background(0xd92b3036)
                .hoverBackground(0xd92b3036)
                .border(0xff3d464f)
                .padding(3)
                .columnGap(3);
        zoomHudTable.addRow(new MapUiControls.Table.Row(
                ZOOM_HUD_H, false, () -> {}, zoomSlider, zoomLabel));

        var locateIcon = new MapUiControls.Icon(8, MapUiControls.IconType.LOCATE);
        locateHudTable = new MapUiControls.Table(LOCATE_HUD_SIZE)
                .background(0xd92b3036)
                .hoverBackground(0xff414a53)
                .border(0xff3d464f)
                .padding(1);
        locateHudTable.addRow(new MapUiControls.Table.Row(
                LOCATE_HUD_SIZE, true, this::centerOnPlayer, locateIcon));

        playerTabLabel = new MapUiControls.Span("玩家");
        structureTabLabel = new MapUiControls.Span("结构");
        mapTabLabel = new MapUiControls.Span("地图");
        controlTabLabel = new MapUiControls.Span("操作");
        settingsTabsTable = new MapUiControls.Table(SETTINGS_TAB_W - 12)
                .background(0xff20262c)
                .hoverBackground(0xff303840)
                .padding(10);
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                34, true, () -> setSettingsTab(SettingsTab.PLAYER), playerTabLabel));
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                34, true, () -> setSettingsTab(SettingsTab.STRUCTURE), structureTabLabel));
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                34, true, () -> setSettingsTab(SettingsTab.MAP), mapTabLabel));
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                34, true, () -> setSettingsTab(SettingsTab.CONTROL), controlTabLabel));

        emptyTabText = new MapUiControls.P(220, 70, "暂无可配置项");
        emptyTabText.setColor(0xff8f989f);
        settingsCloseIcon = new MapUiControls.Icon(16, MapUiControls.IconType.CLOSE);
    }

    @Override
    protected void init() {
        MapClientCache.ensureWorld(minecraft.level);
        PacketDistributor.sendToServer(new MapProtocol.MapViewState(
                true, MapUiSettings.liveScanIntervalTicks()));
        if (!initialized) {
            MapClientCache.beginMapSession();
            if (minecraft.player != null) {
                centerX = minecraft.player.getX();
                centerZ = minecraft.player.getZ();
            }
            initialized = true;
            LOG.info("V3_MAP_OPEN dimension={} center={},{} zoom={}",
                    dimension(), Math.floor(centerX), Math.floor(centerZ), zoom);
        }
        requestVisible(true);
    }

    @Override
    public void tick() {
        requestTicks++;
        int pendingLimit = maxPendingRequests();
        if (!dragging && MapClientCache.pendingCount() < pendingLimit) {
            requestVisible(requestTicks % 5 == 0);
        }
    }

    private String dimension() {
        return minecraft.level == null ? ""
                : minecraft.level.dimension().location().toString();
    }

    private void requestVisible(boolean force) {
        if (minecraft.level == null || MapClientCache.worldId() == null) return;
        String dim = dimension();

        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minChunkX = Math.floorDiv((int) Math.floor(centerX - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv((int) Math.floor(centerX + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + halfBlocksZ), 16) + 1;
        int centerChunkX = Math.floorDiv((int) Math.floor(centerX), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(centerZ), 16);

        boolean viewportChanged =
                minChunkX != lastRequestMinChunkX
                        || maxChunkX != lastRequestMaxChunkX
                        || minChunkZ != lastRequestMinChunkZ
                        || maxChunkZ != lastRequestMaxChunkZ
                        || centerChunkX != requestCenterChunkX
                        || centerChunkZ != requestCenterChunkZ;

        if (!force && !viewportChanged) return;

        if (viewportChanged) {
            // Disk restore is local-only and deliberately independent from the
            // server request budget. Previously cached map data should appear
            // immediately while server validation continues in the background.
            MapClientCache.restoreVisibleFromDisk(
                    dim, minChunkX, maxChunkX, minChunkZ, maxChunkZ);

            lastRequestMinChunkX = minChunkX;
            lastRequestMaxChunkX = maxChunkX;
            lastRequestMinChunkZ = minChunkZ;
            lastRequestMaxChunkZ = maxChunkZ;
            requestCenterChunkX = centerChunkX;
            requestCenterChunkZ = centerChunkZ;
            requestRing = 0;
            requestRingOffset = 0;
        }

        int maxRadius = Math.max(
                Math.max(Math.abs(minChunkX - centerChunkX), Math.abs(maxChunkX - centerChunkX)),
                Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ)));
        int requestBatch = MapUiSettings.requestBatchSize();
        int requestBudget = Math.min(
                requestBatch,
                Math.max(0, maxPendingRequests() - MapClientCache.pendingCount()));
        if (requestBudget <= 0) return;

        int requested = 0;
        int scanned = 0;
        int scanBudget = requestBatch * 8;

        while (requested < requestBudget
                && scanned < scanBudget
                && requestRing <= maxRadius) {
            int dx;
            int dz;

            if (requestRing == 0) {
                dx = 0;
                dz = 0;
                requestRing = 1;
                requestRingOffset = 0;
            } else {
                int r = requestRing;
                int side = r * 2;
                int perimeter = r * 8;
                int p = requestRingOffset++;

                if (p < side) {
                    dx = -r + p;
                    dz = -r;
                } else if (p < side * 2) {
                    dx = r;
                    dz = -r + (p - side);
                } else if (p < side * 3) {
                    dx = r - (p - side * 2);
                    dz = r;
                } else {
                    dx = -r;
                    dz = r - (p - side * 3);
                }

                if (requestRingOffset >= perimeter) {
                    requestRing++;
                    requestRingOffset = 0;
                }
            }

            scanned++;
            int x = centerChunkX + dx;
            int z = centerChunkZ + dz;
            if (x < minChunkX || x > maxChunkX || z < minChunkZ || z > maxChunkZ) continue;

            ChunkKey key = new ChunkKey(dim, x, z);
            if (!MapClientCache.needsRequest(key)) continue;
            MapClientCache.request(key);
            requested++;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xff0f171b);
        if (minecraft.level != null) {
            regionTextures.render(
                    graphics, dimension(), centerX, centerZ, zoom, width, height);
            if (MapUiSettings.grid()) {
                withLayer(graphics, Z_MAP_MARKERS - 4.0f,
                        () -> renderChunkGrid(graphics));
            }
            if (MapUiSettings.markerLayerEnabled() || MapUiSettings.structureLayerEnabled()) {
                withLayer(graphics, Z_MAP_MARKERS, () ->
                        markerUi.renderLayer(
                                graphics, font, dimension(), centerX, centerZ, zoom,
                                width, height, mouseX, mouseY,
                                MapUiSettings.markerLayerEnabled(),
                                MapUiSettings.structureLayerEnabled()));
            }
            withLayer(graphics, Z_PLAYER, () -> renderPlayerMarker(graphics));
        }

        int uiWidth = pixelUiWidth();
        int uiHeight = pixelUiHeight();
        int uiMouseX = pixelUiX(mouseX);
        int uiMouseY = pixelUiY(mouseY);

        withLayer(graphics, Z_HUD, () ->
                withPixelUi(graphics, () -> renderHifiHud(graphics, uiMouseX, uiMouseY)));
        withLayer(graphics, Z_MENU, () ->
                withPixelUi(graphics, () -> renderHifiTopbar(graphics, uiMouseX, uiMouseY)));

        if (markerUi.managerOpen()) {
            withLayer(graphics, Z_MENU + 10.0f, () ->
                    withPixelUi(graphics, () ->
                            markerUi.renderManagerPanel(
                                    graphics, font, uiWidth, uiHeight, uiMouseX, uiMouseY)));
        }
        if (layerPanelUi.open()) {
            withLayer(graphics, Z_MENU + 10.0f, () ->
                    withPixelUi(graphics, () ->
                            layerPanelUi.render(
                                    graphics, font, dimension(),
                                    uiWidth, uiHeight, uiMouseX, uiMouseY)));
        }

        if (contextOpen) {
            withLayer(graphics, Z_CONTEXT, () -> renderContextMenu(graphics, mouseX, mouseY));
        }
        if (aboutOpen) {
            withLayer(graphics, Z_SETTINGS, () ->
                    withPixelUi(graphics, () -> renderAbout(graphics, uiMouseX, uiMouseY)));
        }
        if (hifiSettingsUi.isOpen()) {
            withLayer(graphics, Z_SETTINGS, () ->
                    withPixelUi(graphics, () ->
                            hifiSettingsUi.render(
                                    graphics, font,
                                    uiWidth, uiHeight, uiMouseX, uiMouseY)));
        }
        if (markerUi.overlayOpen()) {
            withLayer(graphics, Z_MARKER_OVERLAY, () ->
                    withPixelUi(graphics, () ->
                            markerUi.renderOverlay(
                                    graphics, font,
                                    uiWidth, uiHeight, uiMouseX, uiMouseY)));
        }
    }

    private int pixelUiWidth() {
        return MapHifiUi.pixelWidth(width);
    }

    private int pixelUiHeight() {
        return MapHifiUi.pixelHeight(height);
    }

    private int pixelUiX(double mouseX) {
        return MapHifiUi.toPixel(mouseX);
    }

    private int pixelUiY(double mouseY) {
        return MapHifiUi.toPixel(mouseY);
    }

    private static void withPixelUi(GuiGraphics graphics, Runnable draw) {
        MapHifiUi.beginPixelSpace(graphics);
        try {
            draw.run();
        } finally {
            MapHifiUi.endPixelSpace(graphics);
        }
    }

    private int navItemWidth() {
        return pixelUiWidth() < 900 ? 70 : 88;
    }

    private int navStartX() {
        int itemW = navItemWidth();
        return Math.max(4, pixelUiWidth() - 5 * itemW - 72);
    }

    private int navX(int index) {
        return navStartX() + index * navItemWidth();
    }

    private boolean topbarContains(double mouseX, double mouseY) {
        return mouseY >= 0 && mouseY < MapHifiUi.headerGui();
    }

    private void renderHifiTopbar(
            GuiGraphics graphics, int mouseX, int mouseY) {
        int uiW = pixelUiWidth();
        MapHifiUi.topbar(graphics, uiW);

        int navStart = navStartX();
        if (navStart > 190) {
            MapIconAtlas.drawUi(
                    graphics, MapIconAtlas.UiIcon.MAP,
                    37, MapHifiUi.HEADER_H / 2, 29, 0xffeef2f4);
            MapHifiUi.text(graphics, font, "地图册", 63, 20, 0xffffffff, 21);
        }
        if (navStart > 360) {
            MapHifiUi.text(
                    graphics, font, "Map Atlas · V3",
                    140, 24, 0xff98a4ac, 13);
        }

        int statusX = navStart > 620 ? 285 : 155;
        int maxStatus = navStart - statusX - 18;
        if (maxStatus > 120) {
            List<MapMarkerStore.Marker> entries = MapMarkerStore.visible(dimension());
            int custom = 0;
            int structures = 0;
            for (MapMarkerStore.Marker marker : entries) {
                if (marker.origin == MapMarkerStore.Origin.AUTO && !marker.modifiedAuto) {
                    structures++;
                } else {
                    custom++;
                }
            }
            String status = "卫星 · 写实   ·   结构 " + structures + " · 标点 " + custom;
            if (MapHifiUi.textWidth(font, status, 14) > maxStatus) {
                status = "结构 " + structures + " · 标点 " + custom;
            }
            MapHifiUi.text(graphics, font, status, statusX, 24, 0xffaeb9bf, 14);
        }

        MapHifiUi.nav(
                graphics, font, navX(0), 0, navItemWidth(), MapHifiUi.HEADER_H,
                MapIconAtlas.UiIcon.MAP, "地图",
                !markerUi.managerOpen() && !layerPanelUi.open()
                        && !hifiSettingsUi.isOpen() && !aboutOpen,
                mouseX, mouseY);
        MapHifiUi.nav(
                graphics, font, navX(1), 0, navItemWidth(), MapHifiUi.HEADER_H,
                MapIconAtlas.UiIcon.MARKERS, "标点",
                markerUi.managerOpen(), mouseX, mouseY);
        MapHifiUi.nav(
                graphics, font, navX(2), 0, navItemWidth(), MapHifiUi.HEADER_H,
                MapIconAtlas.UiIcon.LAYERS, "图层",
                layerPanelUi.open(), mouseX, mouseY);
        MapHifiUi.nav(
                graphics, font, navX(3), 0, navItemWidth(), MapHifiUi.HEADER_H,
                MapIconAtlas.UiIcon.SETTINGS, "设置",
                hifiSettingsUi.isOpen(), mouseX, mouseY);
        MapHifiUi.nav(
                graphics, font, navX(4), 0, navItemWidth(), MapHifiUi.HEADER_H,
                MapIconAtlas.UiIcon.ABOUT, "关于",
                aboutOpen, mouseX, mouseY);

        int closeX = uiW - 64;
        boolean closeHover = inside(mouseX, mouseY, closeX, 10, 42, 42);
        if (closeHover) graphics.fill(closeX, 10, closeX + 42, 52, 0xff28343b);
        MapIconAtlas.drawUi(
                graphics, MapIconAtlas.UiIcon.CLOSE,
                closeX + 21, 31, 23,
                closeHover ? 0xffffffff : 0xffd9e0e4);
    }

    private void renderChunkGrid(GuiGraphics graphics) {
        double spacing = 16.0 * zoom;
        if (spacing < 4.0) return;

        double halfX = width / (2.0 * zoom);
        double halfZ = height / (2.0 * zoom);
        int minBlockX = (int) Math.floor((centerX - halfX) / 16.0) * 16;
        int maxBlockX = (int) Math.ceil((centerX + halfX) / 16.0) * 16;
        int minBlockZ = (int) Math.floor((centerZ - halfZ) / 16.0) * 16;
        int maxBlockZ = (int) Math.ceil((centerZ + halfZ) / 16.0) * 16;
        int color = 0x38e7eef2;

        int guard = 0;
        for (int bx = minBlockX; bx <= maxBlockX && guard++ < 2000; bx += 16) {
            int sx = (int) Math.round(width * 0.5 + (bx - centerX) * zoom);
            graphics.fill(sx, MapHifiUi.headerGui(), sx + 1, height, color);
        }
        guard = 0;
        for (int bz = minBlockZ; bz <= maxBlockZ && guard++ < 2000; bz += 16) {
            int sy = (int) Math.round(height * 0.5 + (bz - centerZ) * zoom);
            if (sy < MapHifiUi.headerGui()) continue;
            graphics.fill(0, sy, width, sy + 1, color);
        }
    }

    private void renderHifiHud(GuiGraphics graphics, int mouseX, int mouseY) {
        int uiW = pixelUiWidth();
        int uiH = pixelUiHeight();

        int zoomW = 166;
        int zoomH = 44;
        int zoomX = uiW - 18 - zoomW;
        int zoomY = uiH - 20 - zoomH;
        int locateX = uiW - 18 - 44;
        int locateY = zoomY - 7 - 44;

        MapHifiUi.panel(graphics, zoomX, zoomY, zoomW, zoomH);
        MapHifiUi.button(
                graphics, font, zoomX, zoomY, 44, zoomH,
                "−", false, false, mouseX, mouseY);
        MapHifiUi.centeredText(
                graphics, font, formatZoom(zoom),
                zoomX + zoomW / 2, zoomY + 13, 0xffeef2f4, 17);
        MapHifiUi.button(
                graphics, font, zoomX + zoomW - 44, zoomY, 44, zoomH,
                "+", false, false, mouseX, mouseY);

        MapHifiUi.panel(graphics, locateX, locateY, 44, 44);
        MapIconAtlas.drawUi(
                graphics, MapIconAtlas.UiIcon.LOCATE,
                locateX + 22, locateY + 22, 22,
                inside(mouseX, mouseY, locateX, locateY, 44, 44)
                        ? 0xffffffff : 0xffdce4e8);

        if (MapUiSettings.showCoords() && mouseY >= MapHifiUi.HEADER_H) {
            double guiMouseX = MapHifiUi.toGuiD(mouseX);
            double guiMouseY = MapHifiUi.toGuiD(mouseY);
            int bx = (int) Math.floor(centerX + (guiMouseX - width * 0.5) / zoom);
            int bz = (int) Math.floor(centerZ + (guiMouseY - height * 0.5) / zoom);
            int by = surfaceYAt(bx, bz);
            String coords = "X " + bx + "   Y " + by + "   Z " + bz;
            String biome = biomeLabelAt(bx, bz);
            int panelW = Math.max(220, Math.max(
                    MapHifiUi.textWidth(font, coords, 12),
                    MapHifiUi.textWidth(font, biome, 12)) + 24);
            int panelH = 58;
            int panelY = uiH - 82 - panelH;
            MapHifiUi.panel(graphics, 18, panelY, panelW, panelH);
            MapHifiUi.text(
                    graphics, font, coords,
                    30, panelY + 10, 0xffeef2f4, 12);
            MapHifiUi.text(
                    graphics, font, biome,
                    30, panelY + 31, 0xffaeb9bf, 12);
        }

        int scaleLineW = 145;
        int scaleBoxW = scaleLineW + 24;
        int scaleBoxH = 38;
        int scaleX = 18;
        int scaleY = uiH - 20 - scaleBoxH;
        MapHifiUi.panel(graphics, scaleX, scaleY, scaleBoxW, scaleBoxH);
        int worldBlocks = Math.max(
                1,
                (int) Math.round(
                        MapHifiUi.toGuiD(scaleLineW) / Math.max(0.0001, zoom)));
        String scale = worldBlocks >= 1000
                ? String.format(java.util.Locale.ROOT, "%.1f km", worldBlocks / 1000.0)
                : worldBlocks + " m";
        MapHifiUi.centeredText(
                graphics, font, scale,
                scaleX + scaleBoxW / 2, scaleY + 8, 0xffdce4e8, 12);
        graphics.fill(
                scaleX + 12, scaleY + 27,
                scaleX + 12 + scaleLineW, scaleY + 29,
                0xfff1f3f4);
        graphics.fill(scaleX + 12, scaleY + 21, scaleX + 14, scaleY + 29, 0xfff1f3f4);
        graphics.fill(
                scaleX + 10 + scaleLineW, scaleY + 21,
                scaleX + 12 + scaleLineW, scaleY + 29,
                0xfff1f3f4);
    }

    private int surfaceYAt(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        SurfaceTile tile = MapClientCache.tile(new ChunkKey(dimension(), chunkX, chunkZ));
        if (tile != null && tile.hasSurfaceFacts()) {
            int localX = Math.floorMod(blockX, 16);
            int localZ = Math.floorMod(blockZ, 16);
            int value = tile.surfaceHeightAt(localZ * 16 + localX);
            if (value != SurfaceTile.UNKNOWN_HEIGHT) return value;
        }
        return minecraft.player == null ? 64 : minecraft.player.getBlockY();
    }

    private String biomeLabelAt(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        SurfaceTile tile = MapClientCache.tile(new ChunkKey(dimension(), chunkX, chunkZ));
        if (tile == null || !tile.hasSurfaceFacts()) return "群系：未加载";
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        int biomeId = tile.biomeIdAt(localZ * 16 + localX);
        if (biomeId < 0 || minecraft.level == null) return "群系：未加载";
        try {
            var registry = minecraft.level.registryAccess().registryOrThrow(
                    net.minecraft.core.registries.Registries.BIOME);
            var biome = registry.byId(biomeId);
            if (biome == null) return "群系 #" + biomeId;
            var key = registry.getKey(biome);
            if (key == null) return "群系 #" + biomeId;
            String translated = Component.translatable(
                    "biome." + key.getNamespace() + "." + key.getPath()).getString();
            return "群系：" + translated;
        } catch (RuntimeException ignored) {
            return "群系 #" + biomeId;
        }
    }

    private void renderAbout(
            GuiGraphics graphics, int mouseX, int mouseY) {
        int uiW = pixelUiWidth();
        int uiH = pixelUiHeight();
        int w = Math.min(520, uiW - 36);
        int h = Math.min(270, uiH - MapHifiUi.HEADER_H - 30);
        int x = (uiW - w) / 2;
        int y = MapHifiUi.HEADER_H
                + Math.max(10, (uiH - MapHifiUi.HEADER_H - h) / 2);
        MapHifiUi.modalShade(graphics, uiW, uiH);
        MapHifiUi.modal(graphics, x, y, w, h);
        MapHifiUi.modalHeader(
                graphics, font, x, y, w,
                MapIconAtlas.UiIcon.ABOUT, "关于", mouseX, mouseY);
        MapIconAtlas.drawUi(
                graphics, MapIconAtlas.UiIcon.MAP,
                x + 42, y + 86, 36, 0xffeef2f4);
        MapHifiUi.text(graphics, font, "地图册 · Map Atlas",
                x + 72, y + 69, 0xffffffff, 18);
        MapHifiUi.text(graphics, font, "Cartograph V3",
                x + 72, y + 91, MapHifiUi.MUTED, 12);
        MapHifiUi.divider(graphics, x + 18, y + 118, x + w - 18);
        MapHifiUi.text(graphics, font, "地图事实", x + 24, y + 139, MapHifiUi.MUTED, 13);
        MapHifiUi.text(graphics, font, "仅使用真实已生成区块",
                x + 145, y + 139, MapHifiUi.TEXT, 13);
        MapHifiUi.text(graphics, font, "当前地图风格",
                x + 24, y + 167, MapHifiUi.MUTED, 13);
        MapHifiUi.text(graphics, font, "卫星 · 写实",
                x + 145, y + 167, MapHifiUi.TEXT, 13);
        MapHifiUi.text(graphics, font, "手绘风格",
                x + 24, y + 195, MapHifiUi.MUTED, 13);
        MapHifiUi.text(graphics, font, "现代 / 古式已预留，暂未实现",
                x + 145, y + 195, MapHifiUi.TEXT, 13);
        MapHifiUi.text(graphics, font, "高保真原型继续保留，游戏内 UI 按原型实现。",
                x + 24, y + h - 34, 0xffaeb9bf, 12);
    }

    private void renderPlayerMarker(GuiGraphics graphics) {
        if (minecraft.player == null) return;

        double sx = width * 0.5 + (minecraft.player.getX() - centerX) * zoom;
        double sy = height * 0.5 + (minecraft.player.getZ() - centerZ) * zoom;
        int size = MapUiSettings.markerSize();
        if (sx < -size || sy < -size || sx > width + size || sy > height + size) return;

        int primary = MapUiSettings.markerPrimaryColor();
        int accent = MapUiSettings.markerColor();
        int line = MapUiSettings.markerLineColor();
        int x = (int) Math.round(sx);
        int y = (int) Math.round(sy);

        if (MapUiSettings.playerRing()) {
            int radius = Math.max(18, size * 2);
            int color = 0x55ffffff;
            for (int degree = 0; degree < 360; degree += 8) {
                double a = Math.toRadians(degree);
                int px = x + (int) Math.round(Math.cos(a) * radius);
                int py = y + (int) Math.round(Math.sin(a) * radius);
                graphics.fill(px, py, px + 1, py + 1, color);
            }
        }

        if (MapUiSettings.markerType() == MapUiSettings.MarkerType.CROSS) {
            MapIconAtlas.drawPlayerCross(
                    graphics, x, y, Math.max(8, size),
                    primary, accent, line);
            return;
        }
        if (MapUiSettings.markerType() == MapUiSettings.MarkerType.DOT) {
            int r = Math.max(2, size / 4);
            graphics.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2,
                    0xff000000 | line);
            graphics.fill(x - r, y - r, x + r + 1, y + r + 1,
                    0xff000000 | accent);
            return;
        }

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0f + minecraft.player.getYRot()));
        MapIconAtlas.drawPlayerArrow(
                graphics, 0, 0, Math.max(8, size),
                primary, accent, line);
        pose.popPose();
    }

    private void renderBottomHud(GuiGraphics graphics, int mouseX, int mouseY) {
        layoutBottomHud();
        zoomSlider.setValue(nearestZoomIndex(zoom));
        zoomLabel.setText(formatZoom(zoom));
        zoomHudTable.render(graphics, font, mouseX, mouseY);
        locateHudTable.render(graphics, font, mouseX, mouseY);
    }

    private void layoutBottomHud() {
        int zoomX = width - HUD_MARGIN - ZOOM_HUD_W;
        int zoomY = height - HUD_MARGIN - ZOOM_HUD_H;
        zoomHudTable.setBounds(zoomX, zoomY, ZOOM_HUD_W, ZOOM_HUD_H);

        int locateX = width - HUD_MARGIN - LOCATE_HUD_SIZE;
        int locateY = zoomY - 6 - LOCATE_HUD_SIZE;
        locateHudTable.setBounds(locateX, locateY, LOCATE_HUD_SIZE, LOCATE_HUD_SIZE);
    }

    private void renderTopMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int menuX = width - MENU_MARGIN - MENU_W;
        int menuY = MENU_MARGIN;

        if (!menuExpanded) {
            drawMenuButton(
                    graphics, menuX, menuY, MENU_W, MENU_H,
                    "", MapIconAtlas.UiIcon.MENU, false, mouseX, mouseY);
            return;
        }

        int markersX = menuBarLeft();
        int layersX = markersX + MENU_ITEM_W + MENU_ITEM_GAP;
        int settingsX = layersX + MENU_ITEM_W + MENU_ITEM_GAP;

        drawMenuButton(
                graphics, markersX, menuY, MENU_ITEM_W, MENU_H,
                "标点", MapIconAtlas.UiIcon.MARKERS,
                menuSection == MenuSection.MARKERS, mouseX, mouseY);
        drawMenuButton(
                graphics, layersX, menuY, MENU_ITEM_W, MENU_H,
                "图层", MapIconAtlas.UiIcon.LAYERS,
                menuSection == MenuSection.LAYERS, mouseX, mouseY);
        drawMenuButton(
                graphics, settingsX, menuY, MENU_ITEM_W, MENU_H,
                "设置", MapIconAtlas.UiIcon.SETTINGS,
                false, mouseX, mouseY);
        drawMenuButton(
                graphics, menuX, menuY, MENU_W, MENU_H,
                "", MapIconAtlas.UiIcon.MENU, true, mouseX, mouseY);

        if (menuSection == MenuSection.NONE) return;

        int dropX = menuSection == MenuSection.MARKERS ? markersX : layersX;
        dropX = Math.max(MENU_MARGIN, Math.min(width - MENU_MARGIN - MENU_DROP_W, dropX));
        int dropY = menuY + MENU_H + 2;

        if (menuSection == MenuSection.MARKERS) {
            drawMenuButton(
                    graphics, dropX, dropY, MENU_DROP_W, MENU_DROP_H,
                    "标点管理", MapIconAtlas.UiIcon.MARKERS,
                    false, mouseX, mouseY);
            return;
        }

        drawMenuButton(
                graphics, dropX, dropY, MENU_DROP_W, MENU_DROP_H,
                MapUiSettings.markerLayerEnabled() ? "标点  ✓" : "标点",
                MapIconAtlas.UiIcon.MARKERS,
                MapUiSettings.markerLayerEnabled(), mouseX, mouseY);
        drawMenuButton(
                graphics, dropX, dropY + MENU_DROP_H, MENU_DROP_W, MENU_DROP_H,
                MapUiSettings.structureLayerEnabled() ? "结构  ✓" : "结构",
                MapIconAtlas.UiIcon.LAYERS,
                MapUiSettings.structureLayerEnabled(), mouseX, mouseY);
    }

    private int menuBarLeft() {
        int menuX = width - MENU_MARGIN - MENU_W;
        return menuX - MENU_ITEM_GAP - MENU_ITEM_W * 3 - MENU_ITEM_GAP * 2;
    }

    private boolean insideTopMenu(double mouseX, double mouseY) {
        int menuX = width - MENU_MARGIN - MENU_W;
        int menuY = MENU_MARGIN;
        if (inside((int) mouseX, (int) mouseY, menuX, menuY, MENU_W, MENU_H)) return true;
        if (!menuExpanded) return false;

        int left = menuBarLeft();
        int barW = MENU_ITEM_W * 3 + MENU_ITEM_GAP * 3 + MENU_W;
        if (inside((int) mouseX, (int) mouseY, left, menuY, barW, MENU_H)) return true;

        if (menuSection == MenuSection.NONE) return false;
        int dropX = menuSection == MenuSection.MARKERS
                ? left : left + MENU_ITEM_W + MENU_ITEM_GAP;
        dropX = Math.max(MENU_MARGIN, Math.min(width - MENU_MARGIN - MENU_DROP_W, dropX));
        int dropH = menuSection == MenuSection.LAYERS
                ? MENU_DROP_H * 2 : MENU_DROP_H;
        return inside(
                (int) mouseX, (int) mouseY,
                dropX, menuY + MENU_H + 2, MENU_DROP_W, dropH);
    }

    private boolean handleTopMenuClick(int mouseX, int mouseY) {
        int menuX = width - MENU_MARGIN - MENU_W;
        int menuY = MENU_MARGIN;

        if (inside(mouseX, mouseY, menuX, menuY, MENU_W, MENU_H)) {
            menuExpanded = !menuExpanded;
            menuSection = MenuSection.NONE;
            contextOpen = false;
            closeSettings();
            return true;
        }
        if (!menuExpanded) return false;

        int left = menuBarLeft();
        int layersX = left + MENU_ITEM_W + MENU_ITEM_GAP;
        int settingsX = layersX + MENU_ITEM_W + MENU_ITEM_GAP;

        if (inside(mouseX, mouseY, left, menuY, MENU_ITEM_W, MENU_H)) {
            menuSection = menuSection == MenuSection.MARKERS
                    ? MenuSection.NONE : MenuSection.MARKERS;
            return true;
        }
        if (inside(mouseX, mouseY, layersX, menuY, MENU_ITEM_W, MENU_H)) {
            menuSection = menuSection == MenuSection.LAYERS
                    ? MenuSection.NONE : MenuSection.LAYERS;
            return true;
        }
        if (inside(mouseX, mouseY, settingsX, menuY, MENU_ITEM_W, MENU_H)) {
            settingsOpen = true;
            markerUi.closeOverlay();
            menuExpanded = false;
            menuSection = MenuSection.NONE;
            contextOpen = false;
            return true;
        }

        if (menuSection == MenuSection.MARKERS) {
            int dropX = Math.max(
                    MENU_MARGIN, Math.min(width - MENU_MARGIN - MENU_DROP_W, left));
            int dropY = menuY + MENU_H + 2;
            if (inside(mouseX, mouseY, dropX, dropY, MENU_DROP_W, MENU_DROP_H)) {
                markerUi.openManager(width - 260, MapHifiUi.HEADER_H + 6, width, height);
                menuExpanded = false;
                menuSection = MenuSection.NONE;
                contextOpen = false;
                return true;
            }
        } else if (menuSection == MenuSection.LAYERS) {
            int dropX = Math.max(
                    MENU_MARGIN, Math.min(width - MENU_MARGIN - MENU_DROP_W, layersX));
            int dropY = menuY + MENU_H + 2;
            if (inside(mouseX, mouseY, dropX, dropY, MENU_DROP_W, MENU_DROP_H)) {
                MapUiSettings.setMarkerLayerEnabled(!MapUiSettings.markerLayerEnabled());
                return true;
            }
            if (inside(
                    mouseX, mouseY,
                    dropX, dropY + MENU_DROP_H, MENU_DROP_W, MENU_DROP_H)) {
                MapUiSettings.setStructureLayerEnabled(!MapUiSettings.structureLayerEnabled());
                return true;
            }
        }
        return false;
    }

    private static void drawMenuButton(
            GuiGraphics graphics,
            int x, int y, int w, int h,
            String text, MapIconAtlas.UiIcon icon,
            boolean active, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, w, h);
        int background = active
                ? 0xe83a424a
                : hovered ? 0xe8323940 : 0xd921262b;
        graphics.fill(x, y, x + w, y + h, background);
        if (active) graphics.fill(x, y + h - 2, x + w, y + h, 0xffffd166);

        int iconX = text.isEmpty() ? x + w / 2 : x + 9;
        MapIconAtlas.drawUi(
                graphics, icon, iconX, y + h / 2,
                text.isEmpty() ? 13 : 10,
                active ? 0xffffd166 : 0xffe2e7eb);
        if (!text.isEmpty()) {
            graphics.drawString(
                    Minecraft.getInstance().font,
                    text, x + 17, y + Math.max(5, (h - 9) / 2),
                    active ? 0xffffd166 : 0xffe2e7eb, false);
        }
    }

    private void layoutContextMenu() {
        if (contextMarker == null) {
            mapContextMenuTable.setBounds(contextX, contextY, CONTEXT_W, CONTEXT_H * 2);
        } else {
            markerContextMenuTable.setBounds(contextX, contextY, CONTEXT_W, CONTEXT_H * 3);
        }
    }

    private void renderSettings(GuiGraphics graphics, int mouseX, int mouseY) {
        int topH = 38;

        graphics.fill(0, 0, width, height, 0xff181d22);
        graphics.fill(0, 0, width, topH, 0xff2d343c);
        graphics.fill(0, topH, SETTINGS_TAB_W, height, 0xff20262c);
        graphics.fill(SETTINGS_TAB_W, topH, width, height, 0xff22282e);

        graphics.drawString(font, "设置", 16, 14, 0xffffffff, false);
        settingsCloseIcon.setBounds(width - 28, 10, 16, 16);
        settingsCloseIcon.render(graphics, font, mouseX, mouseY);

        layoutSettingsTabs();
        updateSettingsTabStyle();
        settingsTabsTable.render(graphics, font, mouseX, mouseY);
        int activeTabY = 54 + settingsTab.ordinal() * 34;
        graphics.fill(8, activeTabY + 3, 11, activeTabY + 27, 0xffffd166);

        int contentX = SETTINGS_TAB_W + 28;
        int contentY = 58;
        int contentRight = width - 28;

        if (settingsTab == SettingsTab.PLAYER) {
            graphics.drawString(font, "玩家图标", contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 15, contentRight, contentY + 16, 0xff39424a);

            layoutSettingsControls();
            markerTypeSelect.setValue(MapUiSettings.markerType());
            markerPrimaryColorInput.setValue(MapUiSettings.markerPrimaryColor());
            markerColorInput.setValue(MapUiSettings.markerColor());
            markerLineColorInput.setValue(MapUiSettings.markerLineColor());
            markerSizeSlider.setValue(MapUiSettings.markerSize());

            int row1 = contentY + 24;
            int row2 = contentY + 52;
            int row3 = contentY + 80;
            int row4 = contentY + 108;
            int row5 = contentY + 140;

            graphics.drawString(font, "图标类型", contentX, row1 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "主色", contentX, row2 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "辅色", contentX, row3 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "边框色", contentX, row4 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "图标大小", contentX, row5 + 5, 0xffd8dde2, false);

            MapUiControls.Control playerPopup = null;
            MapUiControls.Control[] playerControls = {
                    markerTypeSelect,
                    markerPrimaryColorInput,
                    markerColorInput,
                    markerLineColorInput
            };
            for (MapUiControls.Control control : playerControls) {
                if (control.popupOpen()) {
                    playerPopup = control;
                } else {
                    control.render(graphics, font, mouseX, mouseY);
                }
            }
            markerSizeSlider.render(graphics, font, mouseX, mouseY);

            String sizeText = MapUiSettings.markerSize() + " px";
            graphics.drawString(
                    font, sizeText,
                    Math.max(contentX, contentRight - font.width(sizeText)),
                    row5 + 5, 0xffd7dde2, false);

            // The active popup is visually topmost, matching input routing.
            if (playerPopup != null) {
                playerPopup.render(graphics, font, mouseX, mouseY);
            }
        } else if (settingsTab == SettingsTab.STRUCTURE) {
            graphics.drawString(font, "结构预设", contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 15, contentRight, contentY + 16, 0xff39424a);
            graphics.drawString(
                    font,
                    "自动结构的默认图标和配色；只影响未手工编辑的自动标点。",
                    contentX, contentY + 22, 0xff8f989f, false);

            layoutStructurePresetSettings();
            structureResetTable.render(graphics, font, mouseX, mouseY);

            int listTop = structureListTop();
            int listBottom = structureListBottom();
            int[] columns = structureColumns();
            graphics.drawString(font, "结构", columns[0], listTop - 12, 0xffaeb7be, false);
            graphics.drawString(font, "图标", columns[1], listTop - 12, 0xffaeb7be, false);
            graphics.drawCenteredString(
                    font, "主", columns[2] + columns[6] / 2, listTop - 12, 0xffaeb7be);
            graphics.drawCenteredString(
                    font, "辅", columns[3] + columns[6] / 2, listTop - 12, 0xffaeb7be);
            graphics.drawCenteredString(
                    font, "边", columns[4] + columns[6] / 2, listTop - 12, 0xffaeb7be);

            MapUiControls.Control structurePopup = null;
            int row = 0;
            for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
                int rowY = structureRowY(row);
                if (rowY >= listTop && rowY + 22 <= listBottom) {
                    if ((row & 1) == 1) {
                        graphics.fill(contentX, rowY - 2, contentRight, rowY + 24, 0x182f373e);
                    }
                    graphics.drawString(
                            font, preset.label,
                            columns[0], rowY + 7, 0xffd8dde2, false);

                    MapUiControls.MarkerIconSelect select = structurePresetSelects.get(preset);
                    MapUiControls.ColorInput primary = structurePrimaryInputs.get(preset);
                    MapUiControls.ColorInput accent = structureAccentInputs.get(preset);
                    MapUiControls.ColorInput line = structureLineInputs.get(preset);
                    select.setValue(MapUiSettings.structurePresetIcon(preset));
                    primary.setValue(MapUiSettings.structurePresetPrimaryColor(preset));
                    accent.setValue(MapUiSettings.structurePresetAccentColor(preset));
                    line.setValue(MapUiSettings.structurePresetLineColor(preset));

                    MapUiControls.Control[] rowControls = {
                            select, primary, accent, line
                    };
                    for (MapUiControls.Control control : rowControls) {
                        if (control.popupOpen()) {
                            structurePopup = control;
                        } else {
                            control.render(graphics, font, mouseX, mouseY);
                        }
                    }
                }
                row++;
            }
            renderStructureScrollBar(graphics, contentRight, listTop, listBottom);
            if (structurePopup != null) {
                structurePopup.render(graphics, font, mouseX, mouseY);
            }
        } else if (settingsTab == SettingsTab.MAP) {
            graphics.drawString(font, "地图性能", contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 15, contentRight, contentY + 16, 0xff39424a);

            layoutPerformanceSettings();
            requestBatchSlider.setValue(MapUiSettings.requestBatchSize());
            liveScanIntervalSlider.setValue(MapUiSettings.liveScanIntervalTicks());

            int[] cols = performanceColumns();
            int row1 = contentY + 28;
            int row2 = contentY + 86;

            graphics.drawString(font, "每批请求区块数",
                    cols[0], row1 + 5, 0xffd8dde2, false);
            requestBatchSlider.render(graphics, font, mouseX, mouseY);
            String requestValue = Integer.toString(MapUiSettings.requestBatchSize());
            graphics.drawString(font, requestValue,
                    cols[2] + cols[4] - font.width(requestValue),
                    row1 + 5, 0xffd7dde2, false);
            requestBatchHelp.render(graphics, font, mouseX, mouseY);

            graphics.drawString(font, "实时扫描间隔",
                    cols[0], row2 + 5, 0xffd8dde2, false);
            double seconds = MapUiSettings.liveScanIntervalTicks() / 20.0;
            String scanValue = MapUiSettings.liveScanIntervalTicks() + " tick / "
                    + String.format(java.util.Locale.ROOT, "%.2f s", seconds);
            graphics.drawString(font, scanValue,
                    cols[2] + cols[4] - font.width(scanValue),
                    row2 + 5, 0xffd7dde2, false);
            liveScanIntervalSlider.render(graphics, font, mouseX, mouseY);
            liveScanHelp.render(graphics, font, mouseX, mouseY);
        } else {
            graphics.drawString(font, "操作", contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 18, contentRight, contentY + 19, 0xff39424a);
            emptyTabText.setBounds(contentX, contentY + 38,
                    Math.max(120, contentRight - contentX), 80);
            emptyTabText.render(graphics, font, mouseX, mouseY);
        }
    }

    private void layoutSettingsTabs() {
        settingsTabsTable.setBounds(8, 54, SETTINGS_TAB_W - 16, 136);
    }

    private void updateSettingsTabStyle() {
        playerTabLabel.setColor(settingsTab == SettingsTab.PLAYER ? 0xffffd166 : 0xffcbd2d8);
        structureTabLabel.setColor(settingsTab == SettingsTab.STRUCTURE ? 0xffffd166 : 0xffcbd2d8);
        mapTabLabel.setColor(settingsTab == SettingsTab.MAP ? 0xffffd166 : 0xffcbd2d8);
        controlTabLabel.setColor(settingsTab == SettingsTab.CONTROL ? 0xffffd166 : 0xffcbd2d8);
    }

    private void setSettingsTab(SettingsTab next) {
        if (next == SettingsTab.STRUCTURE && settingsTab != SettingsTab.STRUCTURE) {
            structureSettingsScroll = 0;
        }
        settingsTab = next;
        markerTypeSelect.closePopup();
        markerPrimaryColorInput.closePopup();
        markerColorInput.closePopup();
        markerLineColorInput.closePopup();
        closeStructurePresetPopups();
    }

    private void layoutSettingsControls() {
        int contentX = SETTINGS_TAB_W + 28;
        int contentY = 58;
        int contentRight = width - 28;
        int available = Math.max(1, contentRight - contentX);
        int labelW = Math.min(96, Math.max(58, available / 3));
        int controlX = contentX + labelW + 8;
        int controlW = Math.max(72, contentRight - controlX);

        markerTypeSelect.setBounds(controlX, contentY + 24, controlW, 22);
        markerPrimaryColorInput.setBounds(controlX, contentY + 52, controlW, 22);
        markerColorInput.setBounds(controlX, contentY + 80, controlW, 22);
        markerLineColorInput.setBounds(controlX, contentY + 108, controlW, 22);
        markerSizeSlider.setBounds(controlX, contentY + 140, Math.max(72, controlW - 48), 18);
    }

    private int structureListTop() {
        return 112;
    }

    private int structureListBottom() {
        return Math.max(structureListTop() + 28, height - 10);
    }

    private int structureRowY(int row) {
        return structureListTop() + row * 28 - structureSettingsScroll;
    }

    private int structureMaxScroll() {
        int viewport = Math.max(1, structureListBottom() - structureListTop());
        int content = MapMarkerStore.StructurePreset.values().length * 28;
        return Math.max(0, content - viewport);
    }

    private boolean structureRowVisible(int row) {
        int rowY = structureRowY(row);
        return rowY >= structureListTop() && rowY + 22 <= structureListBottom();
    }

    private int[] structureColumns() {
        int contentX = SETTINGS_TAB_W + 28;
        int contentRight = width - 28;
        int available = Math.max(1, contentRight - contentX);
        int colorBlock = available < 220 ? 20 : 28;
        int colorsTotal = colorBlock * 3;
        int nameW = Math.min(88, Math.max(44, available * 27 / 100));
        int iconW = Math.max(52, available - nameW - colorsTotal - 4);

        int total = nameW + iconW + colorsTotal + 4;
        if (total > available) {
            int excess = total - available;
            nameW = Math.max(40, nameW - excess);
            iconW = Math.max(44, available - nameW - colorsTotal - 4);
        }

        int iconX = contentX + nameW;
        int colorX = iconX + iconW + 4;
        return new int[] {
                contentX,
                iconX,
                colorX,
                colorX + colorBlock,
                colorX + colorBlock * 2,
                iconW,
                colorBlock
        };
    }

    private void layoutStructurePresetSettings() {
        int contentX = SETTINGS_TAB_W + 28;
        int contentRight = width - 28;
        int[] c = structureColumns();

        structureSettingsScroll = clamp(
                structureSettingsScroll, 0, structureMaxScroll());
        structureResetTable.setBounds(contentRight - 70, 53, 70, 20);

        int row = 0;
        for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
            int rowY = structureRowY(row);
            structurePresetSelects.get(preset).setBounds(c[1], rowY, c[5], 22);
            int colorW = Math.max(18, c[6] - 2);
            structurePrimaryInputs.get(preset).setBounds(c[2], rowY, colorW, 22);
            structureAccentInputs.get(preset).setBounds(c[3], rowY, colorW, 22);
            structureLineInputs.get(preset).setBounds(c[4], rowY, colorW, 22);
            row++;
        }
    }

    private void renderStructureScrollBar(
            GuiGraphics graphics, int contentRight, int listTop, int listBottom) {
        int max = structureMaxScroll();
        if (max <= 0) return;
        int trackX = contentRight - 2;
        int trackH = Math.max(8, listBottom - listTop);
        int thumbH = Math.max(16, (int) Math.round(
                trackH * (trackH / (double) (trackH + max))));
        int travel = Math.max(1, trackH - thumbH);
        int thumbY = listTop + (int) Math.round(
                travel * (structureSettingsScroll / (double) max));
        graphics.fill(trackX, listTop, trackX + 2, listBottom, 0xff353d44);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xff89949d);
    }

    private void closeStructurePresetPopups() {
        for (MapUiControls.MarkerIconSelect select : structurePresetSelects.values()) {
            select.closePopup();
        }
        for (MapUiControls.ColorInput input : structurePrimaryInputs.values()) {
            input.closePopup();
        }
        for (MapUiControls.ColorInput input : structureAccentInputs.values()) {
            input.closePopup();
        }
        for (MapUiControls.ColorInput input : structureLineInputs.values()) {
            input.closePopup();
        }
    }

    private void resetStructurePresets() {
        MapUiSettings.resetStructurePresets();
        MapMarkerStore.applyAllStructurePresets();
        closeStructurePresetPopups();
    }


    private void closeStructurePresetPopupsExcept(MapUiControls.Control keep) {
        for (MapUiControls.MarkerIconSelect select : structurePresetSelects.values()) {
            if (select != keep) select.closePopup();
        }
        for (MapUiControls.ColorInput input : structurePrimaryInputs.values()) {
            if (input != keep) input.closePopup();
        }
        for (MapUiControls.ColorInput input : structureAccentInputs.values()) {
            if (input != keep) input.closePopup();
        }
        for (MapUiControls.ColorInput input : structureLineInputs.values()) {
            if (input != keep) input.closePopup();
        }
    }

    private int[] performanceColumns() {
        int contentX = SETTINGS_TAB_W + 28;
        int contentRight = width - 28;
        int available = Math.max(1, contentRight - contentX);
        int labelW = Math.min(105, Math.max(58, available * 30 / 100));
        int valueW = Math.min(92, Math.max(58, available * 24 / 100));
        int controlX = contentX + labelW + 8;
        int valueX = contentRight - valueW;
        int controlW = Math.max(48, valueX - controlX - 8);

        // On very narrow GUI scales, sacrifice label/value width before
        // allowing the slider to overlap either column.
        int overflow = controlX + controlW + 8 - valueX;
        if (overflow > 0) {
            controlW = Math.max(40, controlW - overflow);
        }
        return new int[] { contentX, controlX, valueX, controlW, valueW };
    }

    private void layoutPerformanceSettings() {
        int contentY = 58;
        int[] cols = performanceColumns();

        requestBatchSlider.setBounds(cols[1], contentY + 28, cols[3], 18);
        requestBatchHelp.setBounds(
                cols[0], contentY + 50,
                Math.max(160, width - 28 - cols[0]), 28);

        liveScanIntervalSlider.setBounds(cols[1], contentY + 86, cols[3], 18);
        liveScanHelp.setBounds(
                cols[0], contentY + 108,
                Math.max(160, width - 28 - cols[0]), 32);
    }

    private int maxPendingRequests() {
        return Math.max(MIN_PENDING_REQUESTS, MapUiSettings.requestBatchSize() * 2);
    }

    private int contextMenuHeight() {
        return CONTEXT_H * 4;
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int h = contextMenuHeight();
        MapHifiUi.panel(graphics, contextX, contextY, CONTEXT_W, h);

        if (contextMarker == null) {
            drawContextRow(
                    graphics, mouseX, mouseY, 0,
                    MapIconAtlas.UiIcon.MARKERS, "添加标点...", false);
            drawContextRow(
                    graphics, mouseX, mouseY, 1,
                    MapIconAtlas.UiIcon.TELEPORT, "传送至此", false);
            drawContextRow(
                    graphics, mouseX, mouseY, 2,
                    MapIconAtlas.UiIcon.COPY, "复制坐标", false);
        } else {
            boolean reset = contextMarker.origin == MapMarkerStore.Origin.AUTO
                    && contextMarker.modifiedAuto;
            drawContextRow(
                    graphics, mouseX, mouseY, 0,
                    MapIconAtlas.UiIcon.TELEPORT, "传送", false);
            drawContextRow(
                    graphics, mouseX, mouseY, 1,
                    MapIconAtlas.UiIcon.EDIT, "编辑", false);
            drawContextRow(
                    graphics, mouseX, mouseY, 2,
                    reset ? MapIconAtlas.UiIcon.RESET : MapIconAtlas.UiIcon.TRASH,
                    reset ? "重置为自动" : "删除", !reset);
        }

        int metaY = contextY + CONTEXT_H * 3;
        graphics.fill(
                contextX + 8, metaY,
                contextX + CONTEXT_W - 8, metaY + 1,
                0xff3b4850);
        String meta = "X " + contextBlockX
                + "  Y " + contextBlockY
                + "  Z " + contextBlockZ;
        graphics.drawString(
                font, meta, contextX + 12, metaY + 8,
                0xff8f9ba2, false);
    }

    private void drawContextRow(
            GuiGraphics graphics, int mouseX, int mouseY, int row,
            MapIconAtlas.UiIcon icon, String text, boolean danger) {
        int y = contextY + row * CONTEXT_H;
        boolean hover = inside(mouseX, mouseY, contextX, y, CONTEXT_W, CONTEXT_H);
        if (hover) graphics.fill(
                contextX + 2, y + 1,
                contextX + CONTEXT_W - 2, y + CONTEXT_H - 1,
                danger ? 0xff4e2d31 : 0xff29363e);
        MapIconAtlas.drawUi(
                graphics, icon, contextX + 14, y + CONTEXT_H / 2,
                12, danger ? 0xffe07878 : 0xffdfe6ea);
        graphics.drawString(
                font, text, contextX + 27, y + 8,
                danger ? 0xffe78a8a : 0xffedf1f3, false);
    }

    private boolean handleContextClick(int mouseX, int mouseY) {
        int row = (mouseY - contextY) / CONTEXT_H;
        if (row < 0 || row >= 3) return true;
        if (contextMarker == null) {
            if (row == 0) addContextMarker();
            else if (row == 1) teleportToContext();
            else copyContextCoordinates();
        } else {
            if (row == 0) teleportToContext();
            else if (row == 1) editContextMarker();
            else deleteContextMarker();
        }
        return true;
    }

    private void copyContextCoordinates() {
        minecraft.keyboardHandler.setClipboard(
                contextBlockX + ", " + contextBlockY + ", " + contextBlockZ);
        contextOpen = false;
        contextMarker = null;
    }

    private void addContextMarker() {
        markerUi.openCreate(dimension(), contextBlockX, contextBlockY, contextBlockZ);
        contextOpen = false;
    }

    private void teleportToContext() {
        PacketDistributor.sendToServer(new MapProtocol.TeleportRequest(
                dimension(), contextBlockX, contextBlockZ));
        contextOpen = false;
        contextMarker = null;
    }

    private void editContextMarker() {
        if (contextMarker != null) {
            int y = contextMarker.origin == MapMarkerStore.Origin.AUTO
                    ? surfaceYAt(contextMarker.x, contextMarker.z)
                    : contextMarker.y;
            markerUi.openEdit(contextMarker, y);
        }
        contextOpen = false;
        contextMarker = null;
    }

    private void deleteContextMarker() {
        if (contextMarker != null) {
            if (contextMarker.origin == MapMarkerStore.Origin.AUTO
                    && contextMarker.modifiedAuto) {
                MapMarkerStore.resetAutomatic(contextMarker.uuid());
            } else {
                MapMarkerStore.delete(contextMarker.uuid());
            }
        }
        contextOpen = false;
        contextMarker = null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        // Strict visual/input z-order: modal editor > modal settings/about >
        // top bar > floating panels > context menu > HUD > map.
        double uiMouseX = MapHifiUi.toPixelD(mouseX);
        double uiMouseY = MapHifiUi.toPixelD(mouseY);
        int uiWidth = pixelUiWidth();
        int uiHeight = pixelUiHeight();

        if (markerUi.overlayOpen()) {
            return markerUi.mouseClicked(
                    uiMouseX, uiMouseY, button, uiWidth, uiHeight);
        }
        if (hifiSettingsUi.isOpen()) {
            return hifiSettingsUi.mouseClicked(
                    uiMouseX, uiMouseY, button, uiWidth, uiHeight);
        }
        if (aboutOpen) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && aboutCloseHit(uiMouseX, uiMouseY)) {
                aboutOpen = false;
            }
            return true;
        }

        if (topbarContains(mouseX, mouseY)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                handleHifiTopbarClick((int) uiMouseX, (int) uiMouseY);
            }
            return true;
        }

        if (markerUi.managerOpen()
                && markerUi.managerContains(uiMouseX, uiMouseY)) {
            return markerUi.managerMouseClicked(
                    uiMouseX, uiMouseY, button, uiWidth, uiHeight,
                    dimension(),
                    (int) Math.floor(centerX),
                    surfaceYAt((int) Math.floor(centerX), (int) Math.floor(centerZ)),
                    (int) Math.floor(centerZ));
        }
        if (layerPanelUi.open() && layerPanelUi.contains(uiMouseX, uiMouseY)) {
            return layerPanelUi.mouseClicked(
                    uiMouseX, uiMouseY, button, uiWidth, uiHeight);
        }

        if (contextOpen) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && inside(mx, my, contextX, contextY, CONTEXT_W, contextMenuHeight())) {
                return handleContextClick(mx, my);
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                    && inside(mx, my, contextX, contextY, CONTEXT_W, contextMenuHeight())) {
                return true;
            }
            contextOpen = false;
            contextMarker = null;
        }

        if (hudContains(mouseX, mouseY)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) handleHifiHudClick(mx, my);
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Optional<MapMarkerStore.Marker> marker =
                    (MapUiSettings.markerLayerEnabled() || MapUiSettings.structureLayerEnabled())
                            ? markerUi.markerAt(
                                    dimension(), centerX, centerZ, zoom,
                                    width, height, mouseX, mouseY,
                                    MapUiSettings.markerLayerEnabled(),
                                    MapUiSettings.structureLayerEnabled())
                            : Optional.empty();
            if (marker.isPresent()) {
                contextMarker = marker.get();
                contextBlockX = contextMarker.x;
                contextBlockZ = contextMarker.z;
                contextBlockY = contextMarker.origin == MapMarkerStore.Origin.AUTO
                        ? surfaceYAt(contextBlockX, contextBlockZ)
                        : contextMarker.y;
            } else {
                double worldX = centerX + (mouseX - width * 0.5) / zoom;
                double worldZ = centerZ + (mouseY - height * 0.5) / zoom;
                contextMarker = null;
                contextBlockX = (int) Math.floor(worldX);
                contextBlockZ = (int) Math.floor(worldZ);
                contextBlockY = surfaceYAt(contextBlockX, contextBlockZ);
            }
            contextX = clamp(mx + 4, 4, Math.max(4, width - CONTEXT_W - 4));
            contextY = clamp(my + 4, MapHifiUi.headerGui() + 2,
                    Math.max(MapHifiUi.headerGui() + 2, height - contextMenuHeight() - 4));
            contextOpen = true;
            dragging = false;
            return true;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (!MapUiSettings.dragMap()) return true;
        dragging = true;
        return true;
    }

    private void handleHifiTopbarClick(int mouseX, int mouseY) {
        int uiWidth = pixelUiWidth();
        int uiHeight = pixelUiHeight();
        int closeX = uiWidth - 64;
        if (inside(mouseX, mouseY, closeX, 10, 42, 42)) {
            onClose();
            return;
        }

        int start = navStartX();
        int index = (mouseX - start) / navItemWidth();
        if (mouseX < start || index < 0 || index > 4) return;

        contextOpen = false;
        contextMarker = null;
        dragging = false;

        if (index == 0) {
            markerUi.closeManager();
            layerPanelUi.close();
            hifiSettingsUi.close();
            aboutOpen = false;
            return;
        }
        if (index == 1) {
            layerPanelUi.close();
            hifiSettingsUi.close();
            aboutOpen = false;
            if (markerUi.managerOpen()) markerUi.closeManager();
            else markerUi.openManager(
                    navX(1) + navItemWidth() / 2,
                    MapHifiUi.HEADER_H + 8,
                    uiWidth, uiHeight);
            return;
        }
        if (index == 2) {
            markerUi.closeManager();
            hifiSettingsUi.close();
            aboutOpen = false;
            if (layerPanelUi.open()) layerPanelUi.close();
            else layerPanelUi.openAt(
                    navX(2) + navItemWidth() / 2,
                    MapHifiUi.HEADER_H + 8,
                    uiWidth, uiHeight);
            return;
        }
        if (index == 3) {
            markerUi.closeManager();
            layerPanelUi.close();
            aboutOpen = false;
            hifiSettingsUi.open();
            return;
        }
        markerUi.closeManager();
        layerPanelUi.close();
        hifiSettingsUi.close();
        aboutOpen = !aboutOpen;
    }

    private boolean hudContains(double mouseX, double mouseY) {
        double ux = MapHifiUi.toPixelD(mouseX);
        double uy = MapHifiUi.toPixelD(mouseY);
        int uiW = pixelUiWidth();
        int uiH = pixelUiHeight();
        int zoomX = uiW - 18 - 166;
        int zoomY = uiH - 20 - 44;
        int locateX = uiW - 18 - 44;
        int locateY = zoomY - 7 - 44;
        return MapHifiUi.inside(ux, uy, zoomX, zoomY, 166, 44)
                || MapHifiUi.inside(ux, uy, locateX, locateY, 44, 44);
    }

    private void handleHifiHudClick(int mouseX, int mouseY) {
        int uiW = pixelUiWidth();
        int uiH = pixelUiHeight();
        int zoomX = uiW - 18 - 166;
        int zoomY = uiH - 20 - 44;
        int locateX = uiW - 18 - 44;
        int locateY = zoomY - 7 - 44;
        if (inside(mouseX, mouseY, locateX, locateY, 44, 44)) {
            centerOnPlayer();
            return;
        }
        if (inside(mouseX, mouseY, zoomX, zoomY, 44, 44)) {
            int index = nearestZoomIndex(zoom);
            setZoomAt(ZOOM_LEVELS[Math.max(0, index - 1)], width * 0.5, height * 0.5);
            return;
        }
        if (inside(mouseX, mouseY, zoomX + 122, zoomY, 44, 44)) {
            int index = nearestZoomIndex(zoom);
            setZoomAt(ZOOM_LEVELS[Math.min(ZOOM_LEVELS.length - 1, index + 1)],
                    width * 0.5, height * 0.5);
        }
    }

    private boolean aboutCloseHit(double mouseX, double mouseY) {
        int uiW = pixelUiWidth();
        int uiH = pixelUiHeight();
        int w = Math.min(520, uiW - 36);
        int h = Math.min(270, uiH - MapHifiUi.HEADER_H - 30);
        int x = (uiW - w) / 2;
        int y = MapHifiUi.HEADER_H
                + Math.max(10, (uiH - MapHifiUi.HEADER_H - h) / 2);
        return MapHifiUi.modalCloseHit(mouseX, mouseY, x, y, w);
    }

    private boolean handleSettingsClick(int mouseX, int mouseY) {
        layoutSettingsTabs();
        settingsCloseIcon.setBounds(width - 28, 10, 16, 16);

        // Visual z-order must also be input z-order:
        // open popup > close/navigation > page controls > background.
        // This prevents a color/icon popup drawn over the left navigation from
        // accidentally activating the tab underneath it.
        if (handleOpenSettingsPopupClick(mouseX, mouseY)) {
            return true;
        }

        if (settingsCloseIcon.contains(mouseX, mouseY)) {
            closeSettings();
            return true;
        }
        if (settingsTabsTable.mouseClicked(
                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            return true;
        }

        if (settingsTab == SettingsTab.PLAYER) {
            layoutSettingsControls();

            if (markerTypeSelect.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerPrimaryColorInput.closePopup();
                markerColorInput.closePopup();
                markerLineColorInput.closePopup();
                return true;
            }
            if (markerPrimaryColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                markerColorInput.closePopup();
                markerLineColorInput.closePopup();
                return true;
            }
            if (markerColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                markerPrimaryColorInput.closePopup();
                markerLineColorInput.closePopup();
                return true;
            }
            if (markerLineColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                markerPrimaryColorInput.closePopup();
                markerColorInput.closePopup();
                return true;
            }
            if (markerSizeSlider.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                markerPrimaryColorInput.closePopup();
                markerColorInput.closePopup();
                markerLineColorInput.closePopup();
                return true;
            }
        } else if (settingsTab == SettingsTab.STRUCTURE) {
            layoutStructurePresetSettings();

            if (structureResetTable.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                closeStructurePresetPopups();
                return true;
            }

            if (mouseY < structureListTop() || mouseY >= structureListBottom()) {
                return true;
            }

            for (MapMarkerStore.StructurePreset preset
                    : MapMarkerStore.StructurePreset.values()) {
                int row = preset.ordinal();
                if (!structureRowVisible(row)) continue;
                MapUiControls.Control[] controls = {
                        structurePresetSelects.get(preset),
                        structurePrimaryInputs.get(preset),
                        structureAccentInputs.get(preset),
                        structureLineInputs.get(preset)
                };
                for (MapUiControls.Control control : controls) {
                    if (control.mouseClicked(
                            mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                        closeStructurePresetPopupsExcept(control);
                        return true;
                    }
                }
            }
        } else if (settingsTab == SettingsTab.MAP) {
            layoutPerformanceSettings();
            if (requestBatchSlider.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                return true;
            }
            if (liveScanIntervalSlider.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                return true;
            }
        }

        return true;
    }

    private boolean handleOpenSettingsPopupClick(int mouseX, int mouseY) {
        if (settingsTab == SettingsTab.PLAYER) {
            MapUiControls.Control[] controls = {
                    markerTypeSelect,
                    markerPrimaryColorInput,
                    markerColorInput,
                    markerLineColorInput
            };
            for (MapUiControls.Control control : controls) {
                if (control.popupOpen()) {
                    if (control.mouseClicked(
                            mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                        return true;
                    }
                    // Click outside the active popup closes it, then the same
                    // click may continue to navigation/page controls.
                    break;
                }
            }
            return false;
        }

        if (settingsTab == SettingsTab.STRUCTURE) {
            for (MapMarkerStore.StructurePreset preset
                    : MapMarkerStore.StructurePreset.values()) {
                MapUiControls.Control[] controls = {
                        structurePresetSelects.get(preset),
                        structurePrimaryInputs.get(preset),
                        structureAccentInputs.get(preset),
                        structureLineInputs.get(preset)
                };
                for (MapUiControls.Control control : controls) {
                    if (control.popupOpen()) {
                        if (control.mouseClicked(
                                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                            closeStructurePresetPopupsExcept(control);
                            return true;
                        }
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void closeSettings() {
        settingsOpen = false;
        markerTypeSelect.closePopup();
        markerPrimaryColorInput.closePopup();
        markerColorInput.closePopup();
        markerLineColorInput.closePopup();
        closeStructurePresetPopups();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        double uiMouseX = MapHifiUi.toPixelD(mouseX);
        double uiMouseY = MapHifiUi.toPixelD(mouseY);
        if (markerUi.overlayOpen()) {
            return markerUi.mouseReleased(uiMouseX, uiMouseY, button);
        }
        if (hifiSettingsUi.isOpen()) {
            return hifiSettingsUi.mouseReleased(uiMouseX, uiMouseY, button);
        }
        if (markerUi.managerOpen()
                && markerUi.mouseReleased(uiMouseX, uiMouseY, button)) return true;
        if (layerPanelUi.mouseReleased(button)) return true;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            requestVisible(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        double uiMouseX = MapHifiUi.toPixelD(mouseX);
        double uiMouseY = MapHifiUi.toPixelD(mouseY);
        double uiDragX = MapHifiUi.toPixelD(dragX);
        double uiDragY = MapHifiUi.toPixelD(dragY);
        int uiWidth = pixelUiWidth();
        int uiHeight = pixelUiHeight();

        if (markerUi.overlayOpen()) {
            return markerUi.mouseDragged(
                    uiMouseX, uiMouseY, button, uiDragX, uiDragY, uiWidth, uiHeight);
        }
        if (hifiSettingsUi.isOpen()) {
            return hifiSettingsUi.mouseDragged(
                    uiMouseX, uiMouseY, button, uiDragX, uiDragY);
        }
        if (markerUi.managerOpen()
                && markerUi.mouseDragged(
                        uiMouseX, uiMouseY, button,
                        uiDragX, uiDragY, uiWidth, uiHeight)) {
            return true;
        }
        if (layerPanelUi.open()
                && layerPanelUi.mouseDragged(
                        uiMouseX, uiMouseY, button, uiWidth, uiHeight)) {
            return true;
        }

        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && MapUiSettings.dragMap()) {
            contextOpen = false;
            centerX -= dragX / zoom;
            centerZ -= dragY / zoom;
            if (Boolean.getBoolean("cartograph.v3.qaInput")) {
                LOG.info("V3_QA_DRAG center={},{} delta={},{} zoom={}",
                        centerX, centerZ, dragX, dragY, zoom);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        double uiMouseX = MapHifiUi.toPixelD(mouseX);
        double uiMouseY = MapHifiUi.toPixelD(mouseY);
        int uiWidth = pixelUiWidth();
        int uiHeight = pixelUiHeight();

        if (markerUi.overlayOpen()) {
            return markerUi.mouseScrolled(scrollY, uiWidth, uiHeight);
        }
        if (hifiSettingsUi.isOpen()) {
            return hifiSettingsUi.mouseScrolled(scrollY);
        }
        if (markerUi.managerOpen() && markerUi.managerContains(uiMouseX, uiMouseY)) {
            return markerUi.mouseScrolled(scrollY, uiWidth, uiHeight);
        }
        if (layerPanelUi.mouseScrolled(uiMouseX, uiMouseY, scrollY)) {
            return true;
        }
        if (contextOpen || topbarContains(mouseX, mouseY) || scrollY == 0) return true;

        int current = nearestZoomIndex(zoom);
        int steps = Math.max(1, (int) Math.round(Math.abs(scrollY)));
        int nextIndex = clamp(
                current + (scrollY > 0 ? steps : -steps),
                0, ZOOM_LEVELS.length - 1);
        setZoomAt(ZOOM_LEVELS[nextIndex], mouseX, mouseY);

        if (Boolean.getBoolean("cartograph.v3.qaInput")) {
            LOG.info("V3_QA_SCROLL center={},{} zoom={}", centerX, centerZ, zoom);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (markerUi.overlayOpen()
                && markerUi.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (hifiSettingsUi.isOpen()
                && hifiSettingsUi.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (contextOpen) {
                contextOpen = false;
                contextMarker = null;
                return true;
            }
            if (aboutOpen) {
                aboutOpen = false;
                return true;
            }
            if (markerUi.managerOpen()) {
                markerUi.closeManager();
                return true;
            }
            if (layerPanelUi.open()) {
                layerPanelUi.close();
                return true;
            }
            onClose();
            return true;
        }

        if (CartographV3Client.matchesOpenKey(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (markerUi.overlayOpen()
                && markerUi.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (hifiSettingsUi.isOpen()
                && hifiSettingsUi.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void setZoomFromSlider(int index) {
        int clamped = clamp(index, 0, ZOOM_LEVELS.length - 1);
        setZoomAt(ZOOM_LEVELS[clamped], width * 0.5, height * 0.5);
    }

    private void setZoomAt(double nextZoom, double screenX, double screenY) {
        double next = Math.max(ZOOM_LEVELS[0],
                Math.min(ZOOM_LEVELS[ZOOM_LEVELS.length - 1], nextZoom));
        if (Double.compare(next, zoom) == 0) return;

        double worldAtX = centerX + (screenX - width * 0.5) / zoom;
        double worldAtZ = centerZ + (screenY - height * 0.5) / zoom;
        centerX = worldAtX - (screenX - width * 0.5) / next;
        centerZ = worldAtZ - (screenY - height * 0.5) / next;
        zoom = next;
        requestVisible(false);
    }

    private void centerOnPlayer() {
        if (minecraft.player == null) return;
        centerX = minecraft.player.getX();
        centerZ = minecraft.player.getZ();
        requestVisible(false);
    }

    private static int nearestZoomIndex(double value) {
        int best = 0;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            double nextDistance = Math.abs(ZOOM_LEVELS[i] - value);
            if (nextDistance < distance) {
                distance = nextDistance;
                best = i;
            }
        }
        return best;
    }

    private static String formatZoom(double value) {
        double percent = value * 100.0;
        if (percent < 10.0) {
            return String.format(java.util.Locale.ROOT, "%.2f%%", percent);
        }
        if (Math.abs(percent - Math.rint(percent)) > 0.001) {
            return String.format(java.util.Locale.ROOT, "%.1f%%", percent);
        }
        return Math.round(percent) + "%";
    }

    private static void withLayer(GuiGraphics graphics, float z, Runnable render) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, z);
        try {
            render.run();
        } finally {
            graphics.pose().popPose();
        }
    }

    @Override
    public void removed() {
        PacketDistributor.sendToServer(new MapProtocol.MapViewState(
                false, MapUiSettings.liveScanIntervalTicks()));
        regionTextures.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean inside(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
