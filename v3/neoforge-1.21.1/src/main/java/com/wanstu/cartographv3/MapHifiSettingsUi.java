package com.wanstu.cartographv3;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;

final class MapHifiSettingsUi {
    private enum Page {
        GENERAL("通用"),
        DISPLAY("显示"),
        PERFORMANCE("性能与缓存"),
        MARKER("标点"),
        STRUCTURE("结构"),
        SHORTCUT("快捷键"),
        ADVANCED("高级");

        final String label;
        Page(String label) { this.label = label; }
    }

    private boolean open;
    private Page page = Page.GENERAL;
    private int structureScroll;

    private final MapUiControls.PlayerMarkerTypeSelect playerType =
            new MapUiControls.PlayerMarkerTypeSelect(
                    190, MapUiSettings.markerType(), MapUiSettings::setMarkerType);
    private final MapUiControls.ColorInput playerColor =
            new MapUiControls.ColorInput(
                    190, MapUiSettings.markerColor(), MapUiSettings::setPlayerColor);
    private final MapUiControls.Slider playerSize =
            new MapUiControls.Slider(
                    180, 8, 48, MapUiSettings.markerSize(), MapUiSettings::setMarkerSize);

    private final MapUiControls.Slider requestBatch =
            new MapUiControls.Slider(
                    220, 16, 256, MapUiSettings.requestBatchSize(),
                    MapUiSettings::setRequestBatchSize);
    private final MapUiControls.Slider scanInterval =
            new MapUiControls.Slider(
                    220, 5, 100, MapUiSettings.liveScanIntervalTicks(), value -> {
                        MapUiSettings.setLiveScanIntervalTicks(value);
                        PacketDistributor.sendToServer(
                                new MapProtocol.MapViewState(true, value));
                    });
    private final MapUiControls.Slider markerScale =
            new MapUiControls.Slider(
                    180, 0, 100, MapUiSettings.markerScalePercent(),
                    MapUiSettings::setMarkerScalePercent);

    private final MapUiControls.MarkerIconSelect markerDefaultIcon =
            new MapUiControls.MarkerIconSelect(
                    210, MapUiSettings.customMarkerDefaultIcon(),
                    MapUiSettings::setCustomMarkerDefaultIcon);
    private final MapUiControls.ColorInput markerDefaultColor =
            new MapUiControls.ColorInput(
                    180, MapUiSettings.customMarkerDefaultColor(),
                    MapUiSettings::setCustomMarkerDefaultColor);
    private final MapUiControls.Slider markerDefaultSize =
            new MapUiControls.Slider(
                    180, 8, 32, MapUiSettings.customMarkerDefaultSize(),
                    MapUiSettings::setCustomMarkerDefaultSize);

    private final EnumMap<MapMarkerStore.StructurePreset, MapUiControls.MarkerIconSelect>
            structureIcons = new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private final EnumMap<MapMarkerStore.StructurePreset, MapUiControls.ColorInput>
            structureColors = new EnumMap<>(MapMarkerStore.StructurePreset.class);

