package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class MapMarkerUi {
    private enum ManagerTab { LIST, TRASH }

    private record ScreenMarker(MapMarkerStore.Marker marker, int x, int y) {}

    private record EditorLayout(
            int x, int y, int w, int h,
            int labelX, int controlX, int controlW,
            int nameY, int iconY, int colorY, int descriptionY, int descriptionH,
            int coordsY, int buttonY, int buttonH) {}

    private final MapUiControls.Input nameInput =
            new MapUiControls.Input(260, "", 64, value -> {});
    private final MapUiControls.MarkerIconSelect iconSelect =
            new MapUiControls.MarkerIconSelect(220, MapMarkerStore.Icon.PIN, value -> {});
    private final MapUiControls.ColorInput colorInput =
            new MapUiControls.ColorInput(180, 0xffd34e, value -> {});
    private final MapUiControls.TextArea descriptionInput =
            new MapUiControls.TextArea(320, 78, "", 512, value -> {});
    private final MapUiControls.Input xInput =
            new MapUiControls.Input(100, "0", 12, value -> {});
    private final MapUiControls.Input yInput =
            new MapUiControls.Input(100, "64", 12, value -> {});
    private final MapUiControls.Input zInput =
            new MapUiControls.Input(100, "0", 12, value -> {});

    private boolean editorOpen;
    private UUID editingId;
    private String editorDimension;

    private boolean managerOpen;
    private ManagerTab managerTab = ManagerTab.LIST;
    private int listScroll;
    private int managerX = -1;
    private int managerY = MapHifiUi.HEADER_H + 6;
    private int managerW = 300;
    private int managerH = 500;
    private boolean managerDragging;
    private int managerDragOffsetX;
    private int managerDragOffsetY;
    private long clearConfirmUntil;

    boolean overlayOpen() {
        return editorOpen;
    }

    boolean editorOpen() {
        return editorOpen;
    }

    boolean managerOpen() {
        return managerOpen;
    }

    void openCreate(String dimension, int x, int y, int z) {
        editingId = null;
        editorDimension = dimension;
        nameInput.setValue("");
        iconSelect.setValue(MapUiSettings.customMarkerDefaultIcon());
        colorInput.setValue(MapUiSettings.customMarkerDefaultColor());
        descriptionInput.setValue("");
        xInput.setValue(Integer.toString(x));
        yInput.setValue(Integer.toString(y));
        zInput.setValue(Integer.toString(z));
        editorOpen = true;
        closeEditorPopups();
    }

    void openEdit(MapMarkerStore.Marker marker) {
        openEdit(marker, marker == null ? 64 : marker.y);
    }

    void openEdit(MapMarkerStore.Marker marker, int resolvedY) {
        if (marker == null) return;
        editingId = marker.uuid();
        editorDimension = marker.dimension;
        nameInput.setValue(marker.name);
        iconSelect.setValue(marker.icon);
        colorInput.setValue(marker.accent());
        descriptionInput.setValue(marker.description);
        xInput.setValue(Integer.toString(marker.x));
        yInput.setValue(Integer.toString(resolvedY));
        zInput.setValue(Integer.toString(marker.z));
        editorOpen = true;
        closeEditorPopups();
    }

    void openManager(int anchorX, int anchorY, int width, int height) {
        editorOpen = false;
        managerOpen = true;
        managerTab = ManagerTab.LIST;
        listScroll = 0;
        if (managerX < 0) {
            managerX = clamp(anchorX - managerW / 2, 8, Math.max(8, width - managerW - 8));
            managerY = clamp(anchorY, MapHifiUi.HEADER_H + 4,
                    Math.max(MapHifiUi.HEADER_H + 4, height - managerH - 8));
        }
        clampManager(width, height);
    }

    void closeManager() {
        managerOpen = false;
        managerDragging = false;
    }

    void closeOverlay() {
        editorOpen = false;
        closeEditorPopups();
    }

    void closeAll() {
        closeOverlay();
        closeManager();
    }

    boolean managerContains(double mouseX, double mouseY) {
        return managerOpen && inside(mouseX, mouseY, managerX, managerY, managerW, managerH);
    }

    Optional<MapMarkerStore.Marker> markerAt(
            String dimension, double centerX, double centerZ, double zoom,
            int width, int height, double mouseX, double mouseY,
            boolean markerLayerEnabled, boolean structureLayerEnabled) {
        MapMarkerStore.Marker best = null;
        double bestDistance = Double.MAX_VALUE;
        int iconSize = markerIconSize(zoom);
        double radius = Math.max(6.0, iconSize * 0.65);
        double radiusSq = radius * radius;

        for (MapMarkerStore.Marker marker : MapMarkerStore.visible(dimension)) {
            if (!layerVisible(marker, markerLayerEnabled, structureLayerEnabled)) continue;
            double sx = width * 0.5 + (marker.x - centerX) * zoom;
            double sy = height * 0.5 + (marker.z - centerZ) * zoom;
            double dx = sx - mouseX;
            double dy = sy - mouseY;
            double dist = dx * dx + dy * dy;
            if (dist <= radiusSq && dist < bestDistance) {
                best = marker;
                bestDistance = dist;
            }
        }
        return Optional.ofNullable(best);
    }

    void renderLayer(
            GuiGraphics graphics, Font font,
            String dimension, double centerX, double centerZ, double zoom,
            int width, int height, int mouseX, int mouseY,
            boolean markerLayerEnabled, boolean structureLayerEnabled) {
        int iconSize = markerIconSize(zoom);
        int hoverRadius = Math.max(6, iconSize / 2 + 2);
        int margin = iconSize + 12;

        List<ScreenMarker> markers = new ArrayList<>();
        for (MapMarkerStore.Marker marker : MapMarkerStore.visible(dimension)) {
            if (!layerVisible(marker, markerLayerEnabled, structureLayerEnabled)) continue;
            int sx = (int) Math.round(width * 0.5 + (marker.x - centerX) * zoom);
            int sy = (int) Math.round(height * 0.5 + (marker.z - centerZ) * zoom);
            if (sx < -margin || sy < MapHifiUi.headerGui() - margin
                    || sx > width + margin || sy > height + margin) continue;
            markers.add(new ScreenMarker(marker, sx, sy));
        }
        markers.sort(Comparator.comparingInt(item -> customLayer(item.marker()) ? 1 : 0));

        List<int[]> iconRects = new ArrayList<>(markers.size());
        List<int[]> labelRects = new ArrayList<>();

        for (ScreenMarker item : markers) {
            MapMarkerStore.Marker marker = item.marker();
            int sx = item.x();
            int sy = item.y();
            boolean hovered = Math.abs(mouseX - sx) <= hoverRadius
                    && Math.abs(mouseY - sy) <= hoverRadius;
            MapIconAtlas.drawMarker(
                    graphics, marker.icon, sx, sy, iconSize,
                    marker.primary(), marker.accent(), marker.line(), hovered);
            iconRects.add(new int[] {
                    sx - iconSize / 2 - 2, sy - iconSize / 2 - 2,
                    sx + iconSize / 2 + 3, sy + iconSize / 2 + 3
            });
        }

        if (!MapUiSettings.labels()) return;
        if (MapUiSettings.hideLabelsLowZoom() && zoom < 0.25) return;

        for (int index = 0; index < markers.size(); index++) {
            ScreenMarker item = markers.get(index);
            MapMarkerStore.Marker marker = item.marker();
            int sx = item.x();
            int sy = item.y();
            boolean hovered = Math.abs(mouseX - sx) <= hoverRadius
                    && Math.abs(mouseY - sy) <= hoverRadius;
            int textW = font.width(marker.name);
            int lx = Math.max(2, Math.min(width - textW - 2, sx - textW / 2));
            int ly = sy + iconSize / 2 + 4;
            int[] rect = new int[] {
                    lx - 2, ly - 2, lx + textW + 3, ly + font.lineHeight + 2
            };

            if (MapUiSettings.avoidLabels() && !hovered) {
                boolean blocked = overlaps(labelRects, rect);
                if (!blocked) {
                    for (int i = 0; i < iconRects.size(); i++) {
                        if (i == index) continue;
                        int[] iconRect = iconRects.get(i);
                        if (rect[0] < iconRect[2] && rect[2] > iconRect[0]
                                && rect[1] < iconRect[3] && rect[3] > iconRect[1]) {
                            blocked = true;
                            break;
                        }
                    }
                }
                if (blocked) continue;
            }

            labelRects.add(rect);
            graphics.fill(rect[0], rect[1], rect[2], rect[3], 0xd0182229);
            graphics.drawString(font, marker.name, lx, ly, 0xfff0f3f5, true);
        }
    }

    private static boolean customLayer(MapMarkerStore.Marker marker) {
        return marker.origin == MapMarkerStore.Origin.CUSTOM
                || (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto);
    }

    private static boolean layerVisible(
            MapMarkerStore.Marker marker,
            boolean markerLayerEnabled,
            boolean structureLayerEnabled) {
        if (customLayer(marker)) return markerLayerEnabled;
        if (!structureLayerEnabled) return false;
        return MapUiSettings.structureVisible(
                MapMarkerStore.structurePreset(marker.structureId));
    }

    private static int markerIconSize(double zoom) {
        int base = MapUiSettings.customMarkerDefaultSize();
        double safeZoom = Math.max(0.0625, zoom);
        double log2 = Math.log(safeZoom) / Math.log(2.0);
        double strength = MapUiSettings.markerScalePercent() / 100.0;
        int size = (int) Math.round(base + log2 * 3.0 * strength);
        return Math.max(8, Math.min(32, size));
    }

    void renderManagerPanel(
            GuiGraphics graphics, Font font, int width, int height,
            int mouseX, int mouseY) {
        if (!managerOpen) return;
        clampManager(width, height);
        MapHifiUi.panel(graphics, managerX, managerY, managerW, managerH);
        MapHifiUi.panelHeader(
                graphics, font, managerX, managerY, managerW,
                MapIconAtlas.UiIcon.MARKERS, "标点", mouseX, mouseY);

        int bodyX = managerX + 10;
        int bodyY = managerY + MapHifiUi.PANEL_HEADER_H + 10;
        int innerW = managerW - 20;

        MapHifiUi.tab(graphics, font, bodyX, bodyY, (innerW - 4) / 2, 34,
                "标点 " + MapMarkerStore.customManagerEntries().size(),
                managerTab == ManagerTab.LIST, mouseX, mouseY);
        MapHifiUi.tab(graphics, font, bodyX + (innerW - 4) / 2 + 4, bodyY,
                (innerW - 4) / 2, 34,
                "回收站 " + MapMarkerStore.recycleCount(),
                managerTab == ManagerTab.TRASH, mouseX, mouseY);

        int toolY = bodyY + 42;
        if (managerTab == ManagerTab.LIST) {
            MapHifiUi.button(
                    graphics, font, bodyX, toolY, innerW, 34,
                    "＋ 添加标点", true, false, mouseX, mouseY);
        } else {
            String clearText = clearConfirmUntil > System.currentTimeMillis()
                    ? "再次点击确认清空" : "清空回收站";
            MapHifiUi.button(
                    graphics, font, bodyX + innerW - 122, toolY, 122, 34,
                    clearText, false, true, mouseX, mouseY);
        }

        List<MapMarkerStore.Marker> rows = managerRows();
        int listY = toolY + 42;
        int rowH = 56;
        int maxRows = Math.max(1, (managerY + managerH - 8 - listY) / rowH);
        int maxScroll = Math.max(0, rows.size() - maxRows);
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));

        if (rows.isEmpty()) {
            MapHifiUi.centeredText(
                    graphics, font,
                    managerTab == ManagerTab.LIST ? "暂无自定义标点" : "回收站为空",
                    managerX + managerW / 2, listY + 18, MapHifiUi.MUTED, 13);
            return;
        }

        for (int visible = 0; visible < maxRows; visible++) {
            int index = listScroll + visible;
            if (index >= rows.size()) break;
            MapMarkerStore.Marker marker = rows.get(index);
            int y = listY + visible * rowH;
            boolean hover = inside(mouseX, mouseY, bodyX, y, innerW, rowH - 3);
            if (hover) graphics.fill(bodyX, y, bodyX + innerW, y + rowH - 3, 0xff223039);

            MapIconAtlas.drawMarkerMono(
                    graphics, marker.icon, bodyX + 17, y + 20, 22,
                    marker.accent());
            MapHifiUi.text(
                    graphics, font, marker.name,
                    bodyX + 36, y + 9, MapHifiUi.TEXT, 14);
            String meta = "X " + marker.x + "  Y " + marker.y + "  Z " + marker.z;
            MapHifiUi.text(
                    graphics, font, meta,
                    bodyX + 36, y + 31, MapHifiUi.MUTED, 11);

            int actionX = bodyX + innerW - 20;
            if (managerTab == ManagerTab.TRASH) {
                MapIconAtlas.drawUi(
                        graphics, MapIconAtlas.UiIcon.RESTORE, actionX, y + 21, 16,
                        hover ? 0xffffffff : 0xffc5d0d6);
            } else if (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto) {
                MapIconAtlas.drawUi(
                        graphics, MapIconAtlas.UiIcon.RESET, actionX, y + 21, 16,
                        hover ? 0xffffffff : 0xffc5d0d6);
                MapIconAtlas.drawUi(
                        graphics, MapIconAtlas.UiIcon.EDIT, actionX - 30, y + 21, 16,
                        hover ? 0xffffffff : 0xffc5d0d6);
            } else {
                MapIconAtlas.drawUi(
                        graphics, MapIconAtlas.UiIcon.TRASH, actionX, y + 21, 16,
                        hover ? 0xffff8e8e : 0xffd27777);
                MapIconAtlas.drawUi(
                        graphics, MapIconAtlas.UiIcon.EDIT, actionX - 30, y + 21, 16,
                        hover ? 0xffffffff : 0xffc5d0d6);
            }
        }
    }

    void renderOverlay(
            GuiGraphics graphics, Font font, int width, int height,
            int mouseX, int mouseY) {
        if (!editorOpen) return;
        renderEditor(graphics, font, width, height, mouseX, mouseY);
    }

    private void renderEditor(
            GuiGraphics graphics, Font font, int width, int height,
            int mouseX, int mouseY) {
        EditorLayout l = editorLayout(width, height);
        MapHifiUi.modalShade(graphics, width, height);
        MapHifiUi.modal(graphics, l.x(), l.y(), l.w(), l.h());
        MapHifiUi.modalHeader(
                graphics, font, l.x(), l.y(), l.w(),
                MapIconAtlas.UiIcon.MARKERS,
                editingId == null ? "添加标点" : "编辑标点", mouseX, mouseY);

        MapHifiUi.text(graphics, font, "名称", l.labelX(), l.nameY() + 10, MapHifiUi.TEXT, 14);
        MapHifiUi.text(graphics, font, "图标", l.labelX(), l.iconY() + 10, MapHifiUi.TEXT, 14);
        MapHifiUi.text(graphics, font, "颜色", l.labelX(), l.colorY() + 10, MapHifiUi.TEXT, 14);
        MapHifiUi.text(graphics, font, "简介", l.labelX(), l.descriptionY() + 10, MapHifiUi.TEXT, 14);
        MapHifiUi.text(graphics, font, "坐标", l.labelX(), l.coordsY() + 10, MapHifiUi.TEXT, 14);

        nameInput.setBounds(l.controlX(), l.nameY(), l.controlW(), 34);
        iconSelect.setBounds(l.controlX(), l.iconY(), l.controlW(), 34);
        colorInput.setBounds(l.controlX(), l.colorY(), Math.min(230, l.controlW()), 34);
        descriptionInput.setBounds(l.controlX(), l.descriptionY(), l.controlW(), l.descriptionH());

        int coordGap = 5;
        int coordW = Math.max(50, (l.controlW() - coordGap * 2) / 3);
        int cx = l.controlX();
        xInput.setBounds(cx + 18, l.coordsY(), coordW - 18, 34);
        yInput.setBounds(cx + coordW + coordGap + 18, l.coordsY(), coordW - 18, 34);
        zInput.setBounds(cx + (coordW + coordGap) * 2 + 18, l.coordsY(), coordW - 18, 34);
        MapHifiUi.text(graphics, font, "X", cx + 3, l.coordsY() + 10, MapHifiUi.MUTED, 12);
        MapHifiUi.text(graphics, font, "Y",
                cx + coordW + coordGap + 3, l.coordsY() + 10, MapHifiUi.MUTED, 12);
        MapHifiUi.text(graphics, font, "Z",
                cx + (coordW + coordGap) * 2 + 3, l.coordsY() + 10, MapHifiUi.MUTED, 12);

        nameInput.render(graphics, font, mouseX, mouseY);
        descriptionInput.render(graphics, font, mouseX, mouseY);
        xInput.render(graphics, font, mouseX, mouseY);
        yInput.render(graphics, font, mouseX, mouseY);
        zInput.render(graphics, font, mouseX, mouseY);

        MapUiControls.Control popup = null;
        if (iconSelect.popupOpen()) popup = iconSelect;
        else iconSelect.render(graphics, font, mouseX, mouseY);
        if (colorInput.popupOpen()) popup = colorInput;
        else colorInput.render(graphics, font, mouseX, mouseY);

        int saveW = 78;
        int cancelW = 74;
        int saveX = l.x() + l.w() - 16 - saveW;
        int cancelX = saveX - 10 - cancelW;
        MapHifiUi.button(graphics, font, cancelX, l.buttonY(), cancelW, l.buttonH(),
                "取消", false, false, mouseX, mouseY);
        MapHifiUi.button(graphics, font, saveX, l.buttonY(), saveW, l.buttonH(),
                "保存", true, false, mouseX, mouseY);

        if (editingId != null) {
            Optional<MapMarkerStore.Marker> marker = MapMarkerStore.find(editingId);
            if (marker.isPresent()) {
                boolean modifiedAuto = marker.get().origin == MapMarkerStore.Origin.AUTO
                        && marker.get().modifiedAuto;
                String text = modifiedAuto ? "重置为自动" : "删除";
                MapHifiUi.button(
                        graphics, font, l.x() + 12, l.buttonY(),
                        modifiedAuto ? 84 : 58, l.buttonH(),
                        text, false, !modifiedAuto, mouseX, mouseY);
            }
        }

        if (popup != null) popup.render(graphics, font, mouseX, mouseY);
    }

    boolean mouseClicked(
            double mouseX, double mouseY, int button, int width, int height) {
        if (!editorOpen || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        return editorMouseClicked(mouseX, mouseY, width, height);
    }

    boolean managerMouseClicked(
            double mouseX, double mouseY, int button,
            int width, int height,
            String dimension, int centerX, int centerY, int centerZ) {
        if (!managerOpen) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return managerContains(mouseX, mouseY);

        if (MapHifiUi.closeHit(mouseX, mouseY, managerX, managerY, managerW)) {
            closeManager();
            return true;
        }

        if (inside(mouseX, mouseY, managerX, managerY, managerW, MapHifiUi.PANEL_HEADER_H)) {
            managerDragging = true;
            managerDragOffsetX = (int) mouseX - managerX;
            managerDragOffsetY = (int) mouseY - managerY;
            return true;
        }

        int bodyX = managerX + 10;
        int bodyY = managerY + MapHifiUi.PANEL_HEADER_H + 10;
        int innerW = managerW - 20;
        int tabW = (innerW - 4) / 2;
        if (inside(mouseX, mouseY, bodyX, bodyY, tabW, 34)) {
            managerTab = ManagerTab.LIST;
            listScroll = 0;
            return true;
        }
        if (inside(mouseX, mouseY, bodyX + tabW + 4, bodyY, tabW, 34)) {
            managerTab = ManagerTab.TRASH;
            listScroll = 0;
            return true;
        }

        int toolY = bodyY + 42;
        if (managerTab == ManagerTab.LIST
                && inside(mouseX, mouseY, bodyX, toolY, innerW, 34)) {
            openCreate(dimension, centerX, centerY, centerZ);
            return true;
        }
        if (managerTab == ManagerTab.TRASH
                && inside(mouseX, mouseY, bodyX + innerW - 122, toolY, 122, 34)) {
            long now = System.currentTimeMillis();
            if (clearConfirmUntil > now) {
                MapMarkerStore.clearRecycle();
                clearConfirmUntil = 0;
                listScroll = 0;
            } else {
                clearConfirmUntil = now + 2500;
            }
            return true;
        }

        List<MapMarkerStore.Marker> rows = managerRows();
        int listY = toolY + 42;
        int rowH = 56;
        int visible = (int) ((mouseY - listY) / rowH);
        int index = listScroll + visible;
        if (mouseY < listY || index < 0 || index >= rows.size()) return true;

        MapMarkerStore.Marker marker = rows.get(index);
        int rowY = listY + visible * rowH;
        if (mouseY >= rowY + rowH - 3) return true;
        int actionX = bodyX + innerW - 20;

        if (managerTab == ManagerTab.TRASH) {
            if (inside(mouseX, mouseY, actionX - 12, rowY + 8, 24, 28)) {
                MapMarkerStore.restore(marker.uuid());
            }
            return true;
        }

        boolean modifiedAuto = marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto;
        if (inside(mouseX, mouseY, actionX - 12, rowY + 8, 24, 28)) {
            if (modifiedAuto) MapMarkerStore.resetAutomatic(marker.uuid());
            else MapMarkerStore.delete(marker.uuid());
            return true;
        }
        if (inside(mouseX, mouseY, actionX - 42, rowY + 8, 24, 28)) {
            openEdit(marker);
            return true;
        }
        openEdit(marker);
        return true;
    }

    boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY,
            int width, int height) {
        if (editorOpen) {
            if (colorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            if (descriptionInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            return true;
        }
        if (managerOpen && managerDragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            managerX = (int) mouseX - managerDragOffsetX;
            managerY = (int) mouseY - managerDragOffsetY;
            clampManager(width, height);
            return true;
        }
        return false;
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (editorOpen) {
            if (colorInput.mouseReleased(mouseX, mouseY, button)) return true;
            return true;
        }
        if (managerOpen && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && managerDragging) {
            managerDragging = false;
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double scrollY, int width, int height) {
        if (editorOpen) {
            if (iconSelect.popupOpen() && iconSelect.mouseScrolled(scrollY)) return true;
            return true;
        }
        if (!managerOpen) return false;
        if (scrollY != 0) {
            int delta = scrollY > 0 ? -1 : 1;
            listScroll = Math.max(0, listScroll + delta);
        }
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!editorOpen) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeOverlay();
            return true;
        }
        if (nameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (descriptionInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (xInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (yInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (zInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (colorInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        return true;
    }

    boolean charTyped(char codePoint, int modifiers) {
        if (!editorOpen) return false;
        if (nameInput.charTyped(codePoint, modifiers)) return true;
        if (descriptionInput.charTyped(codePoint, modifiers)) return true;
        if (xInput.charTyped(codePoint, modifiers)) return true;
        if (yInput.charTyped(codePoint, modifiers)) return true;
        if (zInput.charTyped(codePoint, modifiers)) return true;
        if (colorInput.charTyped(codePoint, modifiers)) return true;
        return true;
    }

    private boolean editorMouseClicked(double mouseX, double mouseY, int width, int height) {
        EditorLayout l = editorLayout(width, height);

        if (MapHifiUi.modalCloseHit(mouseX, mouseY, l.x(), l.y(), l.w())) {
            closeOverlay();
            return true;
        }

        if (iconSelect.popupOpen()) {
            if (iconSelect.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
            iconSelect.closePopup();
        }
        if (colorInput.popupOpen()) {
            if (colorInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
            colorInput.closePopup();
        }

        if (iconSelect.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            colorInput.closePopup();
            return true;
        }
        if (colorInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            iconSelect.closePopup();
            return true;
        }
        if (nameInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
        if (descriptionInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
        if (xInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
        if (yInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
        if (zInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;

        int saveW = 78;
        int cancelW = 74;
        int saveX = l.x() + l.w() - 16 - saveW;
        int cancelX = saveX - 10 - cancelW;
        if (inside(mouseX, mouseY, cancelX, l.buttonY(), cancelW, l.buttonH())) {
            closeOverlay();
            return true;
        }
        if (inside(mouseX, mouseY, saveX, l.buttonY(), saveW, l.buttonH())) {
            saveEditor();
            return true;
        }

        if (editingId != null) {
            Optional<MapMarkerStore.Marker> marker = MapMarkerStore.find(editingId);
            if (marker.isPresent()) {
                boolean modifiedAuto = marker.get().origin == MapMarkerStore.Origin.AUTO
                        && marker.get().modifiedAuto;
                int actionW = modifiedAuto ? 112 : 74;
                if (inside(mouseX, mouseY, l.x() + 12, l.buttonY(), actionW, l.buttonH())) {
                    if (modifiedAuto) MapMarkerStore.resetAutomatic(editingId);
                    else MapMarkerStore.delete(editingId);
                    closeOverlay();
                    return true;
                }
            }
        }

        // Modal consumes every click inside or outside the card.
        return true;
    }

    private void saveEditor() {
        Integer x = parseInt(xInput.value());
        Integer y = parseInt(yInput.value());
        Integer z = parseInt(zInput.value());
        if (x == null || y == null || z == null) return;

        String name = nameInput.value();
        MapMarkerStore.Icon icon = iconSelect.value();
        int color = colorInput.value();
        String description = descriptionInput.value();

        if (editingId == null) {
            MapMarkerStore.createCustom(
                    editorDimension, x, y, z,
                    name, icon, color, color, color, description);
        } else {
            MapMarkerStore.update(
                    editingId, x, y, z,
                    name, icon, color, color, color, description);
        }
        closeOverlay();
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static EditorLayout editorLayout(int width, int height) {
        int w = Math.max(420, Math.min(580, width - 40));
        int availableH = Math.max(380, height - MapHifiUi.HEADER_H - 28);
        int h = Math.min(480, availableH);
        int x = (width - w) / 2;
        int y = MapHifiUi.HEADER_H
                + Math.max(12, (height - MapHifiUi.HEADER_H - h) / 2);

        int labelX = x + 24;
        int controlX = x + 112;
        int controlW = w - 136;
        int nameY = y + MapHifiUi.MODAL_HEADER_H + 22;
        int iconY = nameY + 48;
        int colorY = iconY + 48;
        int descriptionY = colorY + 48;
        int buttonH = 36;
        int buttonY = y + h - 52;
        int coordsY = buttonY - 48;
        int descriptionH = Math.max(70, coordsY - descriptionY - 12);
        return new EditorLayout(
                x, y, w, h, labelX, controlX, controlW,
                nameY, iconY, colorY, descriptionY, descriptionH,
                coordsY, buttonY, buttonH);
    }

    private List<MapMarkerStore.Marker> managerRows() {
        return managerTab == ManagerTab.LIST
                ? MapMarkerStore.customManagerEntries()
                : MapMarkerStore.recycleAll();
    }

    private void clampManager(int width, int height) {
        managerH = Math.min(560, Math.max(360, height - MapHifiUi.HEADER_H - 28));
        managerX = clamp(managerX, 6, Math.max(6, width - managerW - 6));
        managerY = clamp(managerY, MapHifiUi.HEADER_H + 4,
                Math.max(MapHifiUi.HEADER_H + 4, height - managerH - 6));
    }

    private void closeEditorPopups() {
        iconSelect.closePopup();
        colorInput.closePopup();
    }

    private static boolean overlaps(List<int[]> occupied, int[] rect) {
        for (int[] other : occupied) {
            if (rect[0] < other[2] && rect[2] > other[0]
                    && rect[1] < other[3] && rect[3] > other[1]) return true;
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return MapHifiUi.inside(x, y, rx, ry, rw, rh);
    }
}
