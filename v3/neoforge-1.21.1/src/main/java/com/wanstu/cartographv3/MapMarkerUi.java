package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.*;

final class MapMarkerUi {
    private enum ManagerTab { LIST, TRASH }
    private enum TrashKind { CUSTOM, AUTO }

    private record EditorLayout(
            int x, int y, int w, int h,
            int labelX, int controlX, int controlW,
            int nameY, int iconY,
            int primaryColorY, int accentColorY, int lineColorY,
            int descriptionY, int descriptionH,
            int locationY, int buttonY, int buttonH) {}


    private record ScreenMarker(
            MapMarkerStore.Marker marker, int x, int y) {}

    private final MapUiControls.Input nameInput;
    private final MapUiControls.MarkerIconSelect iconSelect;
    private final MapUiControls.ColorInput primaryColorInput;
    private final MapUiControls.ColorInput accentColorInput;
    private final MapUiControls.ColorInput lineColorInput;
    private final MapUiControls.TextArea descriptionInput;

    private boolean editorOpen;
    private UUID editingId;
    private String editorDimension;
    private int editorX;
    private int editorZ;

    private boolean managerOpen;
    private ManagerTab managerTab = ManagerTab.LIST;
    private TrashKind trashKind = TrashKind.CUSTOM;
    private int listScroll;

    MapMarkerUi() {
        nameInput = new MapUiControls.Input(220, "", 64, value -> {});
        iconSelect = new MapUiControls.MarkerIconSelect(
                180, MapMarkerStore.Icon.PIN, value -> {});
        primaryColorInput = new MapUiControls.ColorInput(
                160, MapMarkerStore.DEFAULT_PRIMARY_COLOR, value -> {});
        accentColorInput = new MapUiControls.ColorInput(160, 0xe53935, value -> {});
        lineColorInput = new MapUiControls.ColorInput(
                160, MapMarkerStore.DEFAULT_LINE_COLOR, value -> {});
        descriptionInput = new MapUiControls.TextArea(300, 82, "", 512, value -> {});
    }

    boolean overlayOpen() {
        return editorOpen || managerOpen;
    }

    boolean editorOpen() {
        return editorOpen;
    }

    boolean managerOpen() {
        return managerOpen;
    }

    void openCreate(String dimension, int x, int z) {
        editingId = null;
        editorDimension = dimension;
        editorX = x;
        editorZ = z;
        nameInput.setValue("");
        iconSelect.setValue(MapMarkerStore.Icon.PIN);
        primaryColorInput.setValue(MapMarkerStore.DEFAULT_PRIMARY_COLOR);
        accentColorInput.setValue(0xe53935);
        lineColorInput.setValue(MapMarkerStore.DEFAULT_LINE_COLOR);
        descriptionInput.setValue("");
        editorOpen = true;
        managerOpen = false;
    }

    void openEdit(MapMarkerStore.Marker marker) {
        if (marker == null) return;
        editingId = marker.uuid();
        editorDimension = marker.dimension;
        editorX = marker.x;
        editorZ = marker.z;
        nameInput.setValue(marker.name);
        iconSelect.setValue(marker.icon);
        primaryColorInput.setValue(marker.primary());
        accentColorInput.setValue(marker.accent());
        lineColorInput.setValue(marker.line());
        descriptionInput.setValue(marker.description);
        editorOpen = true;
        managerOpen = false;
    }

    void openManager() {
        editorOpen = false;
        managerOpen = true;
        managerTab = ManagerTab.LIST;
        listScroll = 0;
    }

