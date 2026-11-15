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
    private int pageScroll;
    private int pageScrollMax;

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
        pageScroll = 0;
        pageScrollMax = 0;
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
        MapHifiUi.modalHeader(
                graphics, font, r.x, r.y, r.w,
                MapIconAtlas.UiIcon.SETTINGS, "设置", mouseX, mouseY);

        int navW = width <= 900 ? 150 : 190;
        navW = Math.min(navW, Math.max(130, r.w / 3));
        graphics.fill(
                r.x + 1, r.y + MapHifiUi.MODAL_HEADER_H,
                r.x + navW, r.y + r.h - 1, 0xff111b21);
        graphics.fill(
                r.x + navW, r.y + MapHifiUi.MODAL_HEADER_H,
                r.x + navW + 1, r.y + r.h - 1, 0xff35434c);

        int navY = r.y + MapHifiUi.MODAL_HEADER_H + 13;
        int navH = 46;
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            int py = navY + i * navH;
            boolean active = page == p;
            boolean hover = MapHifiUi.inside(
                    mouseX, mouseY, r.x + 10, py, navW - 20, 43);
            if (active || hover) {
                graphics.fill(
                        r.x + 10, py, r.x + navW - 10, py + 43,
                        active ? 0xff344b5c : 0xff1c2931);
            }
            if (active) {
                graphics.fill(r.x + 10, py, r.x + 13, py + 43, 0xff62b2ed);
            }
            MapHifiUi.text(
                    graphics, font, p.label,
                    r.x + 26, py + 13,
                    active ? 0xffffffff : 0xffc4cdd2, 14);
        }

        int cx = r.x + navW + 22;
        int cy = r.y + MapHifiUi.MODAL_HEADER_H + 18;
        int cw = r.w - navW - 44;
        int bottom = r.y + r.h - 54;
        int viewportH = Math.max(1, bottom - cy);
        int contentH = page == Page.STRUCTURE
                ? viewportH : Math.max(viewportH, pageContentHeight(page));
        pageScrollMax = Math.max(0, contentH - viewportH);
        pageScroll = Math.max(0, Math.min(pageScroll, pageScrollMax));
        int renderY = cy - pageScroll;
        int renderBottom = renderY + contentH;

        MapHifiUi.enablePixelScissor(
                graphics, r.x + navW + 1,
                r.y + MapHifiUi.MODAL_HEADER_H + 1,
                r.x + r.w - 1, bottom);
        switch (page) {
            case GENERAL -> renderGeneral(
                    graphics, font, cx, renderY, cw, renderBottom, mouseX, mouseY);
            case DISPLAY -> renderDisplay(
                    graphics, font, cx, renderY, cw, renderBottom, mouseX, mouseY);
            case PERFORMANCE -> renderPerformance(
                    graphics, font, cx, renderY, cw, renderBottom, mouseX, mouseY);
            case MARKER -> renderMarker(
                    graphics, font, cx, renderY, cw, renderBottom, mouseX, mouseY);
            case STRUCTURE -> renderStructure(
                    graphics, font, cx, renderY, cw, renderBottom, mouseX, mouseY);
            case SHORTCUT -> renderShortcut(graphics, font, cx, renderY, cw, renderBottom);
            case ADVANCED -> renderAdvanced(graphics, font, cx, renderY, cw, renderBottom);
        }
        MapHifiUi.disablePixelScissor(graphics);

        if (pageScrollMax > 0) {
            int trackTop = cy;
            int trackH = viewportH;
            int trackX = r.x + r.w - 7;
            int thumbH = Math.max(36, trackH * viewportH / contentH);
            int travel = Math.max(1, trackH - thumbH);
            int thumbY = trackTop + travel * pageScroll / pageScrollMax;
            graphics.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0xff29343b);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xff72818a);
        }

        MapHifiUi.divider(graphics, r.x + navW, bottom, r.x + r.w);
        MapHifiUi.button(
                graphics, font,
                r.x + r.w - 94, r.y + r.h - 45, 78, 36,
                "应用", true, false, mouseX, mouseY);
    }

    private void renderGeneral(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "通用", "常用界面设置与地图风格");

        int cardY = y + 40;
        int cardH = Math.min(190, Math.max(172, (bottom - cardY) / 2));
        MapHifiUi.card(g, x, cardY, w, cardH);
        MapHifiUi.text(g, font, "玩家图标", x + 14, cardY + 14, MapHifiUi.TEXT, 14);

        int labelX = x + 14;
        int controlX = x + Math.min(170, Math.max(116, w / 3));
        int controlW = Math.max(120, w - (controlX - x) - 18);

        int row1 = cardY + 43;
        int row2 = row1 + 44;
        int row3 = row2 + 44;
        MapHifiUi.text(g, font, "图标形状", labelX, row1 + 10, MapHifiUi.TEXT, 13);
        MapHifiUi.text(g, font, "图标颜色", labelX, row2 + 10, MapHifiUi.TEXT, 13);
        MapHifiUi.text(g, font, "图标大小", labelX, row3 + 10, MapHifiUi.TEXT, 13);

        playerType.setValue(MapUiSettings.markerType());
        playerColor.setValue(MapUiSettings.markerColor());
        playerSize.setValue(MapUiSettings.markerSize());
        playerType.setBounds(controlX, row1, Math.min(250, controlW), 34);
        playerColor.setBounds(controlX, row2, Math.min(250, controlW), 34);
        playerSize.setBounds(controlX, row3 + 5, Math.min(250, controlW - 62), 24);

        MapUiControls.Control popup = null;
        if (playerType.popupOpen()) popup = playerType;
        else playerType.render(g, font, mouseX, mouseY);
        if (playerColor.popupOpen()) popup = playerColor;
        else playerColor.render(g, font, mouseX, mouseY);
        playerSize.render(g, font, mouseX, mouseY);
        String size = MapUiSettings.markerSize() + " px";
        MapHifiUi.text(g, font, size,
                controlX + Math.min(270, controlW - 54), row3 + 10,
                MapHifiUi.MUTED, 12);

        int styleY = cardY + cardH + 12;
        int styleH = Math.max(90, bottom - styleY - 2);
        MapHifiUi.card(g, x, styleY, w, styleH);
        MapHifiUi.text(g, font, "地图风格", x + 14, styleY + 14, MapHifiUi.TEXT, 14);
        MapHifiUi.text(g, font, "当前渲染", x + 14, styleY + 46, MapHifiUi.MUTED, 13);
        MapHifiUi.text(g, font, "卫星 · 写实", x + 124, styleY + 46, 0xffe8eef5, 13);
        MapHifiUi.text(g, font, "手绘 · 现代", x + 14, styleY + 76, 0xffaab4ba, 13);
        MapHifiUi.text(g, font, "预留", x + 124, styleY + 76, 0xffe1b94b, 13);
        if (styleY + 84 < bottom) {
            MapHifiUi.text(g, font, "手绘 · 古式", x + 14, styleY + 106, 0xffaab4ba, 13);
            MapHifiUi.text(g, font, "预留", x + 124, styleY + 106, 0xffe1b94b, 13);
        }

        if (popup != null) popup.render(g, font, mouseX, mouseY);
    }

    private void renderDisplay(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "显示", "地图覆盖层与可视信息");
        int cardY = y + 40;
        MapHifiUi.card(g, x, cardY, w, Math.min(360, bottom - cardY));
        int ry = cardY + 12;
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

        int scaleY = Math.min(bottom - 50, cardY + 286);
        if (scaleY > cardY + 230) {
            MapHifiUi.text(g, font, "图标随地图缩放", x + 14, scaleY + 10, MapHifiUi.TEXT, 13);
            markerScale.setValue(MapUiSettings.markerScalePercent());
            markerScale.setBounds(x + Math.min(220, w / 2), scaleY + 5, Math.max(110, w / 3), 24);
            markerScale.render(g, font, mouseX, mouseY);
            String v = MapUiSettings.markerScalePercent() + "%";
            MapHifiUi.text(g, font, v,
                    x + w - MapHifiUi.textWidth(font, v, 12) - 14, scaleY + 10,
                    MapHifiUi.MUTED, 12);
        }
    }

    private void renderPerformance(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "性能与缓存", "只调整现有请求与实时扫描参数");
        int cardY = y + 40;
        MapHifiUi.card(g, x, cardY, w, Math.min(255, bottom - cardY));

        int labelX = x + 14;
        int sliderX = x + Math.min(200, Math.max(125, w / 3));
        int sliderW = Math.max(90, w - (sliderX - x) - 95);

        int r1 = cardY + 28;
        MapHifiUi.text(g, font, "每批请求区块数", labelX, r1 + 8, MapHifiUi.TEXT, 14);
        requestBatch.setValue(MapUiSettings.requestBatchSize());
        requestBatch.setBounds(sliderX, r1 + 3, sliderW, 24);
        requestBatch.render(g, font, mouseX, mouseY);
        String b = Integer.toString(MapUiSettings.requestBatchSize());
        MapHifiUi.text(g, font, b,
                x + w - MapHifiUi.textWidth(font, b, 12) - 14, r1 + 9,
                MapHifiUi.MUTED, 12);
        MapHifiUi.text(g, font, "控制客户端预取宽度；底层请求与缓存实现保持不变。",
                labelX, r1 + 35, MapHifiUi.MUTED, 12);

        int r2 = r1 + 88;
        MapHifiUi.text(g, font, "实时扫描间隔", labelX, r2 + 8, MapHifiUi.TEXT, 14);
        scanInterval.setValue(MapUiSettings.liveScanIntervalTicks());
        scanInterval.setBounds(sliderX, r2 + 3, sliderW, 24);
        scanInterval.render(g, font, mouseX, mouseY);
        String scan = MapUiSettings.liveScanIntervalTicks() + " tick";
        MapHifiUi.text(g, font, scan,
                x + w - MapHifiUi.textWidth(font, scan, 12) - 14, r2 + 9,
                MapHifiUi.MUTED, 12);
        MapHifiUi.text(g, font, "只调整已有扫描周期参数，不改变扫描算法与缓存协议。",
                labelX, r2 + 35, MapHifiUi.MUTED, 12);

        int noteY = r2 + 76;
        if (noteY < bottom - 22) {
            MapHifiUi.text(g, font, "已渲染 RGB 缓存：开启（当前实现）",
                    labelX, noteY, 0xffb9c6cc, 12);
        }
    }

    private void renderMarker(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "标点", "自定义标点默认行为");
        int cardY = y + 40;
        MapHifiUi.card(g, x, cardY, w, Math.min(255, bottom - cardY));

        int labelX = x + 14;
        int controlX = x + Math.min(180, Math.max(110, w / 3));
        int controlW = Math.max(120, w - (controlX - x) - 18);
        int r1 = cardY + 28;
        int r2 = r1 + 44;
        int r3 = r2 + 44;

        MapHifiUi.text(g, font, "默认图标", labelX, r1 + 10, MapHifiUi.TEXT, 13);
        MapHifiUi.text(g, font, "默认颜色", labelX, r2 + 10, MapHifiUi.TEXT, 13);
        MapHifiUi.text(g, font, "图标大小", labelX, r3 + 10, MapHifiUi.TEXT, 13);

        markerDefaultIcon.setValue(MapUiSettings.customMarkerDefaultIcon());
        markerDefaultColor.setValue(MapUiSettings.customMarkerDefaultColor());
        markerDefaultSize.setValue(MapUiSettings.customMarkerDefaultSize());
        markerDefaultIcon.setBounds(controlX, r1, Math.min(270, controlW), 34);
        markerDefaultColor.setBounds(controlX, r2, Math.min(230, controlW), 34);
        markerDefaultSize.setBounds(controlX, r3 + 5, Math.min(230, controlW - 60), 24);

        MapUiControls.Control popup = null;
        if (markerDefaultIcon.popupOpen()) popup = markerDefaultIcon;
        else markerDefaultIcon.render(g, font, mouseX, mouseY);
        if (markerDefaultColor.popupOpen()) popup = markerDefaultColor;
        else markerDefaultColor.render(g, font, mouseX, mouseY);
        markerDefaultSize.render(g, font, mouseX, mouseY);
        String size = MapUiSettings.customMarkerDefaultSize() + " px";
        MapHifiUi.text(g, font, size,
                x + w - MapHifiUi.textWidth(font, size, 12) - 14, r3 + 10,
                MapHifiUi.MUTED, 12);

        int labelY = r3 + 48;
        MapHifiUi.divider(g, x + 12, labelY, x + w - 12);
        labelY += 10;
        toggleRow(g, font, x, labelY, w, "25% 以下隐藏名称",
                MapUiSettings.hideLabelsLowZoom(), mouseX, mouseY);
        toggleRow(g, font, x, labelY + 46, w, "名称自动避让",
                MapUiSettings.avoidLabels(), mouseX, mouseY);

        if (popup != null) popup.render(g, font, mouseX, mouseY);
    }

    private void renderStructure(
            GuiGraphics g, Font font, int x, int y, int w, int bottom,
            int mouseX, int mouseY) {
        title(g, font, x, y, w, "结构预设",
                "每个自动结构都有独立图标和单色；手工编辑后保持自定义");
        MapHifiUi.button(
                g, font, x + w - 100, y - 4, 100, 36,
                "恢复默认", false, false, mouseX, mouseY);
        int top = y + 44;
        int rowH = 46;
        int listH = Math.max(40, bottom - top - 4);
        int maxRows = Math.max(1, listH / rowH);
        MapMarkerStore.StructurePreset[] presets = MapMarkerStore.StructurePreset.values();
        int maxScroll = Math.max(0, presets.length - maxRows);
        structureScroll = Math.max(0, Math.min(structureScroll, maxScroll));

        int iconX = x + Math.min(250, Math.max(150, w * 42 / 100));
        int colorX = x + w - 48;

        MapUiControls.Control popup = null;
        for (int i = 0; i < maxRows; i++) {
            int index = structureScroll + i;
            if (index >= presets.length) break;
            MapMarkerStore.StructurePreset preset = presets[index];
            int ry = top + i * rowH;

            if (MapHifiUi.inside(mouseX, mouseY, x, ry, w, rowH - 2)) {
                g.fill(x, ry, x + w, ry + rowH - 2, 0xff18242b);
            }
            MapHifiUi.text(g, font, preset.label, x + 12, ry + 15, MapHifiUi.TEXT, 14);

            MapUiControls.MarkerIconSelect select = structureIcons.get(preset);
            MapUiControls.ColorInput color = structureColors.get(preset);
            select.setValue(MapUiSettings.structurePresetIcon(preset));
            color.setValue(MapUiSettings.structurePresetColor(preset));
            select.setBounds(iconX, ry + 6, Math.max(140, colorX - iconX - 14), 34);
            color.setBounds(colorX, ry + 6, 36, 34);

            if (select.popupOpen()) popup = select;
            else select.render(g, font, mouseX, mouseY);
            if (color.popupOpen()) popup = color;
            else color.render(g, font, mouseX, mouseY);
        }

        if (maxScroll > 0) {
            int trackX = x + w - 3;
            int thumbH = Math.max(28, listH * maxRows / presets.length);
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
        int cardY = y + 40;
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
        int cardY = y + 40;
        MapHifiUi.card(g, x, cardY, w, Math.min(130, bottom - cardY));
        MapHifiUi.text(g, font, "地图底层缓存 / 扫描 / 渲染管线",
                x + 14, cardY + 24, MapHifiUi.TEXT, 14);
        MapHifiUi.text(g, font, "本页不提供破坏性操作；当前 UI 接入不修改底层逻辑。",
                x + 14, cardY + 55, MapHifiUi.MUTED, 12);
        MapHifiUi.text(g, font, "手绘 · 现代 / 古式仅预留设置入口，暂不参与地图绘制。",
                x + 14, cardY + 82, MapHifiUi.MUTED, 12);
    }

    private static void shortcutRow(
            GuiGraphics g, Font font, int x, int y, int w, String label, String key) {
        MapHifiUi.text(g, font, label, x, y + 10, MapHifiUi.TEXT, 14);
        int kw = Math.max(74, MapHifiUi.textWidth(font, key, 13) + 24);
        int kx = x + w - kw;
        g.fill(kx, y + 3, kx + kw, y + 37, 0xff25313a);
        MapHifiUi.border(g, kx, y + 3, kw, 34, 0xff48565f);
        MapHifiUi.centeredText(g, font, key, kx + kw / 2, y + 13, 0xffe6ecef, 13);
    }

    private static void title(
            GuiGraphics g, Font font, int x, int y, int w,
            String title, String desc) {
        MapHifiUi.text(g, font, title, x, y, 0xffffffff, 18);
        int descX = x + MapHifiUi.textWidth(font, title, 18) + 16;
        if (descX + MapHifiUi.textWidth(font, desc, 12) < x + w) {
            MapHifiUi.text(g, font, desc, descX, y + 5, MapHifiUi.MUTED, 12);
        }
        MapHifiUi.divider(g, x, y + 30, x + w);
    }

    private static int toggleRow(
            GuiGraphics g, Font font, int x, int y, int w, String label,
            boolean on, int mouseX, int mouseY) {
        if (MapHifiUi.inside(mouseX, mouseY, x + 8, y, w - 16, 43)) {
            g.fill(x + 8, y, x + w - 8, y + 43, 0xff1d2a31);
        }
        MapHifiUi.text(g, font, label, x + 14, y + 13, MapHifiUi.TEXT, 14);
        MapHifiUi.switchBox(g, x + w - 57, y + 9, on, mouseX, mouseY);
        return y + 46;
    }

    boolean mouseClicked(
            double mouseX, double mouseY, int button, int width, int height) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;

        Rect r = rect(width, height);
        if (MapHifiUi.modalCloseHit(mouseX, mouseY, r.x, r.y, r.w)) {
            close();
            return true;
        }
        if (MapHifiUi.inside(
                mouseX, mouseY, r.x + r.w - 94, r.y + r.h - 45, 78, 36)) {
            close();
            return true;
        }

        int navW = width <= 900 ? 150 : 190;
        navW = Math.min(navW, Math.max(130, r.w / 3));
        int navY = r.y + MapHifiUi.MODAL_HEADER_H + 13;
        int navH = 46;
        Page[] pages = Page.values();
        for (int i = 0; i < pages.length; i++) {
            int py = navY + i * navH;
            if (MapHifiUi.inside(mouseX, mouseY, r.x + 10, py, navW - 20, 43)) {
                page = pages[i];
                structureScroll = 0;
                pageScroll = 0;
                pageScrollMax = 0;
                closePopups();
                return true;
            }
        }

        if (handlePopupClick(mouseX, mouseY)) return true;

        int cx = r.x + navW + 22;
        int cy = r.y + MapHifiUi.MODAL_HEADER_H + 18 - pageScroll;
        int cw = r.w - navW - 44;

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
                int cardY = cy + 40;
                int ry = cardY + 12;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setShowCoords(!MapUiSettings.showCoords()); return true;
                }
                ry += 46;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setPlayerRing(!MapUiSettings.playerRing()); return true;
                }
                ry += 46;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setMarkerLayerEnabled(!MapUiSettings.markerLayerEnabled()); return true;
                }
                ry += 46;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setStructureLayerEnabled(!MapUiSettings.structureLayerEnabled()); return true;
                }
                ry += 46;
                if (row(mouseX, mouseY, cx, ry, cw)) {
                    MapUiSettings.setLabels(!MapUiSettings.labels()); return true;
                }
                ry += 46;
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
                int cardY = cy + 40;
                int r3 = cardY + 28 + 44 + 44;
                int labelY = r3 + 48;
                if (row(mouseX, mouseY, cx, labelY, cw)) {
                    MapUiSettings.setHideLabelsLowZoom(
                            !MapUiSettings.hideLabelsLowZoom()); return true;
                }
                if (row(mouseX, mouseY, cx, labelY + 46, cw)) {
                    MapUiSettings.setAvoidLabels(!MapUiSettings.avoidLabels()); return true;
                }
            }
            case STRUCTURE -> {
                if (MapHifiUi.inside(mouseX, mouseY, cx + cw - 100, cy - 4, 100, 36)) {
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
                int top = cy + 44;
                int rowH = 46;
                int listH = Math.max(40, r.y + r.h - 54 - top - 4);
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
        if (markerDefaultIcon.popupOpen() && markerDefaultIcon.mouseScrolled(scrollY)) {
            return true;
        }
        for (MapUiControls.MarkerIconSelect icon : structureIcons.values()) {
            if (icon.popupOpen() && icon.mouseScrolled(scrollY)) return true;
        }
        if (page == Page.STRUCTURE && scrollY != 0) {
            structureScroll = Math.max(0, structureScroll + (scrollY > 0 ? -1 : 1));
            closeStructurePopups();
            return true;
        }
        if (scrollY != 0 && pageScrollMax > 0) {
            pageScroll = Math.max(
                    0, Math.min(pageScrollMax,
                            pageScroll + (scrollY > 0 ? -42 : 42)));
            closePopups();
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

    private static int pageContentHeight(Page page) {
        return switch (page) {
            case GENERAL -> 520;
            case DISPLAY -> 410;
            case PERFORMANCE -> 330;
            case MARKER -> 380;
            case STRUCTURE -> 0;
            case SHORTCUT -> 220;
            case ADVANCED -> 190;
        };
    }

    private static boolean row(double mouseX, double mouseY, int x, int y, int w) {
        return MapHifiUi.inside(mouseX, mouseY, x + 8, y, w - 16, 43);
    }

    private static Rect rect(int width, int height) {
        int w = Math.max(320, Math.min(1030, (int) Math.floor(width * 0.94)));
        int availableH = Math.max(240, height - MapHifiUi.HEADER_H - 20);
        int h = Math.min(710, availableH);
        int x = Math.max(8, (width - w) / 2);
        int y = MapHifiUi.HEADER_H
                + Math.max(10, (height - MapHifiUi.HEADER_H - h) / 2);
        if (y + h > height - 10) y = Math.max(MapHifiUi.HEADER_H + 2, height - h - 10);
        return new Rect(x, y, w, h);
    }

    private record Rect(int x, int y, int w, int h) {}
}
