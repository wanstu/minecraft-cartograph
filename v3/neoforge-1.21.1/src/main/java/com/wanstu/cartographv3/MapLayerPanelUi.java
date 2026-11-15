package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.List;

final class MapLayerPanelUi {
    private boolean open;
    private boolean dragging;
    private int x = -1;
    private int y = MapHifiUi.HEADER_H + 6;
    private int w = 276;
    private int h = 420;
    private int dragOffsetX;
    private int dragOffsetY;
    private int scroll;

    boolean open() {
        return open;
    }

    void openAt(int anchorX, int anchorY, int width, int height) {
        open = true;
        if (x < 0) {
            x = anchorX - w / 2;
            y = anchorY;
        }
        clamp(width, height);
    }

    void close() {
        open = false;
        dragging = false;
    }

    boolean contains(double mouseX, double mouseY) {
        return open && MapHifiUi.inside(mouseX, mouseY, x, y, w, h);
    }

    void render(
            GuiGraphics graphics, Font font, String dimension,
            int width, int height, int mouseX, int mouseY) {
        if (!open) return;
        clamp(width, height);

        MapHifiUi.panel(graphics, x, y, w, h);
        MapHifiUi.panelHeader(
                graphics, font, x, y, w,
                MapIconAtlas.UiIcon.LAYERS, "图层", mouseX, mouseY);

        int bx = x + 11;
        int by = y + 40;
        int inner = w - 22;
        graphics.drawString(font, "显示内容", bx, by, MapHifiUi.MUTED, false);
        by += 18;

        drawMasterRow(graphics, font, bx, by, inner, "自定义标点",
                MapUiSettings.markerLayerEnabled(), mouseX, mouseY);
        by += 25;
        drawMasterRow(graphics, font, bx, by, inner, "自然结构",
                MapUiSettings.structureLayerEnabled(), mouseX, mouseY);
        by += 29;

        MapHifiUi.divider(graphics, bx, by - 4, bx + inner);

        List<MapMarkerStore.Marker> visible = MapMarkerStore.visible(dimension);
        EnumMap<MapMarkerStore.StructurePreset, Integer> counts =
                new EnumMap<>(MapMarkerStore.StructurePreset.class);
        for (MapMarkerStore.Marker marker : visible) {
            if (marker.origin != MapMarkerStore.Origin.AUTO || marker.modifiedAuto) continue;
            MapMarkerStore.StructurePreset preset =
                    MapMarkerStore.structurePreset(marker.structureId);
            if (preset != null) counts.merge(preset, 1, Integer::sum);
        }

        int listTop = by;
        int footerH = 56;
        int listBottom = y + h - footerH;
        int rowH = 24;
        MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
        int maxRows = Math.max(1, (listBottom - listTop) / rowH);
        int maxScroll = Math.max(0, presets.length - maxRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        for (int i = 0; i < maxRows; i++) {
            int index = scroll + i;
            if (index >= presets.length) break;
            MapMarkerStore.StructurePreset preset = presets[index];
            int ry = listTop + i * rowH;
            boolean hover = MapHifiUi.inside(mouseX, mouseY, bx, ry, inner, rowH);
            if (hover) graphics.fill(bx, ry, bx + inner, ry + rowH, 0xff223039);

            MapHifiUi.checkbox(
                    graphics, bx + 1, ry + 5,
                    MapUiSettings.structureVisible(preset), mouseX, mouseY);
            MapIconAtlas.drawMarker(
                    graphics, MapUiSettings.structurePresetIcon(preset),
                    bx + 30, ry + 12, 16,
                    MapUiSettings.structurePresetColor(preset),
                    MapUiSettings.structurePresetColor(preset),
                    MapUiSettings.structurePresetColor(preset),
                    false);
            graphics.drawString(font, preset.label, bx + 44, ry + 8, MapHifiUi.TEXT, false);
            String count = Integer.toString(counts.getOrDefault(preset, 0));
            graphics.drawString(font, count, bx + inner - font.width(count) - 3, ry + 8,
                    MapHifiUi.MUTED, false);
        }

        if (maxScroll > 0) {
            int trackX = x + w - 7;
            int trackH = listBottom - listTop;
            int thumbH = Math.max(24, trackH * maxRows / presets.length);
            int travel = Math.max(1, trackH - thumbH);
            int thumbY = listTop + travel * scroll / maxScroll;
            graphics.fill(trackX, listTop, trackX + 2, listBottom, 0xff334149);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xff8a989f);
        }

        int fy = y + h - footerH + 6;
        MapHifiUi.divider(graphics, bx, fy - 4, bx + inner);
        drawFooterToggle(graphics, font, bx, fy, inner, "名称标签",
                MapUiSettings.labels(), mouseX, mouseY);
        drawFooterToggle(graphics, font, bx, fy + 23, inner, "区块网格",
                MapUiSettings.grid(), mouseX, mouseY);
    }