    MapHifiSettingsUi() {
        for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
            structureIcons.put(
                    preset,
                    new MapUiControls.MarkerIconSelect(
                            230,
                            MapUiSettings.structurePresetIcon(preset),
                            icon -> {
                                MapUiSettings.setStructurePresetIcon(preset, icon);
                                MapMarkerStore.applyStructurePreset(preset);
                            }));
            MapUiControls.ColorInput color = new MapUiControls.ColorInput(
                    34,
                    MapUiSettings.structurePresetColor(preset),
                    value -> {
                        MapUiSettings.setStructurePresetColor(preset, value);
                        MapMarkerStore.applyStructurePreset(preset);
                    });
            color.setCompact(true);
            structureColors.put(preset, color);
        }
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        page = Page.GENERAL;
        structureScroll = 0;
        closePopups();
    }

    void close() {
        open = false;
        closePopups();
    }

    void render(
            GuiGraphics graphics, Font font,
            int width, int height, int mouseX, int mouseY) {
        if (!open) return;
        Rect r = rect(width, height);

        MapHifiUi.modalShade(graphics, width, height);
        MapHifiUi.modal(graphics, r.x, r.y, r.w, r.h);
        MapHifiUi.panelHeader(
                graphics, font, r.x, r.y, r.w,
                MapIconAtlas.UiIcon.SETTINGS, "设置", mouseX, mouseY);

        int navW = Math.min(162, Math.max(126, r.w / 5));
        graphics.fill(r.x + 1, r.y + 31, r.x + navW, r.y + r.h - 1, 0xff111b21);
        graphics.fill(r.x + navW, r.y + 31, r.x + navW + 1, r.y + r.h - 1,
                0xff35434c);

        int navY = r.y + 46;
        int navH = 31;
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            int py = navY + i * navH;
            boolean active = page == p;
            boolean hover = MapHifiUi.inside(mouseX, mouseY, r.x + 10, py, navW - 20, 27);
            if (active || hover) {
                graphics.fill(
                        r.x + 10, py, r.x + navW - 10, py + 27,
                        active ? 0xff344b5c : 0xff1c2931);
            }
            if (active) graphics.fill(r.x + 10, py, r.x + 13, py + 27, 0xff62b2ed);
            graphics.drawString(
                    font, p.label, r.x + 24, py + 9,
                    active ? 0xffffffff : 0xffc4cdd2, false);
        }

        int cx = r.x + navW + 20;
        int cy = r.y + 48;
        int cw = r.w - navW - 38;
        int bottom = r.y + r.h - 45;

        switch (page) {
            case GENERAL -> renderGeneral(graphics, font, cx, cy, cw, bottom, mouseX, mouseY);
            case DISPLAY -> renderDisplay(graphics, font, cx, cy, cw, bottom, mouseX, mouseY);
            case PERFORMANCE -> renderPerformance(
                    graphics, font, cx, cy, cw, bottom, mouseX, mouseY);
            case MARKER -> renderMarker(graphics, font, cx, cy, cw, bottom, mouseX, mouseY);
            case STRUCTURE -> renderStructure(
                    graphics, font, cx, cy, cw, bottom, mouseX, mouseY);
            case SHORTCUT -> renderShortcut(graphics, font, cx, cy, cw, bottom);
            case ADVANCED -> renderAdvanced(graphics, font, cx, cy, cw, bottom);
        }

        MapHifiUi.divider(graphics, r.x + navW, bottom + 5, r.x + r.w);
        MapHifiUi.button(
                graphics, font,
                r.x + r.w - 77, r.y + r.h - 34, 63, 24,
                "应用", true, false, mouseX, mouseY);
    }

    private void renderGeneral(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "通用", "常用界面设置与地图风格");

        int cardY = y + 31;
        int cardH = Math.min(158, Math.max(132, (bottom - cardY) / 2));
        MapHifiUi.card(g, x, cardY, w, cardH);
        g.drawString(font, "玩家图标", x + 12, cardY + 12, MapHifiUi.TEXT, false);

        int labelX = x + 14;
        int controlX = x + Math.min(170, Math.max(116, w / 3));
        int controlW = Math.max(120, w - (controlX - x) - 18);

        int row1 = cardY + 34;
        int row2 = row1 + 32;
        int row3 = row2 + 32;
        g.drawString(font, "图标形状", labelX, row1 + 7, MapHifiUi.TEXT, false);
        g.drawString(font, "图标颜色", labelX, row2 + 7, MapHifiUi.TEXT, false);
        g.drawString(font, "图标大小", labelX, row3 + 7, MapHifiUi.TEXT, false);

        playerType.setValue(MapUiSettings.markerType());
        playerColor.setValue(MapUiSettings.markerColor());
        playerSize.setValue(MapUiSettings.markerSize());
        playerType.setBounds(controlX, row1, Math.min(210, controlW), 22);
        playerColor.setBounds(controlX, row2, Math.min(210, controlW), 22);
        playerSize.setBounds(controlX, row3, Math.min(210, controlW - 50), 18);

        MapUiControls.Control popup = null;
        if (playerType.popupOpen()) popup = playerType;
        else playerType.render(g, font, mouseX, mouseY);
        if (playerColor.popupOpen()) popup = playerColor;
        else playerColor.render(g, font, mouseX, mouseY);
        playerSize.render(g, font, mouseX, mouseY);
        String size = MapUiSettings.markerSize() + " px";
        g.drawString(font, size, controlX + Math.min(218, controlW - 42), row3 + 6,
                MapHifiUi.MUTED, false);

        int styleY = cardY + cardH + 12;
        int styleH = Math.max(90, bottom - styleY - 2);
        MapHifiUi.card(g, x, styleY, w, styleH);
        g.drawString(font, "地图风格", x + 12, styleY + 12, MapHifiUi.TEXT, false);
        g.drawString(font, "当前渲染", x + 14, styleY + 37, MapHifiUi.MUTED, false);
        g.drawString(font, "卫星 · 写实", x + 96, styleY + 37, 0xffe8eef5, false);
        g.drawString(font, "手绘 · 现代", x + 14, styleY + 61, 0xffaab4ba, false);
        g.drawString(font, "预留", x + 96, styleY + 61, 0xffe1b94b, false);
        if (styleY + 84 < bottom) {
            g.drawString(font, "手绘 · 古式", x + 14, styleY + 84, 0xffaab4ba, false);
            g.drawString(font, "预留", x + 96, styleY + 84, 0xffe1b94b, false);
        }

        if (popup != null) popup.render(g, font, mouseX, mouseY);
    }

    private void renderDisplay(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "显示", "地图覆盖层与可视信息");
        int cardY = y + 31;
        MapHifiUi.card(g, x, cardY, w, Math.min(250, bottom - cardY));
        int ry = cardY + 18;
        ry = toggleRow(g, font, x, ry, w, "显示坐标与生物群系",
                MapUiSettings.showCoords(), mouseX, mouseY);
        ry = toggleRow(g, font, x, ry, w, "显示玩家位置环",
                MapUiSettings.playerRing(), mouseX, mouseY);
        ry = toggleRow(g, font, x, ry, w, "自定义标点",
                MapUiSettings.markerLayerEnabled(), mouseX, mouseY);
        ry = toggleRow(g, font, x, ry, w, "自动结构",
                MapUiSettings.structureLayerEnabled(), mouseX, mouseY);
        ry = toggleRow(g, font, x, ry, w, "名称标签",
                MapUiSettings.labels(), mouseX, mouseY);
        toggleRow(g, font, x, ry, w, "区块网格",
                MapUiSettings.grid(), mouseX, mouseY);

        int scaleY = Math.min(bottom - 48, cardY + 212);
        if (scaleY > cardY + 150) {
            g.drawString(font, "图标随地图缩放", x + 14, scaleY + 6, MapHifiUi.TEXT, false);
            markerScale.setValue(MapUiSettings.markerScalePercent());
            markerScale.setBounds(x + Math.min(190, w / 2), scaleY, Math.max(90, w / 3), 18);
            markerScale.render(g, font, mouseX, mouseY);
            String v = MapUiSettings.markerScalePercent() + "%";
            g.drawString(font, v, x + w - font.width(v) - 14, scaleY + 5,
                    MapHifiUi.MUTED, false);
        }
    }

    private void renderPerformance(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "性能与缓存", "只调整现有请求与实时扫描参数");
        int cardY = y + 31;
        MapHifiUi.card(g, x, cardY, w, Math.min(214, bottom - cardY));

        int labelX = x + 14;
        int sliderX = x + Math.min(200, Math.max(125, w / 3));
        int sliderW = Math.max(90, w - (sliderX - x) - 95);

        int r1 = cardY + 22;
        g.drawString(font, "每批请求区块数", labelX, r1 + 5, MapHifiUi.TEXT, false);
        requestBatch.setValue(MapUiSettings.requestBatchSize());
        requestBatch.setBounds(sliderX, r1, sliderW, 18);
        requestBatch.render(g, font, mouseX, mouseY);
        String b = Integer.toString(MapUiSettings.requestBatchSize());
        g.drawString(font, b, x + w - font.width(b) - 14, r1 + 5, MapHifiUi.MUTED, false);
        g.drawString(font, "控制客户端预取宽度；底层请求与缓存实现保持不变。",
                labelX, r1 + 25, MapHifiUi.MUTED, false);

        int r2 = r1 + 70;
        g.drawString(font, "实时扫描间隔", labelX, r2 + 5, MapHifiUi.TEXT, false);
        scanInterval.setValue(MapUiSettings.liveScanIntervalTicks());
        scanInterval.setBounds(sliderX, r2, sliderW, 18);
        scanInterval.render(g, font, mouseX, mouseY);
        String scan = MapUiSettings.liveScanIntervalTicks() + " tick";
        g.drawString(font, scan, x + w - font.width(scan) - 14, r2 + 5,
                MapHifiUi.MUTED, false);
        g.drawString(font, "只调整已有扫描周期参数，不改变扫描算法与缓存协议。",
                labelX, r2 + 25, MapHifiUi.MUTED, false);

        int noteY = r2 + 65;
        if (noteY < bottom - 22) {
            g.drawString(font, "已渲染 RGB 缓存：开启（当前实现）",
                    labelX, noteY, 0xffb9c6cc, false);
        }
    }

    private void renderMarker(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "标点", "自定义标点默认行为");
        int cardY = y + 31;
        MapHifiUi.card(g, x, cardY, w, Math.min(190, bottom - cardY));

        int labelX = x + 14;
        int controlX = x + Math.min(180, Math.max(110, w / 3));
        int controlW = Math.max(120, w - (controlX - x) - 18);
        int r1 = cardY + 22;
        int r2 = r1 + 34;
        int r3 = r2 + 34;

        g.drawString(font, "默认图标", labelX, r1 + 7, MapHifiUi.TEXT, false);
        g.drawString(font, "默认颜色", labelX, r2 + 7, MapHifiUi.TEXT, false);
        g.drawString(font, "图标大小", labelX, r3 + 7, MapHifiUi.TEXT, false);

        markerDefaultIcon.setValue(MapUiSettings.customMarkerDefaultIcon());
        markerDefaultColor.setValue(MapUiSettings.customMarkerDefaultColor());
        markerDefaultSize.setValue(MapUiSettings.customMarkerDefaultSize());
        markerDefaultIcon.setBounds(controlX, r1, Math.min(230, controlW), 22);
        markerDefaultColor.setBounds(controlX, r2, Math.min(190, controlW), 22);
        markerDefaultSize.setBounds(controlX, r3, Math.min(190, controlW - 48), 18);

        MapUiControls.Control popup = null;
        if (markerDefaultIcon.popupOpen()) popup = markerDefaultIcon;
        else markerDefaultIcon.render(g, font, mouseX, mouseY);
        if (markerDefaultColor.popupOpen()) popup = markerDefaultColor;
        else markerDefaultColor.render(g, font, mouseX, mouseY);
        markerDefaultSize.render(g, font, mouseX, mouseY);
        String size = MapUiSettings.customMarkerDefaultSize() + " px";
        g.drawString(font, size, x + w - font.width(size) - 14, r3 + 6,
                MapHifiUi.MUTED, false);

        int labelY = r3 + 39;
        MapHifiUi.divider(g, x + 12, labelY, x + w - 12);
        labelY += 10;
        toggleRow(g, font, x, labelY, w, "25% 以下隐藏名称",
                MapUiSettings.hideLabelsLowZoom(), mouseX, mouseY);
        toggleRow(g, font, x, labelY + 30, w, "名称自动避让",
                MapUiSettings.avoidLabels(), mouseX, mouseY);

        if (popup != null) popup.render(g, font, mouseX, mouseY);
    }

    private void renderStructure(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "结构预设",
                "每个自动结构都有独立图标和单色；手工编辑后保持自定义");
        MapHifiUi.button(
                g, font, x + w - 84, y - 6, 84, 23,
                "恢复默认", false, false, mouseX, mouseY);
        int top = y + 32;
        int rowH = 31;
        int listH = Math.max(40, bottom - top - 4);
        int maxRows = Math.max(1, listH / rowH);
        MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
        int maxScroll = Math.max(0, presets.length - maxRows);
        structureScroll = Math.max(0, Math.min(structureScroll, maxScroll));

        int iconX = x + Math.min(230, Math.max(125, w * 42 / 100));
        int colorX = x + w - 46;

        MapUiControls.Control popup = null;
        for (int i = 0; i < maxRows; i++) {
            int index = structureScroll + i;
            if (index >= presets.length) break;
            MapMarkerStore.StructurePreset preset = presets[index];
            int ry = top + i * rowH;

            if (MapHifiUi.inside(mouseX, mouseY, x, ry, w, rowH - 2)) {
                g.fill(x, ry, x + w, ry + rowH - 2, 0xff18242b);
            }
            g.drawString(font, preset.label, x + 12, ry + 10, MapHifiUi.TEXT, false);

            MapUiControls.MarkerIconSelect select = structureIcons.get(preset);
            MapUiControls.ColorInput color = structureColors.get(preset);
            select.setValue(MapUiSettings.structurePresetIcon(preset));
            color.setValue(MapUiSettings.structurePresetColor(preset));
            select.setBounds(iconX, ry + 4, Math.max(120, colorX - iconX - 12), 22);
            color.setBounds(colorX, ry + 4, 34, 22);

            if (select.popupOpen()) popup = select;
            else select.render(g, font, mouseX, mouseY);
            if (color.popupOpen()) popup = color;
            else color.render(g, font, mouseX, mouseY);
        }

        if (maxScroll > 0) {
            int trackX = x + w - 3;
            int thumbH = Math.max(20, listH * maxRows / presets.length);
            int travel = Math.max(1, listH - thumbH);
            int thumbY = top + travel * structureScroll / maxScroll;
            g.fill(trackX, top, trackX + 2, top + listH, 0xff344149);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xff8a979e);
        }

        if (popup != null) popup.render(g, font, mouseX, mouseY);
    }

    private void renderShortcut(
            GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        title(g, font, x, y, w, "快捷键", "只展示当前实际有效的高频动作");
        int cardY = y + 31;
        MapHifiUi.card(g, x, cardY, w, Math.min(140, bottom - cardY));
        shortcutRow(
                g, font, x + 14, cardY + 20, w - 28,
                "打开 / 关闭地图", CartographV3Client.openKeyLabel());
        shortcutRow(g, font, x + 14, cardY + 54, w - 28, "回到玩家位置", "右下定位");
        shortcutRow(g, font, x + 14, cardY + 88, w - 28, "添加标点", "右键地图");
    }

    private void renderAdvanced(
            GuiGraphics g, Font font, int x, int y, int w, int bottom) {
        title(g, font, x, y, w, "高级", "调试与边界选项");
        int cardY = y + 31;
        MapHifiUi.card(g, x, cardY, w, Math.min(130, bottom - cardY));
        g.drawString(font, "地图底层缓存 / 扫描 / 渲染管线",
                x + 14, cardY + 22, MapHifiUi.TEXT, false);
        g.drawString(font, "本页不提供破坏性操作；当前 UI 接入不修改底层逻辑。",
                x + 14, cardY + 46, MapHifiUi.MUTED, false);
        g.drawString(font, "手绘 · 现代 / 古式仅预留设置入口，暂不参与地图绘制。",
                x + 14, cardY + 70, MapHifiUi.MUTED, false);
    }

    private static void shortcutRow(
            GuiGraphics g, Font font, int x, int y, int w, String label, String key) {
        g.drawString(font, label, x, y + 5, MapHifiUi.TEXT, false);
        int kw = Math.max(56, font.width(key) + 18);
        int kx = x + w - kw;
        g.fill(kx, y, kx + kw, y + 22, 0xff25313a);
        MapHifiUi.border(g, kx, y, kw, 22, 0xff48565f);
        g.drawCenteredString(font, key, kx + kw / 2, y + 7, 0xffe6ecef);
    }

    private static void title(
            GuiGraphics g, Font font, int x, int y, int w,
            String title, String desc) {
        g.drawString(font, title, x, y, 0xffffffff, false);
        int descX = x + font.width(title) + 12;
        if (descX + font.width(desc) < x + w) {
            g.drawString(font, desc, descX, y + 1, MapHifiUi.MUTED, false);
        }
        MapHifiUi.divider(g, x, y + 20, x + w);
    }

    private static int toggleRow(
            GuiGraphics g, Font font, int x, int y, int w, String label,
            boolean on, int mouseX, int mouseY) {
        if (MapHifiUi.inside(mouseX, mouseY, x + 8, y, w - 16, 28)) {
            g.fill(x + 8, y, x + w - 8, y + 28, 0xff1d2a31);
        }
        g.drawString(font, label, x + 14, y + 9, MapHifiUi.TEXT, false);
        MapHifiUi.switchBox(g, x + w - 45, y + 6, on, mouseX, mouseY);
        return y + 30;
    }

    boolean mouseClicked(
            double mouseX, double mouseY, int button, int width, int height) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;

        Rect r = rect(width, height);
        if (MapHifiUi.closeHit(mouseX, mouseY, r.x, r.y, r.w)) {
            close();
            return true;
        }
        if (MapHifiUi.inside(
                mouseX, mouseY, r.x + r.w - 77, r.y + r.h - 34, 63, 24)) {
            close();
            return true;
        }

        int navW = Math.min(162, Math.max(126, r.w / 5));
        int navY = r.y + 46;
        int navH = 31;
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            int py = navY + i * navH;
            if (MapHifiUi.inside(mouseX, mouseY, r.x + 10, py, navW - 20, 27)) {
                page = pages[i];
                structureScroll = 0;
                closePopups();
                return true;
            }
        }

        if (handlePopupClick(mouseX, mouseY)) return true;

        int cx = r.x + navW + 20;
        int cy = r.y + 48;
        int cw = r.w - navW - 38;

        switch (page) {
            case GENERAL -> {
                if (playerType.mouseClicked(mouseX, mouseY, button)) {
                    playerColor.closePopup();
                    return true;
                }
                if (playerColor.mouseClicked(mouseX, mouseY, button)) {
                    playerType.closePopup();
                    return true;
                }
                if (playerSize.mouseClicked(mouseX, mouseY, button)) return true;
            }
            case DISPLAY -> {
                int cardY = cy + 31;
                int ry = cardY + 18;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setShowCoords(!MapUiSettings.showCoords()); return true;
                }
                ry += 30;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setPlayerRing(!MapUiSettings.playerRing()); return true;
                }
                ry += 30;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setMarkerLayerEnabled(!MapUiSettings.markerLayerEnabled()); return true;
                }
                ry += 30;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setStructureLayerEnabled(!MapUiSettings.structureLayerEnabled()); return true;
                }
                ry += 30;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setLabels(!MapUiSettings.labels()); return true;
                }
                ry += 30;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setGrid(!MapUiSettings.grid()); return true;
                }
                if (markerScale.mouseClicked(mouseX, mouseY, button)) return true;
            }
            case PERFORMANCE -> {
                if (requestBatch.mouseClicked(mouseX, mouseY, button)) return true;
                if (scanInterval.mouseClicked(mouseX, mouseY, button)) return true;
            }
            case MARKER -> {
                if (markerDefaultIcon.mouseClicked(mouseX, mouseY, button)) {
                    markerDefaultColor.closePopup();
                    return true;
                }
                if (markerDefaultColor.mouseClicked(mouseX, mouseY, button)) {
                    markerDefaultIcon.closePopup();
                    return true;
                }
                if (markerDefaultSize.mouseClicked(mouseX, mouseY, button)) return true;
                int cardY = cy + 31;
                int r3 = cardY + 22 + 34 + 34;
                int labelY = r3 + 49;
                if (row(mouseX, mouseY, cx, labelY, cw)) {
                    MapUiSettings.setHideLabelsLowZoom(
                            !MapUiSettings.hideLabelsLowZoom()); return true;
                }
                if (row(mouseX, mouseY, cx, labelY + 30, cw)) {
                    MapUiSettings.setAvoidLabels(!MapUiSettings.avoidLabels()); return true;
                }
            }
            case STRUCTURE -> {
                if (MapHifiUi.inside(mouseX, mouseY, cx + cw - 84, cy - 6, 84, 23)) {
                    MapUiSettings.resetStructurePresets();
                    MapMarkerStore.applyAllStructurePresets();
                    for (MapMarkerStore.StructurePreset preset
                            : MapMarkerStore.StructurePreset.values()) {
                        structureIcons.get(preset).setValue(
                                MapUiSettings.structurePresetIcon(preset));
                        structureColors.get(preset).setValue(
                                MapUiSettings.structurePresetColor(preset));
                    }
                    closeStructurePopups();
                    return true;
                }
                int top = cy + 32;
                int rowH = 31;
                int listH = Math.max(40, r.y + r.h - 45 - top - 4);
                int maxRows = Math.max(1, listH / rowH);
                MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
                int visibleRow = (int) ((mouseY - top) / rowH);
                if (mouseY >= top && visibleRow >= 0 && visibleRow < maxRows) {
                    int index = structureScroll + visibleRow;
                    if (index < presets.length) {
                        MapMarkerStore.StructurePreset preset = presets[index];
                        MapUiControls.MarkerIconSelect icon = structureIcons.get(preset);
                        MapUiControls.ColorInput color = structureColors.get(preset);
                        if (icon.mouseClicked(mouseX, mouseY, button)) {
                            closeStructurePopupsExcept(icon); return true;
                        }
                        if (color.mouseClicked(mouseX, mouseY, button)) {
                            closeStructurePopupsExcept(color); return true;
                        }
                    }
                }
            }
            case SHORTCUT, ADVANCED -> {}
        }
        // Settings modal is a hard modal: never pass clicks through to the map.
        return true;
    }

    private boolean handlePopupClick(double mouseX, double mouseY) {
        MapUiControls.Control[] common = {
                playerType, playerColor, markerDefaultIcon, markerDefaultColor
        };
        for (MapUiControls.Control control : common) {
            if (control.popupOpen()) {
                if (control.mouseClicked(mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
                control.closePopup();
                return false;
            }
        }
        if (page == Page.STRUCTURE) {
            for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
                MapUiControls.Control[] controls = {
                        structureIcons.get(preset), structureColors.get(preset)
                };
                for (MapUiControls.Control control : controls) {
                    if (!control.popupOpen()) continue;
                    if (control.mouseClicked(
                            mouseX, mouseY, GLFW.GLFW_MOUSE_BUTTON_LEFT)) return true;
                    control.closePopup();
                    return false;
                }
            }
        }
        return false;
    }

    boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!open) return false;
        if (page == Page.GENERAL) {
            if (playerColor.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            if (playerSize.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        } else if (page == Page.DISPLAY) {
            if (markerScale.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        } else if (page == Page.PERFORMANCE) {
            if (requestBatch.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            if (scanInterval.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        } else if (page == Page.MARKER) {
            if (markerDefaultColor.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            if (markerDefaultSize.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        } else if (page == Page.STRUCTURE) {
            for (MapUiControls.ColorInput color : structureColors.values()) {
                if (color.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
            }
        }
        return true;
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!open) return false;
        playerColor.mouseReleased(mouseX, mouseY, button);
        playerSize.mouseReleased(mouseX, mouseY, button);
        markerScale.mouseReleased(mouseX, mouseY, button);
        requestBatch.mouseReleased(mouseX, mouseY, button);
        scanInterval.mouseReleased(mouseX, mouseY, button);
        markerDefaultColor.mouseReleased(mouseX, mouseY, button);
        markerDefaultSize.mouseReleased(mouseX, mouseY, button);
        for (MapUiControls.ColorInput color : structureColors.values()) {
            color.mouseReleased(mouseX, mouseY, button);
        }
        return true;
    }

    boolean mouseScrolled(double scrollY) {
        if (!open) return false;
        if (page == Page.STRUCTURE && scrollY != 0) {
            structureScroll = Math.max(0, structureScroll + (scrollY > 0 ? -2 : 2));
            closeStructurePopups();
        }
        return true;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (playerColor.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (markerDefaultColor.keyPressed(keyCode, scanCode, modifiers)) return true;
        for (MapUiControls.ColorInput color : structureColors.values()) {
            if (color.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return true;
    }

    boolean charTyped(char codePoint, int modifiers) {
        if (!open) return false;
        if (playerColor.charTyped(codePoint, modifiers)) return true;
        if (markerDefaultColor.charTyped(codePoint, modifiers)) return true;
        for (MapUiControls.ColorInput color : structureColors.values()) {
            if (color.charTyped(codePoint, modifiers)) return true;
        }
        return true;
    }

    private void closePopups() {
        playerType.closePopup();
        playerColor.closePopup();
        markerDefaultIcon.closePopup();
        markerDefaultColor.closePopup();
        closeStructurePopups();
    }

    private void closeStructurePopups() {
        for (MapUiControls.MarkerIconSelect icon : structureIcons.values()) icon.closePopup();
        for (MapUiControls.ColorInput color : structureColors.values()) color.closePopup();
    }

    private void closeStructurePopupsExcept(MapUiControls.Control keep) {
        for (MapUiControls.MarkerIconSelect icon : structureIcons.values()) {
            if (icon != keep) icon.closePopup();
        }
        for (MapUiControls.ColorInput color : structureColors.values()) {
            if (color != keep) color.closePopup();
        }
    }

    private static boolean row(double mouseX, double mouseY, int x, int y, int w) {
        return MapHifiUi.inside(mouseX, mouseY, x + 8, y, w - 16, 28);
    }

    private static Rect rect(int width, int height) {
        int w = Math.max(300, Math.min(940, width - 16));
        int maxH = Math.max(220, height - MapHifiUi.HEADER_H - 10);
        int h = Math.min(650, maxH);
        if (h > height - MapHifiUi.HEADER_H - 4) {
            h = Math.max(160, height - MapHifiUi.HEADER_H - 4);
        }
        int x = Math.max(4, (width - w) / 2);
        int y = MapHifiUi.HEADER_H
                + Math.max(2, (height - MapHifiUi.HEADER_H - h) / 2);
        return new Rect(x, y, w, h);
    }

    private record Rect(int x, int y, int w, int h) {}
}
