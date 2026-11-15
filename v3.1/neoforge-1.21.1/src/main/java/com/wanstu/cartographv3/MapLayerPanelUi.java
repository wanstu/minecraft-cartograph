package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.List;

final class MapLayerPanelUi {
    private static final int PANEL_W = 300;
    private static final int BODY_PAD = 12;
    private static final int ROW_H = 39;
    private static final int FOOTER_H = 88;

    private boolean open;
    private boolean dragging;
    private int x = -1;
    private int y = MapHifiUi.HEADER_H + 18;
    private int w = PANEL_W;
    private int h = 520;
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

        int bx = x + BODY_PAD;
        int by = y + MapHifiUi.PANEL_HEADER_H + BODY_PAD;
        int inner = w - BODY_PAD * 2;
        MapHifiUi.text(graphics, font, "显示内容", bx, by, MapHifiUi.MUTED, 12);
        by += 24;

        drawMasterRow(graphics, font, bx, by, inner, "自定义标点",
                MapUiSettings.markerLayerEnabled(), mouseX, mouseY);
        by += ROW_H;
        drawMasterRow(graphics, font, bx, by, inner, "自然结构",
                MapUiSettings.structureLayerEnabled(), mouseX, mouseY);
        by += ROW_H;

        MapHifiUi.divider(graphics, bx, by, bx + inner);
        by += 6;

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
        int listBottom = y + h - FOOTER_H;
        MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
        int maxRows = Math.max(1, (listBottom - listTop) / ROW_H);
        int maxScroll = Math.max(0, presets.length - maxRows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        MapHifiUi.enablePixelScissor(graphics, x + 1, listTop, x + w - 1, listBottom);
        for (int i = 0; i < maxRows; i++) {
            int index = scroll + i;
            if (index >= presets.length) break;
            MapMarkerStore.StructurePreset preset = presets[index];
            int ry = listTop + i * ROW_H;
            boolean hover = MapHifiUi.inside(mouseX, mouseY, bx, ry, inner, ROW_H);
            if (hover) graphics.fill(bx, ry, bx + inner, ry + ROW_H, 0xff223039);

            MapHifiUi.checkbox(
                    graphics, bx + 1, ry + 10,
                    MapUiSettings.structureVisible(preset), mouseX, mouseY);
            MapIconAtlas.drawMarkerMono(
                    graphics, MapUiSettings.structurePresetIcon(preset),
                    bx + 42, ry + ROW_H / 2, 21,
                    MapUiSettings.structurePresetColor(preset));
            MapHifiUi.text(
                    graphics, font, preset.label,
                    bx + 58, ry + 12, MapHifiUi.TEXT, 14);
            String count = Integer.toString(counts.getOrDefault(preset, 0));
            MapHifiUi.text(
                    graphics, font, count,
                    bx + inner - MapHifiUi.textWidth(font, count, 12) - 5,
                    ry + 13, MapHifiUi.MUTED, 12);
            MapHifiUi.divider(graphics, bx, ry + ROW_H - 1, bx + inner);
        }
        MapHifiUi.disablePixelScissor(graphics);

        if (maxScroll > 0) {
            int trackX = x + w - 8;
            int trackH = listBottom - listTop;
            int thumbH = Math.max(28, trackH * maxRows / presets.length);
            int travel = Math.max(1, trackH - thumbH);
            int thumbY = listTop + travel * scroll / maxScroll;
            graphics.fill(trackX, listTop, trackX + 4, listBottom, 0xff27343c);
            graphics.fill(trackX, thumbY, trackX + 4, thumbY + thumbH, 0xff66757e);
        }

        int fy = y + h - FOOTER_H + 6;
        MapHifiUi.divider(graphics, bx, fy - 5, bx + inner);
        drawFooterToggle(graphics, font, bx, fy, inner, "名称标签",
                MapUiSettings.labels(), mouseX, mouseY);
        drawFooterToggle(graphics, font, bx, fy + 39, inner, "区块网格",
                MapUiSettings.grid(), mouseX, mouseY);
    }

    private void drawMasterRow(
            GuiGraphics graphics, Font font, int x, int y, int w, String label,
            boolean on, int mouseX, int mouseY) {
        boolean hover = MapHifiUi.inside(mouseX, mouseY, x, y, w, ROW_H);
        if (hover) graphics.fill(x, y, x + w, y + ROW_H, 0xff223039);
        MapHifiUi.checkbox(graphics, x + 1, y + 10, on, mouseX, mouseY);
        MapHifiUi.text(graphics, font, label, x + 31, y + 12, MapHifiUi.TEXT, 14);
        MapHifiUi.divider(graphics, x, y + ROW_H - 1, x + w);
    }

    private void drawFooterToggle(
            GuiGraphics graphics, Font font, int x, int y, int w, String label,
            boolean on, int mouseX, int mouseY) {
        MapHifiUi.text(graphics, font, label, x + 1, y + 10, MapHifiUi.TEXT, 14);
        MapHifiUi.switchBox(graphics, x + w - 43, y + 7, on, mouseX, mouseY);
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
        if (MapHifiUi.inside(mouseX, mouseY, x, y, w, MapHifiUi.PANEL_HEADER_H)) {
            dragging = true;
            dragOffsetX = (int) mouseX - x;
            dragOffsetY = (int) mouseY - y;
            return true;
        }

        int bx = x + BODY_PAD;
        int by = y + MapHifiUi.PANEL_HEADER_H + BODY_PAD + 24;
        int inner = w - BODY_PAD * 2;
        if (MapHifiUi.inside(mouseX, mouseY, bx, by, inner, ROW_H)) {
            MapUiSettings.setMarkerLayerEnabled(!MapUiSettings.markerLayerEnabled());
            return true;
        }
        by += ROW_H;
        if (MapHifiUi.inside(mouseX, mouseY, bx, by, inner, ROW_H)) {
            MapUiSettings.setStructureLayerEnabled(!MapUiSettings.structureLayerEnabled());
            return true;
        }
        by += ROW_H + 6;

        int listTop = by;
        int listBottom = y + h - FOOTER_H;
        MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
        int maxRows = Math.max(1, (listBottom - listTop) / ROW_H);
        int visibleRow = (int) ((mouseY - listTop) / ROW_H);
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

        int fy = y + h - FOOTER_H + 6;
        if (MapHifiUi.inside(mouseX, mouseY, bx, fy, inner, 36)) {
            MapUiSettings.setLabels(!MapUiSettings.labels());
            return true;
        }
        if (MapHifiUi.inside(mouseX, mouseY, bx, fy + 39, inner, 36)) {
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
            scroll = Math.max(0, scroll + (scrollY > 0 ? -1 : 1));
        }
        return true;
    }

    private void clamp(int width, int height) {
        w = PANEL_W;
        int available = Math.max(260, height - MapHifiUi.HEADER_H - 36);
        h = Math.min(650, available);
        x = Math.max(8, Math.min(Math.max(8, width - w - 8), x));
        y = Math.max(MapHifiUi.HEADER_H + 8,
                Math.min(Math.max(MapHifiUi.HEADER_H + 8, height - h - 8), y));
    }
}
