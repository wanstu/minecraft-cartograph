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
            int nameY, int iconY, int colorY,
            int descriptionY, int descriptionH,
            int locationY, int buttonY, int buttonH) {}

    private final MapUiControls.Input nameInput;
    private final MapUiControls.MarkerIconSelect iconSelect;
    private final MapUiControls.ColorInput colorInput;
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
        colorInput = new MapUiControls.ColorInput(160, 0xe53935, value -> {});
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
        colorInput.setValue(0xe53935);
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
        colorInput.setValue(marker.color);
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
        colorInput.closePopup();
    }

    Optional<MapMarkerStore.Marker> markerAt(
            String dimension, double centerX, double centerZ, double zoom,
            int width, int height, double mouseX, double mouseY) {
        MapMarkerStore.Marker best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MapMarkerStore.Marker marker : MapMarkerStore.visible(dimension)) {
            double sx = width * 0.5 + (marker.x - centerX) * zoom;
            double sy = height * 0.5 + (marker.z - centerZ) * zoom;
            double dx = sx - mouseX;
            double dy = sy - mouseY;
            double dist = dx * dx + dy * dy;
            if (dist <= 100.0 && dist < bestDistance) {
                best = marker;
                bestDistance = dist;
            }
        }
        return Optional.ofNullable(best);
    }

    void renderLayer(
            GuiGraphics graphics, Font font,
            String dimension, double centerX, double centerZ, double zoom,
            int width, int height, int mouseX, int mouseY) {
        List<MapMarkerStore.Marker> markers = MapMarkerStore.visible(dimension);
        List<int[]> labels = new ArrayList<>();

        markers.sort(Comparator.comparingInt(marker -> marker.origin == MapMarkerStore.Origin.CUSTOM ? 1 : 0));
        for (MapMarkerStore.Marker marker : markers) {
            int sx = (int) Math.round(width * 0.5 + (marker.x - centerX) * zoom);
            int sy = (int) Math.round(height * 0.5 + (marker.z - centerZ) * zoom);
            if (sx < -24 || sy < -24 || sx > width + 24 || sy > height + 24) continue;

            boolean hovered = Math.abs(mouseX - sx) <= 8 && Math.abs(mouseY - sy) <= 8;
            drawMarkerIcon(graphics, marker.icon, sx, sy, marker.color, hovered);

            if (zoom < 0.25 && !hovered) continue;
            int textW = font.width(marker.name);
            int lx = sx + 9;
            int ly = sy - 5;
            int[] rect = new int[] {lx - 2, ly - 2, lx + textW + 3, ly + font.lineHeight + 2};
            if (!hovered && overlaps(labels, rect)) continue;
            labels.add(rect);

            graphics.fill(rect[0], rect[1], rect[2], rect[3], 0xc0181c20);
            graphics.drawString(font, marker.name, lx, ly, 0xfff0f3f5, true);
        }
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
        graphics.drawString(font, "颜色", l.labelX(), l.colorY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "简介", l.labelX(), l.descriptionY() + 7, 0xffdbe1e6, false);
        graphics.drawString(font, "位置  " + editorX + ", " + editorZ,
                l.labelX(), l.locationY(), 0xff8f989f, false);

        nameInput.setBounds(l.controlX(), l.nameY(), l.controlW(), 22);
        iconSelect.setBounds(l.controlX(), l.iconY(), Math.min(180, l.controlW()), 22);
        colorInput.setBounds(l.controlX(), l.colorY(), Math.min(180, l.controlW()), 22);
        descriptionInput.setBounds(
                l.controlX(), l.descriptionY(), l.controlW(), l.descriptionH());

        nameInput.render(graphics, font, mouseX, mouseY);
        descriptionInput.render(graphics, font, mouseX, mouseY);
        iconSelect.render(graphics, font, mouseX, mouseY);
        colorInput.render(graphics, font, mouseX, mouseY);

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
    }

    private void renderManager(
            GuiGraphics graphics, Font font, int width, int height, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, 0xff181d22);
        graphics.fill(0, 0, width, 38, 0xff2d343c);
        graphics.drawString(font, "自定义标点", 16, 14, 0xffffffff, false);
        graphics.drawString(font, "ESC 关闭", width - 64, 14, 0xff9aa3aa, false);

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
        int rowH = 28;
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
            graphics.fill(contentX, y, contentX + contentW, y + rowH - 2,
                    hover ? 0xff2d353d : 0xff22292f);

            drawMarkerIcon(graphics, marker.icon, contentX + 12, y + 13, marker.color, false);
            graphics.drawString(font, marker.name, contentX + 26, y + 9, 0xffedf1f4, false);
            String origin = marker.origin == MapMarkerStore.Origin.AUTO
                    ? (marker.modifiedAuto ? "自动→自定义" : "自动") : "自定义";
            graphics.drawString(font, origin, contentX + Math.max(180, contentW / 2),
                    y + 9, 0xff9ca5ac, false);
            graphics.drawString(font, marker.x + ", " + marker.z,
                    contentX + Math.max(280, contentW * 2 / 3), y + 9, 0xff9ca5ac, false);

            if (managerTab == ManagerTab.LIST) {
                drawActionText(graphics, font, contentX + contentW - 118, y + 9,
                        "编辑", mouseX, mouseY);
                if (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto) {
                    drawActionText(graphics, font, contentX + contentW - 78, y + 9,
                            "重置", mouseX, mouseY);
                }
                drawActionText(graphics, font, contentX + contentW - 38, y + 9,
                        "删除", mouseX, mouseY);
            } else {
                drawActionText(graphics, font, contentX + contentW - 38, y + 9,
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
        if (colorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        if (descriptionInput.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        return true;
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!editorOpen) return overlayOpen();
        if (colorInput.mouseReleased(mouseX, mouseY, button)) return true;
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
                colorInput.closePopup();
            } else {
                managerOpen = false;
            }
            return true;
        }
        if (editorOpen) {
            if (nameInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (descriptionInput.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (colorInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return true;
    }

    boolean charTyped(char codePoint, int modifiers) {
        if (!editorOpen) return overlayOpen();
        if (nameInput.charTyped(codePoint, modifiers)) return true;
        if (descriptionInput.charTyped(codePoint, modifiers)) return true;
        if (colorInput.charTyped(codePoint, modifiers)) return true;
        return true;
    }

    private boolean editorMouseClicked(double mouseX, double mouseY, int width, int height) {
        EditorLayout l = editorLayout(width, height);

        if (colorInput.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            iconSelect.closePopup();
            return true;
        }
        if (iconSelect.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            colorInput.closePopup();
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
        int rowH = 28;
        int visible = (int) ((mouseY - contentY) / rowH);
        int index = listScroll + visible;
        if (mouseY < contentY || index < 0 || index >= rows.size()) return true;

        MapMarkerStore.Marker marker = rows.get(index);
        int rowY = contentY + visible * rowH;
        if (mouseY >= rowY + rowH - 2) return true;

        if (managerTab == ManagerTab.TRASH) {
            if (mouseX >= contentX + contentW - 54) {
                MapMarkerStore.restore(marker.uuid());
            }
            return true;
        }

        if (mouseX >= contentX + contentW - 48) {
            MapMarkerStore.delete(marker.uuid());
            return true;
        }
        if (marker.origin == MapMarkerStore.Origin.AUTO && marker.modifiedAuto
                && mouseX >= contentX + contentW - 88) {
            MapMarkerStore.resetAutomatic(marker.uuid());
            return true;
        }
        if (mouseX >= contentX + contentW - 132) {
            openEdit(marker);
            return true;
        }

        openEdit(marker);
        return true;
    }

    private void saveEditor() {
        String name = nameInput.value();
        MapMarkerStore.Icon icon = iconSelect.value();
        int color = colorInput.value();
        String description = descriptionInput.value();
        if (editingId == null) {
            MapMarkerStore.createCustom(
                    editorDimension, editorX, editorZ,
                    name, icon, color, description);
        } else {
            MapMarkerStore.update(editingId, name, icon, color, description);
        }
        editorOpen = false;
        iconSelect.closePopup();
        colorInput.closePopup();
    }

    private static EditorLayout editorLayout(int width, int height) {
        int margin = 6;
        int panelW = Math.max(1, Math.min(520, width - margin * 2));
        int panelH = Math.max(1, Math.min(350, height - margin * 2));
        int x = Math.max(0, (width - panelW) / 2);
        int y = Math.max(0, (height - panelH) / 2);

        boolean compact = panelH < 280;
        int headerH = compact ? 26 : 34;
        int horizontalPad = panelW < 340 ? 12 : 20;
        int labelW = panelW < 340 ? 48 : 82;
        int labelX = x + horizontalPad;
        int controlX = labelX + labelW;
        int controlW = Math.max(64, x + panelW - horizontalPad - controlX);

        int gap = compact ? 24 : 34;
        int nameY = y + headerH + 8;
        int iconY = nameY + gap;
        int colorY = iconY + gap;
        int descriptionY = colorY + gap;

        int buttonH = compact ? 20 : 24;
        int buttonY = y + panelH - buttonH - 8;
        int locationY = buttonY - (compact ? 15 : 18);
        int descriptionH = Math.max(24, locationY - 7 - descriptionY);

        return new EditorLayout(
                x, y, panelW, panelH,
                labelX, controlX, controlW,
                nameY, iconY, colorY,
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
            int x, int y, int rgb, boolean hovered) {
        MapIconAtlas.drawMarker(graphics, icon, x, y, hovered ? 16 : 14, rgb, hovered);
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