    void closeOverlay() {
        editorOpen = false;
        managerOpen = false;
        iconSelect.closePopup();
        primaryColorInput.closePopup();
        accentColorInput.closePopup();
        lineColorInput.closePopup();
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

        // Cull before sorting. Large worlds can retain many markers that are
        // nowhere near the current viewport; they should not participate in
        // per-frame sorting or label layout.
        List<ScreenMarker> markers = new ArrayList<>();
        for (MapMarkerStore.Marker marker : MapMarkerStore.visible(dimension)) {
            if (!layerVisible(marker, markerLayerEnabled, structureLayerEnabled)) {
                continue;
            }
            int sx = (int) Math.round(
                    width * 0.5 + (marker.x - centerX) * zoom);
            int sy = (int) Math.round(
                    height * 0.5 + (marker.z - centerZ) * zoom);
            if (sx < -margin || sy < -margin
                    || sx > width + margin || sy > height + margin) {
                continue;
            }
            markers.add(new ScreenMarker(marker, sx, sy));
        }

        markers.sort(Comparator.comparingInt(
                item -> customLayer(item.marker()) ? 1 : 0));

        List<int[]> iconRects = new ArrayList<>(markers.size());
        List<int[]> labelRects = new ArrayList<>();

        for (ScreenMarker item : markers) {
            MapMarkerStore.Marker marker = item.marker();
            int sx = item.x();
            int sy = item.y();
            boolean hovered = Math.abs(mouseX - sx) <= hoverRadius
                    && Math.abs(mouseY - sy) <= hoverRadius;
            drawMarkerIcon(
                    graphics, marker.icon, sx, sy,
                    marker.primary(), marker.accent(), marker.line(),
                    hovered, iconSize);
            iconRects.add(new int[] {
                    sx - iconSize / 2 - 2,
                    sy - iconSize / 2 - 2,
                    sx + iconSize / 2 + 3,
                    sy + iconSize / 2 + 3
            });
        }

        if (zoom < 0.25) return;

        for (int index = 0; index < markers.size(); index++) {
            ScreenMarker item = markers.get(index);
            MapMarkerStore.Marker marker = item.marker();
            int sx = item.x();
            int sy = item.y();

            boolean hovered = Math.abs(mouseX - sx) <= hoverRadius
                    && Math.abs(mouseY - sy) <= hoverRadius;
            int textW = font.width(marker.name);
            int lx = Math.max(
                    2, Math.min(width - textW - 2, sx - textW / 2));
            int ly = sy + iconSize / 2 + 4;
            int[] rect = new int[] {
                    lx - 2, ly - 2,
                    lx + textW + 3, ly + font.lineHeight + 2
            };

            if (!hovered) {
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
            graphics.fill(
                    rect[0], rect[1], rect[2], rect[3], 0xc0181c20);
            graphics.drawString(
                    font, marker.name, lx, ly, 0xfff0f3f5, true);
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
        return customLayer(marker) ? markerLayerEnabled : structureLayerEnabled;
    }

    private static int markerIconSize(double zoom) {
        double safeZoom = Math.max(0.0625, zoom);
        double log2 = Math.log(safeZoom) / Math.log(2.0);
        return Math.max(8, Math.min(22, (int) Math.round(14.0 + log2 * 1.5)));
    }

    void renderOverlay(GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        if (managerOpen) renderManager(graphics, font, width, height, mouseX, mouseY);
        if (editorOpen) renderEditor(graphics, font, width, height, mouseX, mouseY);
    }

    private void renderEditor(
            GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        EditorLayout l = editorLayout(width, height);

        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(l.x(), l.y(), l.x() + l.w(), l.y() + l.h(), 0xff20262c);
        int headerH = Math.min(34, Math.max(24, l.nameY() - l.y() - 8));
        graphics.fill(l.x(), l.y(), l.x() + l.w(), l.y() + headerH, 0xff303840);
        graphics.drawString(font, editingId == null ? "增加标点" : "编辑标点",
                l.x() + 12, l.y() + Math.max(7, (headerH - font.lineHeight) / 2),
                0xffffffff, false);

        graphics.drawString(font, "名字", l.labelX(), l.nameY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "图标", l.labelX(), l.iconY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "主色", l.labelX(), l.primaryColorY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "辅色", l.labelX(), l.accentColorY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "边框色", l.labelX(), l.lineColorY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "简介", l.labelX(), l.descriptionY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "位置  " + editorX + ", " + editorZ,
                l.labelX(), l.locationY(), 0xff8f989f, false);

        nameInput.setBounds(l.controlX(), l.nameY(), l.controlW(), 22);
        iconSelect.setBounds(l.controlX(), l.iconY(), Math.min(220, l.controlW()), 22);
        primaryColorInput.setBounds(
                l.controlX(), l.primaryColorY(), Math.min(220, l.controlW()), 22);
        accentColorInput.setBounds(
                l.controlX(), l.accentColorY(), Math.min(220, l.controlW()), 22);
        lineColorInput.setBounds(
                l.controlX(), l.lineColorY(), Math.min(220, l.controlW()), 22);
        descriptionInput.setBounds(
                l.controlX(), l.descriptionY(), l.controlW(), l.descriptionH());

        nameInput.render(graphics, font, mouseX, mouseY);
        descriptionInput.render(graphics, font, mouseX, mouseY);

        MapUiControls.Control activePopup = null;
        MapUiControls.Control[] popupControls = {
                iconSelect,
                primaryColorInput,
                accentColorInput,
                lineColorInput
        };
        for (MapUiControls.Control control : popupControls) {
            if (control.popupOpen()) {
                activePopup = control;
            } else {
                control.render(graphics, font, mouseX, mouseY);
            }
        }

        int saveW = 52;
        int cancelW = 52;
        int saveX = l.x() + l.w() - 10 - saveW;
        int cancelX = saveX - 6 - cancelW;
        drawButton(graphics, font, cancelX, l.buttonY(), cancelW, l.buttonH(), "取消",
                mouseX, mouseY, false);
        drawButton(graphics, font, saveX, l.buttonY(), saveW, l.buttonH(), "保存",
                mouseX, mouseY, true);

        if (editingId != null) {
            Optional<MapMarkerStore.Marker> marker = MapMarkerStore.find(editingId);
            if (marker.isPresent()) {
                drawButton(graphics, font, l.x() + 10, l.buttonY(), 52, l.buttonH(), "删除",
                        mouseX, mouseY, false);
                if (marker.get().origin == MapMarkerStore.Origin.AUTO && marker.get().modifiedAuto) {
                    drawButton(graphics, font, l.x() + 68, l.buttonY(), 72, l.buttonH(), "重置自动",
                            mouseX, mouseY, false);
                }
            }
        }

        if (activePopup != null) {
            activePopup.render(graphics, font, mouseX, mouseY);
        }
    }

    private void renderManager(
            GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, 0xff181d22);
        graphics.fill(0, 0, width, 38, 0xff2d343c);
        graphics.drawString(font, "自定义标点", 16, 14, 0xffffffff, false);

        int closeX = width - 30;
        int closeY = 8;
        boolean closeHover = inside(mouseX, mouseY, closeX, closeY, 22, 22);
        if (closeHover) {
            graphics.fill(closeX, closeY, closeX + 22, closeY + 22, 0xff3b444c);
        }
        MapIconAtlas.drawUi(
                graphics, MapIconAtlas.UiIcon.CLOSE,
                closeX + 11, closeY + 11, 14,
                closeHover ? 0xffffff : 0xd8dde2);

        int sideW = 118;
        graphics.fill(0, 38, sideW, height, 0xff20262c);
        drawSideTab(graphics, font, 8, 54, sideW - 16, 28, "标点列表",
                managerTab == ManagerTab.LIST, mouseX, mouseY);
        drawSideTab(graphics, font, 8, 88, sideW - 16, 28, "回收站",
                managerTab == ManagerTab.TRASH, mouseX, mouseY);

        int contentX = sideW + 20;
        int contentY = 56;
        int contentW = width - contentX - 20;

        if (managerTab == ManagerTab.TRASH) {
            drawButton(graphics, font, contentX, contentY, 86, 24, "自定义标点",
                    mouseX, mouseY, trashKind == TrashKind.CUSTOM);
            drawButton(graphics, font, contentX + 94, contentY, 86, 24, "自动标点",
                    mouseX, mouseY, trashKind == TrashKind.AUTO);
            contentY += 38;
        }

        List<MapMarkerStore.Marker> rows = managerRows();
        int rowH = 40;
        int maxRows = Math.max(1, (height - contentY - 20) / rowH);
        int maxScroll = Math.max(0, rows.size() - maxRows);
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));

