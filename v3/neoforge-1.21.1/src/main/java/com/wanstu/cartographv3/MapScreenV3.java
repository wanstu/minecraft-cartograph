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

    private static final int SETTINGS_W = 280;
    private static final int SETTINGS_H = 145;

    private static final int CONTEXT_W = 92;
    private static final int CONTEXT_H = 26;

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
            renderPlayerMarker(graphics);
        }

        if (contextOpen) renderContextMenu(graphics, mouseX, mouseY);
        renderTopMenu(graphics, mouseX, mouseY);
        if (settingsOpen) renderSettings(graphics, mouseX, mouseY);
    }

    private void renderPlayerMarker(GuiGraphics graphics) {
        if (minecraft.player == null) return;

        double sx = width * 0.5 + (minecraft.player.getX() - centerX) * zoom;
        double sy = height * 0.5 + (minecraft.player.getZ() - centerZ) * zoom;
        int size = MapUiSettings.markerSize();

        if (sx < -size || sy < -size || sx > width + size || sy > height + size) return;

        int color = 0xff000000 | MapUiSettings.markerColor();
        int outline = 0xcc000000;
        int x = (int) Math.round(sx);
        int y = (int) Math.round(sy);

        if (MapUiSettings.markerType() == MapUiSettings.MarkerType.CROSS) {
            int arm = Math.max(3, size / 2);
            int thick = Math.max(2, size / 5);
            graphics.fill(x - arm - 1, y - thick - 1, x + arm + 2, y + thick + 2, outline);
            graphics.fill(x - thick - 1, y - arm - 1, x + thick + 2, y + arm + 2, outline);
            graphics.fill(x - arm, y - thick, x + arm + 1, y + thick + 1, color);
            graphics.fill(x - thick, y - arm, x + thick + 1, y + arm + 1, color);
            return;
        }

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 220.0);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0f + minecraft.player.getYRot()));

        int half = Math.max(4, size / 2);
        int head = Math.max(3, size / 3);
        int shaft = Math.max(1, size / 7);

        graphics.fill(-head - 1, -half - 1, head + 2, -half + 2, outline);
        graphics.fill(-(head - 1) - 1, -half + 1, (head - 1) + 2, -half + 4, outline);
        graphics.fill(-shaft - 1, -half + 3, shaft + 2, half + 2, outline);

        graphics.fill(-head, -half, head + 1, -half + 1, color);
        graphics.fill(-(head - 1), -half + 1, head, -half + 3, color);
        graphics.fill(-shaft, -half + 3, shaft + 1, half + 1, color);

        pose.popPose();
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
        graphics.fill(x, y, x + SETTINGS_W, y + 26, 0xff2c333b);
        graphics.drawString(font, "设置", x + 10, y + 9, 0xffffffff, false);
        graphics.drawString(font, "X", x + SETTINGS_W - 16, y + 9, 0xffcfd6dc, false);

        layoutSettingsControls();
        markerTypeSelect.setValue(MapUiSettings.markerType());
        markerColorInput.setValue(MapUiSettings.markerColor());
        markerSizeSlider.setValue(MapUiSettings.markerSize());

        int typeY = y + 39;
        int colorY = y + 70;
        int sizeY = y + 101;

        graphics.drawString(font, "玩家图标类型", x + 12, typeY + 7, 0xffd8dde2, false);
        graphics.drawString(font, "玩家图标颜色", x + 12, colorY + 7, 0xffd8dde2, false);
        graphics.drawString(font, "玩家图标大小", x + 12, sizeY + 6, 0xffd8dde2, false);
        graphics.drawString(font, MapUiSettings.markerSize() + " px",
                x + SETTINGS_W - 42, sizeY + 6, 0xffd8dde2, false);

        markerSizeSlider.render(graphics, font, mouseX, mouseY);
        markerTypeSelect.render(graphics, font, mouseX, mouseY);
        // ColorInput owns its floating picker and renders last so it stays above the panel.
        markerColorInput.render(graphics, font, mouseX, mouseY);
    }

    private void layoutSettingsControls() {
        int x = width - MENU_MARGIN - SETTINGS_W;
        int y = MENU_MARGIN + MENU_H + 4;
        markerTypeSelect.setBounds(x + 178, y + 39, 86, 22);
        markerColorInput.setBounds(x + 178, y + 70, 86, 22);
        markerSizeSlider.setBounds(x + 92, y + 101, 126, 18);
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

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (settingsOpen) return true;
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

        layoutMenuTables();
        if (contextOpen) {
            if (contextMenuTable.mouseClicked(mouseX, mouseY, button)) return true;
            contextOpen = false;
        }

        if (collapsedMenuTable.mouseClicked(mouseX, mouseY, button)) return true;
        if (menuExpanded && expandedMenuTable.mouseClicked(mouseX, mouseY, button)) return true;

        if (settingsOpen) {
            if (handleSettingsClick(mx, my)) return true;
            return true;
        }

        dragging = true;
        return true;
    }

    private boolean handleSettingsClick(int mouseX, int mouseY) {
        layoutSettingsControls();
        int x = width - MENU_MARGIN - SETTINGS_W;
        int y = MENU_MARGIN + MENU_H + 4;

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

        if (inside(mouseX, mouseY, x + SETTINGS_W - 28, y, 28, 26)) {
            closeSettings();
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
        if (settingsOpen) {
            if (markerColorInput.mouseReleased(mouseX, mouseY, button)) return true;
            if (markerSizeSlider.mouseReleased(mouseX, mouseY, button)) return true;
        }
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
            if (markerColorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            if (markerSizeSlider.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            return true;
        }
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

        double worldAtMouseX = centerX + (mouseX - width * 0.5) / zoom;
        double worldAtMouseZ = centerZ + (mouseY - height * 0.5) / zoom;
        double next = Math.max(0.5, Math.min(8.0,
                zoom * (scrollY > 0 ? 1.35 : 1.0 / 1.35)));
        centerX = worldAtMouseX - (mouseX - width * 0.5) / next;
        centerZ = worldAtMouseZ - (mouseY - height * 0.5) / next;
        zoom = next;

        if (Boolean.getBoolean("cartograph.v3.qaInput")) {
            LOG.info("V3_QA_SCROLL center={},{} zoom={}", centerX, centerZ, zoom);
        }
        requestVisible(false);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (settingsOpen && markerColorInput.keyPressed(keyCode, scanCode, modifiers)) {
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
        if (settingsOpen && markerColorInput.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
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