    private void drawMasterRow(
            GuiGraphics graphics, Font font, int x, int y, int w, String label,
            boolean on, int mouseX, int mouseY) {
        boolean hover = MapHifiUi.inside(mouseX, mouseY, x, y, w, 23);
        if (hover) graphics.fill(x, y, x + w, y + 23, 0xff223039);
        MapHifiUi.checkbox(graphics, x + 1, y + 4, on, mouseX, mouseY);
        graphics.drawString(font, label, x + 22, y + 7, MapHifiUi.TEXT, false);
    }

    private void drawFooterToggle(
            GuiGraphics graphics, Font font, int x, int y, int w, String label,
            boolean on, int mouseX, int mouseY) {
        graphics.drawString(font, label, x + 1, y + 5, MapHifiUi.TEXT, false);
        MapHifiUi.switchBox(graphics, x + w - 29, y + 1, on, mouseX, mouseY);
    }

    boolean mouseClicked(
            double mouseX, double mouseY, int button, int width, int height) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return contains(mouseX, mouseY);
        clamp(width, height);

        if (MapHifiUi.closeHit(mouseX, mouseY, x, y, w)) {
            close();
            return true;
        }
        if (MapHifiUi.inside(mouseX, mouseY, x, y, w, 31)) {
            dragging = true;
            dragOffsetX = (int) mouseX - x;
            dragOffsetY = (int) mouseY - y;
            return true;
        }

        int bx = x + 11;
        int by = y + 58;
        int inner = w - 22;
        if (MapHifiUi.inside(mouseX, mouseY, bx, by, inner, 23)) {
            MapUiSettings.setMarkerLayerEnabled(!MapUiSettings.markerLayerEnabled());
            return true;
        }
        by += 25;
        if (MapHifiUi.inside(mouseX, mouseY, bx, by, inner, 23)) {
            MapUiSettings.setStructureLayerEnabled(!MapUiSettings.structureLayerEnabled());
            return true;
        }
        by += 29;

        int listTop = by;
        int footerH = 56;
        int listBottom = y + h - footerH;
        int rowH = 24;
        MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
        int maxRows = Math.max(1, (listBottom - listTop) / rowH);
        int visibleRow = (int) ((mouseY - listTop) / rowH);
        if (mouseY >= listTop && mouseY < listBottom
                && visibleRow >= 0 && visibleRow < maxRows) {
            int index = scroll + visibleRow;
            if (index < presets.length) {
                MapMarkerStore.StructurePreset preset = presets[index];
                MapUiSettings.setStructureVisible(
                        preset, !MapUiSettings.structureVisible(preset));
                return true;
            }
        }

        int fy = y + h - footerH + 6;
        if (MapHifiUi.inside(mouseX, mouseY, bx, fy, inner, 21)) {
            MapUiSettings.setLabels(!MapUiSettings.labels());
            return true;
        }
        if (MapHifiUi.inside(mouseX, mouseY, bx, fy + 23, inner, 21)) {
            MapUiSettings.setGrid(!MapUiSettings.grid());
            return true;
        }
        return true;
    }

    boolean mouseDragged(
            double mouseX, double mouseY, int button,
            int width, int height) {
        if (!open) return false;
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            x = (int) mouseX - dragOffsetX;
            y = (int) mouseY - dragOffsetY;
            clamp(width, height);
            return true;
        }
        return false;
    }

    boolean mouseReleased(int button) {
        if (!open) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!open || !contains(mouseX, mouseY)) return false;
        if (scrollY != 0) {
            scroll = Math.max(0, scroll + (scrollY > 0 ? -2 : 2));
        }
        return true;
    }

    private void clamp(int width, int height) {
        h = Math.min(470, Math.max(300, height - MapHifiUi.HEADER_H - 16));
        x = Math.max(6, Math.min(Math.max(6, width - w - 6), x));
        y = Math.max(MapHifiUi.HEADER_H + 4,
                Math.min(Math.max(MapHifiUi.HEADER_H + 4, height - h - 6), y));
    }
}