        if (rows.isEmpty()) {
            graphics.drawString(font,
                    managerTab == ManagerTab.LIST ? "暂无自定义标点" : "回收站为空",
                    contentX, contentY + 8, 0xff8f989f, false);
            return;
        }

        for (int visible = 0; visible < maxRows; visible++) {
            int index = listScroll + visible;
            if (index >= rows.size()) break;
            MapMarkerStore.Marker marker = rows.get(index);
            int y = contentY + visible * rowH;
            boolean hover = mouseY >= y && mouseY < y + rowH
                    && mouseX >= contentX && mouseX < contentX + contentW;
            graphics.fill(contentX, y, contentX + contentW, y + rowH - 3,
                    hover ? 0xff2d353d : 0xff22292f);

            drawMarkerIcon(
                    graphics, marker.icon,
                    contentX + 16, y + 18,
                    marker.primary(), marker.accent(), marker.line(),
                    false, 18);
            graphics.drawString(
                    font, marker.name,
                    contentX + 32, y + 7,
                    0xffedf1f4, false);

            String origin = marker.origin == MapMarkerStore.Origin.AUTO
                    ? (marker.modifiedAuto ? "自动→自定义" : "自动") : "自定义";
            String meta = origin + " · " + marker.x + ", " + marker.z;
            graphics.drawString(
                    font, meta,
                    contentX + 32, y + 23,
                    0xff8f989f, false);

            int deleteX = contentX + contentW - 38;
            if (managerTab == ManagerTab.LIST) {
                int resetX = deleteX - 42;
                int editX = (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto)
                        ? resetX - 42 : deleteX - 42;
                drawActionText(graphics, font, editX, y + 23,
                        "编辑", mouseX, mouseY);
                if (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto) {
                    drawActionText(graphics, font, resetX, y + 23,
                            "重置", mouseX, mouseY);
                }
                drawActionText(graphics, font, deleteX, y + 23,
                        "删除", mouseX, mouseY);
            } else {
                drawActionText(graphics, font, deleteX, y + 23,
                        "恢复", mouseX, mouseY);
            }
        }
    }

    boolean mouseClicked(
            double mouseX, double mouseY, int button, int width, int height) {
        if (!overlayOpen() || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (editorOpen) return editorMouseClicked(mouseX, mouseY, width, height);
        if (managerOpen) return managerMouseClicked(mouseX, mouseY, width, height);
        return false;
    }

    boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!editorOpen) return overlayOpen();
        if (primaryColorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (accentColorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (lineColorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (descriptionInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        return true;
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!editorOpen) return overlayOpen();
        if (primaryColorInput.mouseReleased(mouseX, mouseY, button)) return true;
        if (accentColorInput.mouseReleased(mouseX, mouseY, button)) return true;
        if (lineColorInput.mouseReleased(mouseX, mouseY, button)) return true;
        return true;
    }

    boolean mouseScrolled(double scrollY, int width, int height) {
        if (!managerOpen) return overlayOpen();
        if (scrollY != 0) {
            int delta = scrollY > 0 ? -3 : 3;
            listScroll = Math.max(0, listScroll + delta);
        }
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!overlayOpen()) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (editorOpen) {
                editorOpen = false;
                iconSelect.closePopup();
                primaryColorInput.closePopup();
                accentColorInput.closePopup();
                lineColorInput.closePopup();
            } else {
                managerOpen = false;
            }
            return true;
        }
        if (editorOpen) {
            if (nameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (descriptionInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (primaryColorInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (accentColorInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (lineColorInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return true;
    }

    boolean charTyped(char codePoint, int modifiers) {
        if (!editorOpen) return overlayOpen();
        if (nameInput.charTyped(codePoint, modifiers)) return true;
        if (descriptionInput.charTyped(codePoint, modifiers)) return true;
        if (primaryColorInput.charTyped(codePoint, modifiers)) return true;
        if (accentColorInput.charTyped(codePoint, modifiers)) return true;
        if (lineColorInput.charTyped(codePoint, modifiers)) return true;
        return true;
    }

    private boolean editorMouseClicked(double mouseX, double mouseY, int width, int height) {
        EditorLayout l = editorLayout(width, height);

        // Open popups get first chance to consume the click. This prevents a
        // color picker behind the icon grid from stealing icon-cell clicks.
        if (iconSelect.popupOpen()) {
            if (iconSelect.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
            iconSelect.closePopup();
        }
        if (primaryColorInput.popupOpen()) {
            if (primaryColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
            primaryColorInput.closePopup();
        }
        if (accentColorInput.popupOpen()) {
            if (accentColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
            accentColorInput.closePopup();
        }
        if (lineColorInput.popupOpen()) {
            if (lineColorInput.mouseClicked(
                    mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
            lineColorInput.closePopup();
        }

        if (iconSelect.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            primaryColorInput.closePopup();
            accentColorInput.closePopup();
            lineColorInput.closePopup();
            return true;
        }
        if (primaryColorInput.mouseClicked(
                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            iconSelect.closePopup();
            accentColorInput.closePopup();
            lineColorInput.closePopup();
            return true;
        }
        if (accentColorInput.mouseClicked(
                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            iconSelect.closePopup();
            primaryColorInput.closePopup();
            lineColorInput.closePopup();
            return true;
        }
        if (lineColorInput.mouseClicked(
                mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            iconSelect.closePopup();
            primaryColorInput.closePopup();
            accentColorInput.closePopup();
            return true;
        }
        if (nameInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
        if (descriptionInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;

        int saveW = 52;
        int cancelW = 52;
        int saveX = l.x() + l.w() - 10 - saveW;
        int cancelX = saveX - 6 - cancelW;
        if (inside(mouseX, mouseY, cancelX, l.buttonY(), cancelW, l.buttonH())) {
            editorOpen = false;
            return true;
        }
        if (inside(mouseX, mouseY, saveX, l.buttonY(), saveW, l.buttonH())) {
            saveEditor();
            return true;
        }

        if (editingId != null) {
            if (inside(mouseX, mouseY, l.x() + 10, l.buttonY(), 52, l.buttonH())) {
                MapMarkerStore.delete(editingId);
                editorOpen = false;
                return true;
            }
            Optional<MapMarkerStore.Marker> marker = MapMarkerStore.find(editingId);
            if (marker.isPresent()
                    && marker.get().origin == MapMarkerStore.Origin.AUTO
                    && marker.get().modifiedAuto
                    && inside(mouseX, mouseY, l.x() + 68, l.buttonY(), 72, l.buttonH())) {
                MapMarkerStore.resetAutomatic(editingId);
                editorOpen = false;
                return true;
            }
        }
        return true;
    }

    private boolean managerMouseClicked(double mouseX, double mouseY, int width, int height) {
        if (inside(mouseX, mouseY, width - 30, 8, 22, 22)) {
            managerOpen = false;
            return true;
        }

        int sideW = 118;
        if (inside(mouseX, mouseY, 8, 54, sideW - 16, 28)) {
            managerTab = ManagerTab.LIST;
            listScroll = 0;
            return true;
        }
        if (inside(mouseX, mouseY, 8, 88, sideW - 16, 28)) {
            managerTab = ManagerTab.TRASH;
            listScroll = 0;
            return true;
        }

        int contentX = sideW + 20;
        int contentY = 56;
        int contentW = width - contentX - 20;
        if (managerTab == ManagerTab.TRASH) {
            if (inside(mouseX, mouseY, contentX, contentY, 86, 24)) {
                trashKind = TrashKind.CUSTOM;
                listScroll = 0;
                return true;
            }
            if (inside(mouseX, mouseY, contentX + 94, contentY, 86, 24)) {
                trashKind = TrashKind.AUTO;
                listScroll = 0;
                return true;
            }
            contentY += 38;
        }

        List<MapMarkerStore.Marker> rows = managerRows();
        int rowH = 40;
        int visible = (int) ((mouseY - contentY) / rowH);
        int index = listScroll + visible;
        if (mouseY < contentY || index < 0 || index >= rows.size()) return true;

        MapMarkerStore.Marker marker = rows.get(index);
        int rowY = contentY + visible * rowH;
        if (mouseY >= rowY + rowH - 3) return true;

        int deleteX = contentX + contentW - 38;
        if (managerTab == ManagerTab.TRASH) {
            if (inside(mouseX, mouseY, deleteX - 2, rowY + 18, 38, 20)) {
                MapMarkerStore.restore(marker.uuid());
            }
            return true;
        }

        int resetX = deleteX - 42;
        int editX = (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto)
                ? resetX - 42 : deleteX - 42;

        if (inside(mouseX, mouseY, deleteX - 2, rowY + 18, 38, 20)) {
            MapMarkerStore.delete(marker.uuid());
            return true;
        }
        if (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto
                && inside(mouseX, mouseY, resetX - 2, rowY + 18, 38, 20)) {
            MapMarkerStore.resetAutomatic(marker.uuid());
            return true;
        }
        if (inside(mouseX, mouseY, editX - 2, rowY + 18, 38, 20)) {
            openEdit(marker);
            return true;
        }

        openEdit(marker);
        return true;
    }

    private void saveEditor() {
        String name = nameInput.value();
        MapMarkerStore.Icon icon = iconSelect.value();
        int primary = primaryColorInput.value();
        int accent = accentColorInput.value();
        int line = lineColorInput.value();
        String description = descriptionInput.value();
        if (editingId == null) {
            MapMarkerStore.createCustom(
                    editorDimension, editorX, editorZ,
                    name, icon, primary, accent, line, description);
        } else {
            MapMarkerStore.update(
                    editingId, name, icon, primary, accent, line, description);
        }
        editorOpen = false;
        iconSelect.closePopup();
        primaryColorInput.closePopup();
        accentColorInput.closePopup();
        lineColorInput.closePopup();
    }

    private static EditorLayout editorLayout(int width, int height) {
        int margin = 6;
        int panelW = Math.max(1, Math.min(560, width - margin * 2));
        int panelH = Math.max(1, Math.min(410, height - margin * 2));
        int x = Math.max(0, (width - panelW) / 2);
        int y = Math.max(0, (height - panelH) / 2);

        boolean compact = panelH < 340;
        int headerH = compact ? 26 : 34;
        int horizontalPad = panelW < 360 ? 12 : 20;
        int labelW = panelW < 360 ? 48 : 82;
        int labelX = x + horizontalPad;
        int controlX = labelX + labelW;
        int controlW = Math.max(64, x + panelW - horizontalPad - controlX);

        int gap = compact ? 24 : 30;
        int nameY = y + headerH + 8;
        int iconY = nameY + gap;
        int primaryColorY = iconY + gap;
        int accentColorY = primaryColorY + gap;
        int lineColorY = accentColorY + gap;
        int descriptionY = lineColorY + gap;

        int buttonH = compact ? 20 : 24;
        int buttonY = y + panelH - buttonH - 8;
        int locationY = buttonY - (compact ? 15 : 18);
        int descriptionH = Math.max(24, locationY - 7 - descriptionY);

        return new EditorLayout(
                x, y, panelW, panelH,
                labelX, controlX, controlW,
                nameY, iconY,
                primaryColorY, accentColorY, lineColorY,
                descriptionY, descriptionH,
                locationY, buttonY, buttonH);
    }

    private List<MapMarkerStore.Marker> managerRows() {
        if (managerTab == ManagerTab.LIST) return MapMarkerStore.customManagerEntries();
        return trashKind == TrashKind.CUSTOM
                ? MapMarkerStore.recycleCustom()
                : MapMarkerStore.recycleAuto();
    }

    private static void drawMarkerIcon(
            GuiGraphics graphics, MapMarkerStore.Icon icon,
            int x, int y,
            int primary, int accent, int line,
            boolean hovered, int size) {
        MapIconAtlas.drawMarker(
                graphics, icon, x, y, size,
                primary, accent, line, hovered);
    }

    private static boolean overlaps(List<int[]> occupied, int[] rect) {
        for (int[] other : occupied) {
            if (rect[0] < other[2] && rect[2] > other[0]
                    && rect[1] < other[3] && rect[3] > other[1]) return true;
        }
        return false;
    }

    private static void drawButton(
            GuiGraphics graphics, Font font,
            int x, int y, int w, int h, String text,
            int mouseX, int mouseY, boolean primary) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        int bg = primary ? (hover ? 0xff4e8b69 : 0xff3d7256)
                : (hover ? 0xff3b444c : 0xff30373e);
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.drawCenteredString(font, text, x + w / 2, y + 8, 0xffffffff);
    }

    private static void drawSideTab(
            GuiGraphics graphics, Font font,
            int x, int y, int w, int h, String text,
            boolean active, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h,
                active ? 0xff303840 : hover ? 0xff293139 : 0xff20262c);
        if (active) graphics.fill(x, y, x + 3, y + h, 0xffffd166);
        graphics.drawString(font, text, x + 12, y + 10,
                active ? 0xffffd166 : 0xffd7dde2, false);
    }

    private static void drawActionText(
            GuiGraphics graphics, Font font, int x, int y, String text,
            int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x - 2, y - 3, 34, 18);
        graphics.drawString(font, text, x, y, hover ? 0xffffd166 : 0xffb9c2c9, false);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }
}
