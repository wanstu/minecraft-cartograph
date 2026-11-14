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

    private static final int CONTEXT_W = 108;
    private static final int CONTEXT_H = 26;

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

    private enum SettingsTab { PLAYER, MAP, CONTROL }
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
    private int contextBlockZ;
    private MapMarkerStore.Marker contextMarker;

    private final MapUiControls.Select<MapUiSettings.MarkerType> markerTypeSelect;
    private final MapUiControls.ColorInput markerColorInput;
    private final MapUiControls.Slider markerSizeSlider;

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
    private final MapUiControls.Span mapTabLabel;
    private final MapUiControls.Span controlTabLabel;
    private final MapUiControls.Table settingsTabsTable;
    private final MapUiControls.P emptyTabText;
    private final MapUiControls.Icon settingsCloseIcon;
    private final MapMarkerUi markerUi = new MapMarkerUi();
    private SettingsTab settingsTab = SettingsTab.PLAYER;

    public MapScreenV3() {
        super(Component.literal("Cartograph V3"));
        markerTypeSelect = new MapUiControls.Select<>(
                86,
                List.of(
                        new MapUiControls.Option<>("箭头", MapUiSettings.MarkerType.ARROW),
                        new MapUiControls.Option<>("十字", MapUiSettings.MarkerType.CROSS)),
                MapUiSettings.markerType(),
                MapUiSettings::setMarkerType);
        markerColorInput = new MapUiControls.ColorInput(
                86, MapUiSettings.markerColor(), MapUiSettings::setMarkerColor);
        markerSizeSlider = new MapUiControls.Slider(
                126, 6, 32, MapUiSettings.markerSize(), MapUiSettings::setMarkerSize);
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
                "每批请求区块数越大，地图空白区域补全越快，但会增加网络、磁盘读取和服务器瞬时压力。默认 64；通常建议 32～64，机器性能较弱时可继续降低。");
        requestBatchHelp.setColor(0xff8f989f);
        liveScanHelp = new MapUiControls.P(
                420, 42,
                "实时扫描间隔决定已加载区块多久检查一次地表变化。数值越小更新越及时，但会增加服务器 tick 压力。默认 10 tick（0.5 秒）；若实体/TNT 变慢，应优先调高。");
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
        mapTabLabel = new MapUiControls.Span("地图");
        controlTabLabel = new MapUiControls.Span("操作");
        settingsTabsTable = new MapUiControls.Table(SETTINGS_TAB_W - 12)
                .background(0xff20262c)
                .hoverBackground(0xff303840)
                .padding(10);
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                30, true, () -> setSettingsTab(SettingsTab.PLAYER), playerTabLabel));
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                30, true, () -> setSettingsTab(SettingsTab.MAP), mapTabLabel));
        settingsTabsTable.addRow(new MapUiControls.Table.Row(
                30, true, () -> setSettingsTab(SettingsTab.CONTROL), controlTabLabel));

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
        MapClientCache.tick();
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
        graphics.fill(0, 0, width, height, 0xff111417);
        if (minecraft.level != null) {
            regionTextures.render(
                    graphics, dimension(), centerX, centerZ, zoom, width, height);
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

        withLayer(graphics, Z_HUD, () -> renderBottomHud(graphics, mouseX, mouseY));
        withLayer(graphics, Z_MENU, () -> renderTopMenu(graphics, mouseX, mouseY));
        if (contextOpen) {
            withLayer(graphics, Z_CONTEXT, () -> renderContextMenu(graphics, mouseX, mouseY));
        }
        if (settingsOpen) {
            withLayer(graphics, Z_SETTINGS, () -> renderSettings(graphics, mouseX, mouseY));
        }
        if (markerUi.overlayOpen()) {
            withLayer(graphics, Z_MARKER_OVERLAY, () ->
                    markerUi.renderOverlay(graphics, font, width, height, mouseX, mouseY));
        }
    }

    private void renderPlayerMarker(GuiGraphics graphics) {
        if (minecraft.player == null) return;

        double sx = width * 0.5 + (minecraft.player.getX() - centerX) * zoom;
        double sy = height * 0.5 + (minecraft.player.getZ() - centerZ) * zoom;
        int size = MapUiSettings.markerSize();
        if (sx < -size || sy < -size || sx > width + size || sy > height + size) return;

        int color = 0xff000000 | MapUiSettings.markerColor();
        int outline = 0xdd0b0d0f;
        int x = (int) Math.round(sx);
        int y = (int) Math.round(sy);

        if (MapUiSettings.markerType() == MapUiSettings.MarkerType.CROSS) {
            MapIconAtlas.drawPlayerCross(
                    graphics, x, y, Math.max(8, size), color);
            return;
        }

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0f + minecraft.player.getYRot()));
        MapIconAtlas.drawPlayerArrow(graphics, 0, 0, Math.max(8, size), color);
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
                markerUi.openManager();
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
            graphics.fill(contentX, contentY + 18, contentRight, contentY + 19, 0xff39424a);

            layoutSettingsControls();
            markerTypeSelect.setValue(MapUiSettings.markerType());
            markerColorInput.setValue(MapUiSettings.markerColor());
            markerSizeSlider.setValue(MapUiSettings.markerSize());

            int row1 = contentY + 38;
            int row2 = contentY + 78;
            int row3 = contentY + 118;

            graphics.drawString(font, "图标类型", contentX, row1 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "图标颜色", contentX, row2 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "图标大小", contentX, row3 + 6, 0xffd8dde2, false);
            graphics.drawString(font, MapUiSettings.markerSize() + " px",
                    contentX + 350, row3 + 6, 0xffb8c0c7, false);

            markerSizeSlider.render(graphics, font, mouseX, mouseY);
            markerTypeSelect.render(graphics, font, mouseX, mouseY);
            markerColorInput.render(graphics, font, mouseX, mouseY);
        } else if (settingsTab == SettingsTab.MAP) {
            graphics.drawString(font, "地图性能", contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 18, contentRight, contentY + 19, 0xff39424a);

            layoutPerformanceSettings();
            requestBatchSlider.setValue(MapUiSettings.requestBatchSize());
            liveScanIntervalSlider.setValue(MapUiSettings.liveScanIntervalTicks());

            int row1 = contentY + 38;
            int row2 = contentY + 112;
            graphics.drawString(font, "每批请求区块数", contentX, row1 + 6, 0xffd8dde2, false);
            graphics.drawString(font, Integer.toString(MapUiSettings.requestBatchSize()),
                    contentX + 350, row1 + 6, 0xffb8c0c7, false);
            requestBatchSlider.render(graphics, font, mouseX, mouseY);
            requestBatchHelp.render(graphics, font, mouseX, mouseY);

            graphics.drawString(font, "实时扫描间隔", contentX, row2 + 6, 0xffd8dde2, false);
            double seconds = MapUiSettings.liveScanIntervalTicks() / 20.0;
            graphics.drawString(font,
                    MapUiSettings.liveScanIntervalTicks() + " tick / "
                            + String.format(java.util.Locale.ROOT, "%.2f s", seconds),
                    contentX + 300, row2 + 6, 0xffb8c0c7, false);
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
        settingsTabsTable.setBounds(8, 54, SETTINGS_TAB_W - 16, 102);
    }

    private void updateSettingsTabStyle() {
        playerTabLabel.setColor(settingsTab == SettingsTab.PLAYER ? 0xffffd166 : 0xffcbd2d8);
        mapTabLabel.setColor(settingsTab == SettingsTab.MAP ? 0xffffd166 : 0xffcbd2d8);
        controlTabLabel.setColor(settingsTab == SettingsTab.CONTROL ? 0xffffd166 : 0xffcbd2d8);
    }

    private void setSettingsTab(SettingsTab next) {
        settingsTab = next;
        markerTypeSelect.closePopup();
        markerColorInput.closePopup();
    }

    private void layoutSettingsControls() {
        int contentX = SETTINGS_TAB_W + 28;
        int contentY = 58;
        int controlX = Math.min(width - 230, contentX + 180);
        int controlW = Math.max(150, Math.min(220, width - controlX - 28));

        markerTypeSelect.setBounds(controlX, contentY + 38, controlW, 22);
        markerColorInput.setBounds(controlX, contentY + 78, controlW, 22);
        markerSizeSlider.setBounds(controlX, contentY + 118, Math.min(180, controlW), 18);
    }

    private void layoutPerformanceSettings() {
        int contentX = SETTINGS_TAB_W + 28;
        int contentY = 58;
        int controlX = Math.min(width - 230, contentX + 180);
        int controlW = Math.max(140, Math.min(180, width - controlX - 28));

        requestBatchSlider.setBounds(controlX, contentY + 38, controlW, 18);
        requestBatchHelp.setBounds(
                contentX, contentY + 62,
                Math.max(160, width - contentX - 28), 42);

        liveScanIntervalSlider.setBounds(controlX, contentY + 112, controlW, 18);
        liveScanHelp.setBounds(
                contentX, contentY + 136,
                Math.max(160, width - contentX - 28), 42);
    }

    private int maxPendingRequests() {
        return Math.max(MIN_PENDING_REQUESTS, MapUiSettings.requestBatchSize() * 2);
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        layoutContextMenu();
        if (contextMarker == null) {
            mapContextMenuTable.render(graphics, font, mouseX, mouseY);
        } else {
            markerContextMenuTable.render(graphics, font, mouseX, mouseY);
        }
    }

    private void addContextMarker() {
        markerUi.openCreate(dimension(), contextBlockX, contextBlockZ);
        contextOpen = false;
    }

    private void teleportToContext() {
        PacketDistributor.sendToServer(new MapProtocol.TeleportRequest(
                dimension(), contextBlockX, contextBlockZ));
        contextOpen = false;
        contextMarker = null;
    }

    private void editContextMarker() {
        if (contextMarker != null) markerUi.openEdit(contextMarker);
        contextOpen = false;
        contextMarker = null;
    }

    private void deleteContextMarker() {
        if (contextMarker != null) MapMarkerStore.delete(contextMarker.uuid());
        contextOpen = false;
        contextMarker = null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (markerUi.overlayOpen()) {
            return markerUi.mouseClicked(mouseX, mouseY, button, width, height);
        }

        layoutContextMenu();
        layoutBottomHud();

        if (settingsOpen) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return handleSettingsClick(mx, my);
            }
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (insideTopMenu(mouseX, mouseY)
                    || zoomHudTable.contains(mouseX, mouseY)
                    || locateHudTable.contains(mouseX, mouseY)) {
                return true;
            }

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
                contextX = Math.max(4, Math.min(width - CONTEXT_W - 4, mx));
                contextY = Math.max(4, Math.min(height - CONTEXT_H * 3 - 4, my));
                contextOpen = true;
                menuExpanded = false;
                menuSection = MenuSection.NONE;
                dragging = false;
                return true;
            }

            double worldX = centerX + (mouseX - width * 0.5) / zoom;
            double worldZ = centerZ + (mouseY - height * 0.5) / zoom;
            contextMarker = null;
            contextBlockX = (int) Math.floor(worldX);
            contextBlockZ = (int) Math.floor(worldZ);
            contextX = Math.max(4, Math.min(width - CONTEXT_W - 4, mx));
            contextY = Math.max(4, Math.min(height - CONTEXT_H * 2 - 4, my));
            contextOpen = true;
            menuExpanded = false;
            menuSection = MenuSection.NONE;
            dragging = false;
            return true;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (contextOpen) {
            MapUiControls.Table activeContext =
                    contextMarker == null ? mapContextMenuTable : markerContextMenuTable;
            if (activeContext.mouseClicked(mouseX, mouseY, button)) return true;
            contextOpen = false;
            contextMarker = null;
        }

        if (handleTopMenuClick(mx, my)) return true;
        if (menuExpanded) {
            menuExpanded = false;
            menuSection = MenuSection.NONE;
        }

        if (locateHudTable.mouseClicked(mouseX, mouseY, button)) return true;
        if (zoomHudTable.mouseClicked(mouseX, mouseY, button)) return true;

        dragging = true;
        return true;
    }

    private boolean handleSettingsClick(int mouseX, int mouseY) {
        layoutSettingsTabs();
        settingsCloseIcon.setBounds(width - 28, 10, 16, 16);

        if (settingsTab == SettingsTab.PLAYER) {
            layoutSettingsControls();

            if (markerColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                return true;
            }
            if (markerTypeSelect.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerColorInput.closePopup();
                return true;
            }
            if (markerSizeSlider.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                markerColorInput.closePopup();
                return true;
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

        if (settingsCloseIcon.contains(mouseX, mouseY)) {
            closeSettings();
            return true;
        }

        if (settingsTabsTable.mouseClicked(
                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            return true;
        }

        return true;
    }

    private void closeSettings() {
        settingsOpen = false;
        markerTypeSelect.closePopup();
        markerColorInput.closePopup();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (markerUi.overlayOpen()) {
            return markerUi.mouseReleased(mouseX, mouseY, button);
        }

        if (settingsOpen && settingsTab == SettingsTab.PLAYER) {
            if (markerColorInput.mouseReleased(mouseX, mouseY, button)) return true;
            if (markerSizeSlider.mouseReleased(mouseX, mouseY, button)) return true;
        } else if (settingsOpen && settingsTab == SettingsTab.MAP) {
            if (requestBatchSlider.mouseReleased(mouseX, mouseY, button)) return true;
            if (liveScanIntervalSlider.mouseReleased(mouseX, mouseY, button)) return true;
        }

        layoutBottomHud();
        if (zoomHudTable.mouseReleased(mouseX, mouseY, button)) return true;

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
        if (markerUi.overlayOpen()) {
            return markerUi.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        if (settingsOpen) {
            if (settingsTab == SettingsTab.PLAYER) {
                if (markerColorInput.mouseDragged(
                        mouseX, mouseY, button, dragX, dragY)) return true;
                if (markerSizeSlider.mouseDragged(
                        mouseX, mouseY, button, dragX, dragY)) return true;
            } else if (settingsTab == SettingsTab.MAP) {
                if (requestBatchSlider.mouseDragged(
                        mouseX, mouseY, button, dragX, dragY)) return true;
                if (liveScanIntervalSlider.mouseDragged(
                        mouseX, mouseY, button, dragX, dragY)) return true;
            }
            return true;
        }

        layoutBottomHud();
        if (zoomHudTable.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;

        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
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
        if (markerUi.overlayOpen()) {
            return markerUi.mouseScrolled(scrollY, width, height);
        }
        if (settingsOpen || contextOpen) return true;
        if (scrollY == 0) return true;

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

        if (settingsOpen && settingsTab == SettingsTab.PLAYER
                && markerColorInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (settingsOpen || menuExpanded || contextOpen) {
                closeSettings();
                menuExpanded = false;
                menuSection = MenuSection.NONE;
                contextOpen = false;
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
        if (settingsOpen && settingsTab == SettingsTab.PLAYER
                && markerColorInput.charTyped(codePoint, modifiers)) {
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
