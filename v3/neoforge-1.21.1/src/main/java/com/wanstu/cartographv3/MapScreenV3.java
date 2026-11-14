package com.wanstu.cartographv3;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MapScreenV3 extends Screen {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cartograph.v3.screen");

    private static final int MENU_W = 28;
    private static final int MENU_H = 22;
    private static final int MENU_MARGIN = 8;
    private static final int MENU_PANEL_W = 112;
    private static final int MENU_PANEL_H = 28;

    private static final int SETTINGS_W = 360;
    private static final int SETTINGS_H = 220;
    private static final int SETTINGS_TAB_W = 88;

    private static final int CONTEXT_W = 92;
    private static final int CONTEXT_H = 26;

    private static final int HUD_MARGIN = 8;
    private static final int ZOOM_HUD_W = 230;
    private static final int ZOOM_HUD_H = 26;
    private static final int LOCATE_HUD_SIZE = 30;

    private static final float Z_MARKER = 100.0f;
    private static final float Z_HUD = 200.0f;
    private static final float Z_MENU = 300.0f;
    private static final float Z_CONTEXT = 340.0f;
    private static final float Z_SETTINGS = 400.0f;

    private static final double[] ZOOM_LEVELS = {
            0.5, 0.625, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0,
            4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0,
            32.0, 40.0, 48.0, 64.0
    };

    private enum SettingsTab { PLAYER, MAP, CONTROL }

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
    private int requestTicks;

    private boolean menuExpanded;
    private boolean settingsOpen;
    private boolean contextOpen;
    private int contextX;
    private int contextY;
    private int contextBlockX;
    private int contextBlockZ;

    private final MapUiControls.Select<MapUiSettings.MarkerType> markerTypeSelect;
    private final MapUiControls.ColorInput markerColorInput;
    private final MapUiControls.Slider markerSizeSlider;

    private final MapUiControls.Table collapsedMenuTable;
    private final MapUiControls.Table expandedMenuTable;
    private final MapUiControls.Table contextMenuTable;

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

        var menuIcon = new MapUiControls.Icon(18, MapUiControls.IconType.MENU);
        collapsedMenuTable = new MapUiControls.Table(MENU_W)
                .background(0xd92b3036)
                .hoverBackground(0xe63a4148)
                .padding(5);
        collapsedMenuTable.addRow(new MapUiControls.Table.Row(
                MENU_H, true, () -> {
                    menuExpanded = !menuExpanded;
                    closeSettings();
                    contextOpen = false;
                }, menuIcon));

        var settingsLabel = new MapUiControls.Span("设置");
        expandedMenuTable = new MapUiControls.Table(MENU_PANEL_W)
                .background(0xee20252b)
                .hoverBackground(0xff343b43)
                .padding(12);
        expandedMenuTable.addRow(new MapUiControls.Table.Row(
                MENU_PANEL_H, true, () -> {
                    settingsOpen = true;
                    menuExpanded = false;
                    contextOpen = false;
                }, settingsLabel));

        var teleportLabel = new MapUiControls.Span("传送");
        contextMenuTable = new MapUiControls.Table(CONTEXT_W)
                .background(0xf21f242a)
                .hoverBackground(0xff343c45)
                .padding(12);
        contextMenuTable.addRow(new MapUiControls.Table.Row(
                CONTEXT_H, true, this::teleportToContext, teleportLabel));

        zoomLabel = new MapUiControls.Span(formatZoom(zoom));
        zoomLabel.setCentered(true);
        zoomSlider = new MapUiControls.Slider(
                140, 0, ZOOM_LEVELS.length - 1, nearestZoomIndex(zoom),
                this::setZoomFromSlider);
        zoomHudTable = new MapUiControls.Table(ZOOM_HUD_W)
                .background(0xd92b3036)
                .hoverBackground(0xd92b3036)
                .border(0xff3d464f)
                .padding(8)
                .columnGap(8);
        zoomHudTable.addRow(new MapUiControls.Table.Row(
                ZOOM_HUD_H, false, () -> {}, zoomSlider, zoomLabel));

        var locateIcon = new MapUiControls.Icon(18, MapUiControls.IconType.LOCATE);
        locateHudTable = new MapUiControls.Table(LOCATE_HUD_SIZE)
                .background(0xd92b3036)
                .hoverBackground(0xff414a53)
                .border(0xff3d464f)
                .padding(6);
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
        if (!dragging) requestVisible(requestTicks % 20 == 0);
    }

    private String dimension() {
        return minecraft.level == null ? ""
                : minecraft.level.dimension().location().toString();
    }

    private void requestVisible(boolean force) {
        if (minecraft.level == null) return;
        String dim = dimension();

        double halfBlocksX = width / (2.0 * zoom);
        double halfBlocksZ = height / (2.0 * zoom);
        int minChunkX = Math.floorDiv((int) Math.floor(centerX - halfBlocksX), 16) - 1;
        int maxChunkX = Math.floorDiv((int) Math.floor(centerX + halfBlocksX), 16) + 1;
        int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - halfBlocksZ), 16) - 1;
        int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + halfBlocksZ), 16) + 1;

        if (!force
                && minChunkX == lastRequestMinChunkX
                && maxChunkX == lastRequestMaxChunkX
                && minChunkZ == lastRequestMinChunkZ
                && maxChunkZ == lastRequestMaxChunkZ) {
            return;
        }

        lastRequestMinChunkX = minChunkX;
        lastRequestMaxChunkX = maxChunkX;
        lastRequestMinChunkZ = minChunkZ;
        lastRequestMaxChunkZ = maxChunkZ;

        List<ChunkKey> visible = new ArrayList<>();
        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            for (int x = minChunkX; x <= maxChunkX; x++) {
                ChunkKey key = new ChunkKey(dim, x, z);
                if (MapClientCache.needsRequest(key)) visible.add(key);
            }
        }

        int centerChunkX = Math.floorDiv((int) Math.floor(centerX), 16);
        int centerChunkZ = Math.floorDiv((int) Math.floor(centerZ), 16);
        visible.sort(Comparator.comparingInt(key ->
                Math.abs(key.x() - centerChunkX) + Math.abs(key.z() - centerChunkZ)));

        for (ChunkKey key : visible) MapClientCache.request(key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xff111417);
        if (minecraft.level != null) {
            regionTextures.render(
                    graphics, dimension(), centerX, centerZ, zoom, width, height);
            withLayer(graphics, Z_MARKER, () -> renderPlayerMarker(graphics));
        }

        withLayer(graphics, Z_HUD, () -> renderBottomHud(graphics, mouseX, mouseY));
        withLayer(graphics, Z_MENU, () -> renderTopMenu(graphics, mouseX, mouseY));
        if (contextOpen) {
            withLayer(graphics, Z_CONTEXT, () -> renderContextMenu(graphics, mouseX, mouseY));
        }
        if (settingsOpen) {
            withLayer(graphics, Z_SETTINGS, () -> renderSettings(graphics, mouseX, mouseY));
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
            int arm = Math.max(3, size / 2);
            int thick = Math.max(1, size / 6);
            graphics.fill(x - arm - 1, y - thick - 1, x + arm + 2, y + thick + 2, outline);
            graphics.fill(x - thick - 1, y - arm - 1, x + thick + 2, y + arm + 2, outline);
            graphics.fill(x - arm, y - thick, x + arm + 1, y + thick + 1, color);
            graphics.fill(x - thick, y - arm, x + thick + 1, y + arm + 1, color);
            return;
        }

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0f + minecraft.player.getYRot()));

        int h = Math.max(6, size);
        int half = Math.max(3, (size + 1) / 2);

        // One clean navigation triangle with a one-pixel dark outline.
        for (int row = 0; row < h; row++) {
            int yy = -h / 2 + row;
            int w = Math.max(0, (int) Math.round((row / (double) (h - 1)) * half));
            graphics.fill(-w - 1, yy, w + 2, yy + 1, outline);
        }
        for (int row = 1; row < h - 1; row++) {
            int yy = -h / 2 + row;
            int w = Math.max(0, (int) Math.round((row / (double) (h - 1)) * (half - 1)));
            graphics.fill(-w, yy, w + 1, yy + 1, color);
        }

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
        layoutMenuTables();
        collapsedMenuTable.render(graphics, font, mouseX, mouseY);
        if (menuExpanded) expandedMenuTable.render(graphics, font, mouseX, mouseY);
    }

    private void layoutMenuTables() {
        int menuX = width - MENU_MARGIN - MENU_W;
        int menuY = MENU_MARGIN;
        collapsedMenuTable.setBounds(menuX, menuY, MENU_W, MENU_H);

        int panelX = width - MENU_MARGIN - MENU_PANEL_W;
        int panelY = menuY + MENU_H + 4;
        expandedMenuTable.setBounds(panelX, panelY, MENU_PANEL_W, MENU_PANEL_H);

        contextMenuTable.setBounds(contextX, contextY, CONTEXT_W, CONTEXT_H);
    }

    private void renderSettings(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = width - MENU_MARGIN - SETTINGS_W;
        int y = MENU_MARGIN + MENU_H + 4;

        graphics.fill(x, y, x + SETTINGS_W, y + SETTINGS_H, 0xf21d2228);
        graphics.fill(x, y, x + SETTINGS_W, y + 30, 0xff2d343c);
        graphics.fill(x, y + 30, x + SETTINGS_TAB_W, y + SETTINGS_H, 0xff1f252b);
        graphics.fill(x + SETTINGS_TAB_W, y + 30, x + SETTINGS_W, y + SETTINGS_H, 0xff22282e);

        graphics.drawString(font, "设置", x + 12, y + 10, 0xffffffff, false);
        settingsCloseIcon.setBounds(x + SETTINGS_W - 26, y + 7, 16, 16);
        settingsCloseIcon.render(graphics, font, mouseX, mouseY);

        layoutSettingsTabs();
        updateSettingsTabStyle();
        settingsTabsTable.render(graphics, font, mouseX, mouseY);
        int activeTabY = y + 40 + settingsTab.ordinal() * 30;
        graphics.fill(x + 6, activeTabY + 4, x + 9, activeTabY + 26, 0xffffd166);

        int contentX = x + SETTINGS_TAB_W + 18;
        int contentY = y + 44;
        int contentRight = x + SETTINGS_W - 16;

        if (settingsTab == SettingsTab.PLAYER) {
            graphics.drawString(font, "玩家图标", contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 16, contentRight, contentY + 17, 0xff39424a);

            layoutSettingsControls();
            markerTypeSelect.setValue(MapUiSettings.markerType());
            markerColorInput.setValue(MapUiSettings.markerColor());
            markerSizeSlider.setValue(MapUiSettings.markerSize());

            int row1 = contentY + 30;
            int row2 = contentY + 66;
            int row3 = contentY + 102;

            graphics.drawString(font, "图标类型", contentX, row1 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "图标颜色", contentX, row2 + 7, 0xffd8dde2, false);
            graphics.drawString(font, "图标大小", contentX, row3 + 6, 0xffd8dde2, false);
            graphics.drawString(font, MapUiSettings.markerSize() + " px",
                    contentRight - 34, row3 + 6, 0xffb8c0c7, false);

            markerSizeSlider.render(graphics, font, mouseX, mouseY);
            markerTypeSelect.render(graphics, font, mouseX, mouseY);
            markerColorInput.render(graphics, font, mouseX, mouseY);
        } else {
            String title = settingsTab == SettingsTab.MAP ? "地图" : "操作";
            graphics.drawString(font, title, contentX, contentY, 0xfff0f3f5, false);
            graphics.fill(contentX, contentY + 16, contentRight, contentY + 17, 0xff39424a);
            emptyTabText.setBounds(contentX, contentY + 32,
                    contentRight - contentX, 60);
            emptyTabText.render(graphics, font, mouseX, mouseY);
        }
    }

    private void layoutSettingsTabs() {
        int x = width - MENU_MARGIN - SETTINGS_W;
        int y = MENU_MARGIN + MENU_H + 4;
        settingsTabsTable.setBounds(x + 6, y + 40, SETTINGS_TAB_W - 12, 90);
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
        int x = width - MENU_MARGIN - SETTINGS_W;
        int y = MENU_MARGIN + MENU_H + 4;
        int contentX = x + SETTINGS_TAB_W + 18;
        int contentY = y + 44;
        int controlX = x + SETTINGS_W - 166;

        markerTypeSelect.setBounds(controlX, contentY + 30, 150, 22);
        markerColorInput.setBounds(controlX, contentY + 66, 150, 22);
        markerSizeSlider.setBounds(controlX, contentY + 102, 118, 18);
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        contextMenuTable.setBounds(contextX, contextY, CONTEXT_W, CONTEXT_H);
        contextMenuTable.render(graphics, font, mouseX, mouseY);
    }

    private void teleportToContext() {
        PacketDistributor.sendToServer(new MapProtocol.TeleportRequest(
                dimension(), contextBlockX, contextBlockZ));
        contextOpen = false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        layoutMenuTables();
        layoutBottomHud();

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (settingsOpen) return true;
            if (collapsedMenuTable.contains(mouseX, mouseY)
                    || (menuExpanded && expandedMenuTable.contains(mouseX, mouseY))
                    || zoomHudTable.contains(mouseX, mouseY)
                    || locateHudTable.contains(mouseX, mouseY)) {
                return true;
            }

            double worldX = centerX + (mouseX - width * 0.5) / zoom;
            double worldZ = centerZ + (mouseY - height * 0.5) / zoom;
            contextBlockX = (int) Math.floor(worldX);
            contextBlockZ = (int) Math.floor(worldZ);
            contextX = Math.max(4, Math.min(width - CONTEXT_W - 4, mx));
            contextY = Math.max(4, Math.min(height - CONTEXT_H - 4, my));
            contextOpen = true;
            menuExpanded = false;
            dragging = false;
            return true;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Highest visual layer gets the first chance to consume input.
        if (settingsOpen) return handleSettingsClick(mx, my);

        if (contextOpen) {
            if (contextMenuTable.mouseClicked(mouseX, mouseY, button)) return true;
            contextOpen = false;
        }

        if (collapsedMenuTable.mouseClicked(mouseX, mouseY, button)) return true;
        if (menuExpanded && expandedMenuTable.mouseClicked(mouseX, mouseY, button)) return true;

        if (locateHudTable.mouseClicked(mouseX, mouseY, button)) return true;
        if (zoomHudTable.mouseClicked(mouseX, mouseY, button)) return true;

        dragging = true;
        return true;
    }

    private boolean handleSettingsClick(int mouseX, int mouseY) {
        int x = width - MENU_MARGIN - SETTINGS_W;
        int y = MENU_MARGIN + MENU_H + 4;

        layoutSettingsTabs();
        settingsCloseIcon.setBounds(x + SETTINGS_W - 26, y + 7, 16, 16);

        if (settingsTab == SettingsTab.PLAYER) {
            layoutSettingsControls();

            // Popups intentionally get input before panel bounds, because they can
            // extend beyond the normal content rectangle.
            if (markerColorInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                return true;
            }
            if (markerTypeSelect.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerColorInput.closePopup();
                return true;
            }
            if (markerSizeSlider.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
                markerTypeSelect.closePopup();
                markerColorInput.closePopup();
                return true;
            }
        }

        if (settingsCloseIcon.contains(mouseX, mouseY)) {
            closeSettings();
            return true;
        }

        if (settingsTabsTable.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            return true;
        }

        if (!inside(mouseX, mouseY, x, y, SETTINGS_W, SETTINGS_H)) {
            closeSettings();
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
        if (settingsOpen && settingsTab == SettingsTab.PLAYER) {
            if (markerColorInput.mouseReleased(mouseX, mouseY, button)) return true;
            if (markerSizeSlider.mouseReleased(mouseX, mouseY, button)) return true;
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
        if (settingsOpen) {
            if (settingsTab == SettingsTab.PLAYER) {
                if (markerColorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
                if (markerSizeSlider.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
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
        if (settingsOpen && settingsTab == SettingsTab.PLAYER
                && markerColorInput.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (settingsOpen || menuExpanded || contextOpen) {
                closeSettings();
                menuExpanded = false;
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
        return Math.round(value * 100.0) + "%";
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
