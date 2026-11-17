package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

import static com.wanstu.cartographv3.InGameMapPreferences.Flag;
import static com.wanstu.cartographv3.InGameMapPreferences.FONT_SCALE;
import static com.wanstu.cartographv3.InGameMapPreferences.MAX_SCALE;
import static com.wanstu.cartographv3.InGameMapPreferences.MIN_SCALE;
import static com.wanstu.cartographv3.InGameMapPreferences.UI_SCALE;
import static com.wanstu.cartographv3.InGameMapUi.*;

final class InGameMapDialogs {
    private enum Mode { NONE, SETTINGS, EDIT, ABOUT }
    private enum Tab {
        GENERAL("通用", "settings"), DISPLAY("显示", "display"), PERFORMANCE("性能与缓存", "performance"),
        MARKER("标点", "pin"), STRUCTURE("结构", "temple"), SHORTCUT("快捷键", "keyboard"), ADVANCED("高级", "wrench");
        final String label;
        final String icon;
        Tab(String label, String icon) { this.label = label; this.icon = icon; }
    }

    private static final int[] COLORS = {
            0xf3c94f, 0xffe14e, 0xe9edf2, 0xdbaa81, 0xf08584, 0xe25558,
            0xa77ce8, 0x68b5f7, 0x70c7ce, 0x71d797, 0x89a972, 0x758794
    };
    private static final int STRUCTURE_ROW = 23;
    private static final int DISPLAY_ROWS = 8;

    private final InGameMapScreen screen;
    private final InGameMapPreferences preferences;
    private final Runnable openWeb;
    private final Map<MapMarkerStore.StructurePreset, CartographSettings.StructureAppearance> draft =
            new EnumMap<>(MapMarkerStore.StructurePreset.class);
    private Mode mode = Mode.NONE;
    private Tab tab = Tab.GENERAL;
    private int rowOffset;
    private int rowsInView;
    private boolean resetPresets;
    private MapMarkerStore.Marker editing;
    private MapMarkerStore.Icon markerIcon;
    private int markerColor;
    private final String[] editorValues = new String[5];
    private Consumer<MapMarkerStore.Icon> iconResult;
    private IntConsumer colorResult;
    private String hexValue;
    private EditBox hexField;

    InGameMapDialogs(InGameMapScreen screen, Runnable openWeb) {
        this.screen = screen;
        this.preferences = screen.preferences;
        this.openWeb = openWeb;
    }

    boolean open() { return mode != Mode.NONE; }
    boolean settingsOpen() { return mode == Mode.SETTINGS; }
    boolean aboutOpen() { return mode == Mode.ABOUT; }

    void settings() {
        mode = Mode.SETTINGS;
        tab = Tab.GENERAL;
        rowOffset = 0;
        draft.clear();
        resetPresets = false;
    }

    void about() { mode = Mode.ABOUT; }

    void edit(MapMarkerStore.Marker marker, InGameMapScreen.Point point) {
        mode = Mode.EDIT;
        editing = marker;
        editorValues[0] = marker == null ? "" : marker.name;
        editorValues[1] = Integer.toString(marker == null ? point.x() : marker.x);
        editorValues[2] = Integer.toString(marker == null ? point.y() : marker.y);
        editorValues[3] = Integer.toString(marker == null ? point.z() : marker.z);
        editorValues[4] = marker == null ? "" : marker.description;
        markerIcon = marker == null ? preferences.markerIcon() : marker.icon;
        markerColor = marker == null ? preferences.number("markerColor", 0xffd34e, 0, 0xffffff) : marker.accent();
        rebuildInputs();
    }

    void resize() { rebuildInputs(); }

    void close() {
        if (iconResult != null || colorResult != null) {
            dismissPicker();
            return;
        }
        mode = Mode.NONE;
        screen.clearInputs();
    }

    private Box centered(int w, int h) {
        int width = Math.min(screen.layoutWidth() - 14, w);
        int height = Math.min(screen.layoutHeight() - 20, h);
        return new Box((screen.layoutWidth() - width) / 2, (screen.layoutHeight() - height) / 2, width, height);
    }

    private Box settingsBox() { return centered(526, 333); }
    private Box editorBox() { return centered(339, 185); }
    private Box colorsBox() { return centered(228, 126); }
    private Box iconsBox() { return centered(240, 160); }

    private static int editorRowY(Box box, int row) {
        int step = Math.clamp((box.h() - 83) / 3, 17, 25);
        return box.y() + 33 + row * step;
    }

    private Box editorName(Box box) { return new Box(box.x() + 58, editorRowY(box, 0), box.w() - 71, 16); }
    private Box editorCoord(Box box, int i) {
        int gap = 15;
        int w = (box.w() - 83 - gap * 2) / 3;
        return new Box(box.x() + 64 + (w + gap) * i, editorRowY(box, 1), w, 16);
    }
    private Box editorNote(Box box) { return new Box(box.x() + 58, editorRowY(box, 2), box.w() - 71, 16); }
    private Box hexBox(Box box) { return new Box(box.x() + 44, box.bottom() - 51, box.w() - 59, 16); }

    private void rebuildInputs() {
        screen.clearInputs();
        hexField = null;
        if (colorResult != null) {
            Box area = hexBox(colorsBox());
            hexField = input(area, "颜色", hexValue, 7, value -> hexValue = value);
        } else if (iconResult == null && mode == Mode.EDIT) {
            Box editor = editorBox();
            input(editorName(editor), "名称", editorValues[0], 40, value -> editorValues[0] = value);
            String[] labels = {"X", "Y", "Z"};
            for (int i = 0; i < labels.length; i++) {
                int field = i + 1;
                input(editorCoord(editor, i), labels[i], editorValues[field], 12,
                        value -> editorValues[field] = value);
            }
            input(editorNote(editor), "简介", editorValues[4], 160, value -> editorValues[4] = value);
        }
    }

    private EditBox input(Box box, String narration, String value, int limit, Consumer<String> action) {
        var edit = new ScaledInput(Minecraft.getInstance().font, box, screen.fontScale(),
                Component.literal(narration));
        edit.setBordered(false);
        edit.setTextColor(TEXT);
        edit.setMaxLength(limit);
        edit.setValue(value == null ? "" : value);
        edit.setResponder(action);
        screen.addInput(edit);
        return edit;
    }

    private static final class ScaledInput extends EditBox {
        private final float scale;

        ScaledInput(Font font, Box box, float scale, Component narration) {
            super(font, box.x() + 4, box.y() + Math.round((box.h() - font.lineHeight * scale) / 2),
                    Math.max(1, (int) ((box.w() - 8) / scale)),
                    Math.max(1, (int) ((box.h() - 4) / scale)), narration);
            this.scale = scale;
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            return super.mouseClicked(getX() + (x - getX()) / scale,
                    getY() + (y - getY()) / scale, button);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (scale == 1) {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
                return;
            }
            var pose = graphics.pose();
            pose.pushPose();
            try {
                pose.translate(getX(), getY(), 0);
                pose.scale(scale, scale, 1);
                pose.translate(-getX(), -getY(), 0);
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
            } finally {
                pose.popPose();
            }
        }
    }

    void render(InGameMapUi ui) {
        Box viewport = new Box(0, 0, screen.layoutWidth(), screen.layoutHeight());
        ui.fill(viewport, 0xa4070b0e);
        if (mode == Mode.SETTINGS) renderSettings(ui);
        if (mode == Mode.EDIT) renderEditor(ui);
        if (mode == Mode.ABOUT) renderAbout(ui);

        if (iconResult != null || colorResult != null) {
            ui.clearActions();
            ui.fill(viewport, 0x85070b0e);
            if (iconResult != null) renderIcons(ui);
            else renderColors(ui);
        }
    }

    private void title(InGameMapUi ui, Box box, String icon, String text, Runnable close) {
        ui.panel(box);
        ui.glyph(icon, box.x() + 8, box.y() + 8, 11, TEXT);
        ui.text(text, box.x() + 24, box.y() + 9, TEXT);
        ui.divider(box.x(), box.y() + 26, box.w());
        ui.iconButton(new Box(box.right() - 21, box.y() + 4, 17, 17), "close", MUTED, close);
    }

    private void renderSettings(InGameMapUi ui) {
        Box box = settingsBox();
        title(ui, box, "settings", "设置", this::close);
        int railWidth = Math.clamp(box.w() / 5, 71, 98);
        ui.fill(new Box(box.x() + 1, box.y() + 27, railWidth, box.h() - 28), 0xff131b20);
        ui.fill(new Box(box.x() + railWidth + 1, box.y() + 27, 1, box.h() - 28), LINE);
        int tabStep = Math.clamp((box.h() - 38) / Tab.values().length, 11, 21);
        for (Tab item : Tab.values()) {
            Box nav = new Box(box.x() + 6, box.y() + 33 + item.ordinal() * tabStep, railWidth - 10, tabStep - 2);
            if (item == tab || ui.hovered(nav)) ui.fill(nav, item == tab ? 0xff2e414e : FIELD);
            if (item == tab) ui.fill(new Box(nav.x(), nav.y(), 2, nav.h()), BLUE);
            int iconSize = Math.min(11, nav.h() - 3);
            ui.glyph(item.icon, nav.x() + 6, nav.y() + (nav.h() - iconSize) / 2, iconSize, TEXT);
            float textScale = Math.min(1f, (nav.h() - 2f) / ui.textHeight());
            int y = nav.y() + (nav.h() - ui.textHeight(textScale)) / 2 + 1;
            ui.smallText(item.label, nav.x() + 21, y, nav.w() - 23, TEXT, textScale);
            ui.action(nav, () -> { tab = item; rowOffset = 0; });
        }

        Box content = new Box(box.x() + railWidth + 11, box.y() + 34,
                box.w() - railWidth - 21, box.h() - 66);
        int footerY = box.bottom() - 23;
        ui.divider(content.x(), footerY - 5, content.w());
        if (tab == Tab.STRUCTURE) {
            ui.button(new Box(box.right() - 103, footerY, 45, 17), "取消", false, this::discardPresets);
            ui.button(new Box(box.right() - 53, footerY, 44, 17), "应用", true, this::applyPresets);
            structureSettings(ui, content);
            return;
        }
        ui.button(new Box(box.right() - 53, footerY, 44, 17), "完成", true, this::close);

        ui.text(tab.label, content.x(), content.y() + 2, TEXT);
        int body = content.y() + 18;
        int total = switch (tab) {
            case GENERAL -> 9;
            case DISPLAY -> DISPLAY_ROWS;
            case PERFORMANCE -> 3;
            case MARKER -> 5;
            case SHORTCUT, ADVANCED -> 2;
            default -> 0;
        };
        rowsInView = Math.max(1, (content.bottom() - body) / 22);
        rowOffset = Math.clamp(rowOffset, 0, Math.max(0, total - rowsInView));

        ui.clip(new Box(content.x(), body, content.w(), content.bottom() - body));
        for (int i = rowOffset; i < Math.min(total, rowOffset + rowsInView); i++) {
            Box row = new Box(content.x(), body + 22 * (i - rowOffset), content.w(), 22);
            switch (tab) {
                case GENERAL -> generalRow(ui, row, i);
                case DISPLAY -> displayRow(ui, row, i);
                case PERFORMANCE -> performanceRow(ui, row, i);
                case MARKER -> markerRow(ui, row, i);
                case SHORTCUT -> {
                    if (i == 0) summary(ui, row, "打开地图", "M");
                    else summary(ui, row, "关闭地图", "Esc");
                }
                case ADVANCED -> {
                    if (i == 0) toggle(ui, row, "缓存调试信息", Flag.DEBUG);
                    else action(ui, row, "地图图像", "重新着色", screen::refreshRaster);
                }
                default -> { }
            }
        }
        ui.unclip();
        scrollbar(ui, content.right(), body, Math.min(rowsInView, total) * 22, total);
    }

    private void generalRow(InGameMapUi ui, Box row, int i) {
        switch (i) {
            case 0 -> {
                boolean inGame = CartographSettings.frontendTarget() == CartographFrontendTarget.IN_GAME;
                action(ui, row, "M 打开的位置", inGame ? "游戏内" : "WebUI", () -> CartographSettings.setFrontendTarget(
                        inGame ? CartographFrontendTarget.WEB : CartographFrontendTarget.IN_GAME));
            }
            case 1 -> action(ui, row, "WebUI", "在浏览器打开", openWeb);
            case 2 -> summary(ui, row, "地图风格", "卫星 · 写实");
            case 3 -> toggle(ui, row, "显示坐标", Flag.COORDS);
            case 4 -> toggle(ui, row, "玩家定位圆环", Flag.PLAYER_RING);
            case 5 -> toggle(ui, row, "拖拽地图", Flag.DRAG_MAP);
            case 6 -> action(ui, row, "玩家图标", preferences.enabled(Flag.CROSS_PLAYER) ? "十字" : "箭头",
                    () -> preferences.toggle(Flag.CROSS_PLAYER));
            case 7 -> color(ui, row, "玩家图标颜色",
                    preferences.number("playerColor", 0xffe14e, 0, 0xffffff),
                    value -> preferences.number("playerColor", value));
            case 8 -> pixelStepper(ui, row, "玩家图标大小", "playerSize", InGameMapScreen.DEFAULT_PLAYER_SIZE, 6, 30);
            default -> { }
        }
    }

    private void displayRow(InGameMapUi ui, Box row, int i) {
        switch (i) {
            case 0 -> toggle(ui, row, "自定义标点", Flag.CUSTOM);
            case 1 -> toggle(ui, row, "自然结构", Flag.STRUCTURES);
            case 2 -> toggle(ui, row, "名称标签", Flag.NAMES);
            case 3 -> toggle(ui, row, "区块网格", Flag.GRID);
            case 4 -> toggle(ui, row, "固定玩家图标大小", Flag.FIX_PLAYER_SIZE);
            case 5 -> stepper(ui, row, "标点随缩放变化", preferences.number("markerScale", 35, 0, 100),
                    0, 100, 5, "%", value -> preferences.number("markerScale", value));
            case 6 -> scaleStepper(ui, row, "字号", FONT_SCALE);
            case 7 -> scaleStepper(ui, row, "UI 缩放倍率", UI_SCALE);
            default -> { }
        }
    }

    private void scaleStepper(InGameMapUi ui, Box row, String label, String key) {
        stepper(ui, row, label, preferences.scalePercent(key), MIN_SCALE, MAX_SCALE, 5, "%", value -> {
            screen.displayScale(key, value);
            if (key.equals(UI_SCALE)) rowOffset = DISPLAY_ROWS - 1;
        });
    }

    private void performanceRow(InGameMapUi ui, Box row, int i) {
        switch (i) {
            case 0 -> stepper(ui, row, "每批请求区块数", CartographSettings.requestBatchSize(),
                    8, 256, 8, " 区块", CartographSettings::setRequestBatchSize);
            case 1 -> stepper(ui, row, "实时扫描间隔", CartographSettings.liveScanIntervalTicks(),
                    5, 100, 5, " tick", screen.backend::setLiveScanInterval);
            case 2 -> summary(ui, row, "地表事实缓存", Integer.toString(screen.backend.cachedCount()));
            default -> { }
        }
    }

    private void markerRow(InGameMapUi ui, Box row, int i) {
        switch (i) {
            case 0 -> action(ui, row, "默认图标", preferences.markerIcon().label, () -> icons(preferences::markerIcon));
            case 1 -> color(ui, row, "默认颜色", preferences.number("markerColor", 0xffd34e, 0, 0xffffff),
                    value -> preferences.number("markerColor", value));
            case 2 -> pixelStepper(ui, row, "标点大小", "markerSize", InGameMapScreen.DEFAULT_MARKER_SIZE, 7, 25);
            case 3 -> toggle(ui, row, "25% 以下隐藏标签", Flag.HIDE_LOW_ZOOM);
            case 4 -> toggle(ui, row, "标签避让", Flag.AVOID_LABELS);
            default -> { }
        }
    }

    private void rowLabel(InGameMapUi ui, Box row, String label) {
        rowLabel(ui, row, label, 110);
    }

    private void rowLabel(InGameMapUi ui, Box row, String label, int rightPadding) {
        ui.divider(row.x(), row.bottom() - 1, row.w());
        ui.smallText(label, row.x() + 2, row.y() + (row.h() - ui.textHeight()) / 2 + 1,
                row.w() - rightPadding, TEXT);
    }

    private void summary(InGameMapUi ui, Box row, String label, String value) {
        rowLabel(ui, row, label);
        ui.center(value, new Box(row.right() - 103, row.y() + 2, 100, 17), MUTED);
    }

    private void action(InGameMapUi ui, Box row, String label, String value, Runnable run) {
        rowLabel(ui, row, label);
        ui.button(new Box(row.right() - 103, row.y() + 2, 100, 17), value, false, run);
    }

    private void toggle(InGameMapUi ui, Box row, String label, Flag flag) {
        rowLabel(ui, row, label, 31);
        boolean checked = preferences.enabled(flag);
        Box box = new Box(row.right() - 27, row.y() + 6, 21, 10);
        ui.fill(box, checked ? 0xff3776ab : FIELD);
        ui.border(box, checked ? BLUE : LINE);
        ui.fill(new Box(box.x() + (checked ? 12 : 2), box.y() + 2, 7, 6), checked ? TEXT : SOFT);
        ui.action(row, () -> preferences.toggle(flag));
    }

    private void color(InGameMapUi ui, Box row, String label, int value, IntConsumer result) {
        rowLabel(ui, row, label);
        Box box = new Box(row.right() - 103, row.y() + 2, 100, 17);
        ui.button(box, hex(value), false, () -> colors(value, result));
        ui.fill(new Box(box.x() + 6, box.y() + 4, 9, 9), 0xff000000 | value);
    }

    private void stepper(InGameMapUi ui, Box row, String label, int value, int min, int max,
                         int step, String units, IntConsumer result) {
        drawStepper(ui, row, label, value + units,
                () -> result.accept(Math.max(min, value - step)),
                () -> result.accept(Math.min(max, value + step)));
    }

    private void drawStepper(InGameMapUi ui, Box row, String label, String value,
                             Runnable decrease, Runnable increase) {
        int width = Math.clamp(row.w() / 2, 68, 105);
        rowLabel(ui, row, label, width + 5);
        int x = row.right() - width;
        ui.button(new Box(x, row.y() + 2, 16, 17), "−", false, decrease);
        ui.center(value, new Box(x + 18, row.y() + 2, width - 38, 17), TEXT);
        ui.button(new Box(row.right() - 18, row.y() + 2, 16, 17), "+", false, increase);
    }

    private void pixelStepper(InGameMapUi ui, Box row, String label, String key, int fallback, int min, int max) {
        int size = preferences.number(key, fallback, min, max);
        String value = Math.round(size * screen.pixelsPerUnit()) + " px";
        drawStepper(ui, row, label, value,
                () -> preferences.number(key, Math.max(min, size - 1)),
                () -> preferences.number(key, Math.min(max, size + 1)));
    }

    private CartographSettings.StructureAppearance appearance(MapMarkerStore.StructurePreset preset) {
        var changed = draft.get(preset);
        if (changed != null) return changed;
        if (resetPresets) return new CartographSettings.StructureAppearance(preset.defaultIcon, preset.defaultAccent);
        return CartographSettings.structureAppearance(preset);
    }

    private void structureSettings(InGameMapUi ui, Box content) {
        ui.text("结构预设", content.x(), content.y() + 4, TEXT);
        ui.button(new Box(content.right() - 64, content.y(), 63, 17), "恢复默认", false,
                () -> { draft.clear(); resetPresets = true; });
        int body = content.y() + 21;
        var presets = MapMarkerStore.StructurePreset.values();
        rowsInView = Math.max(1, (content.bottom() - body) / STRUCTURE_ROW);
        rowOffset = Math.clamp(rowOffset, 0, Math.max(0, presets.length - rowsInView));
        for (int i = rowOffset; i < Math.min(presets.length, rowOffset + rowsInView); i++) {
            var preset = presets[i];
            var appearance = appearance(preset);
            int y = body + (i - rowOffset) * STRUCTURE_ROW;
            int control = Math.clamp(content.w() / 3, 57, 105);
            Box row = new Box(content.x(), y, content.w() - 4, STRUCTURE_ROW);
            ui.divider(row.x(), row.bottom() - 1, row.w());
            ui.smallText(preset.label, row.x() + 3, y + 8, Math.max(42, row.w() - control - 31), TEXT);
            Box icon = new Box(row.right() - control - 25, y + 3, control, 17);
            ui.button(icon, appearance.icon().label, false, () -> icons(value -> draft.put(preset,
                    new CartographSettings.StructureAppearance(value, appearance.color()))));
            ui.glyph(appearance.icon(), icon.x() + 3, icon.y() + 3, 11, 0xff000000 | appearance.color());
            Box color = new Box(row.right() - 19, y + 4, 16, 15);
            ui.fill(color, 0xff000000 | appearance.color());
            ui.border(color, MUTED);
            ui.action(color, () -> colors(appearance.color(), value -> draft.put(preset,
                    new CartographSettings.StructureAppearance(appearance.icon(), value))));
        }
        scrollbar(ui, content.right(), body, rowsInView * STRUCTURE_ROW, presets.length);
    }

    private void scrollbar(InGameMapUi ui, int right, int top, int track, int total) {
        if (total <= rowsInView || track <= 0) return;
        int size = Math.max(10, track * rowsInView / total);
        int offset = (track - size) * rowOffset / (total - rowsInView);
        ui.fill(new Box(right - 2, top + offset, 2, size), SOFT);
    }

    private void applyPresets() {
        try {
            CartographSettings.updateStructurePresets(resetPresets, draft);
            screen.refreshMarkers();
            screen.notice("结构预设已保存");
            discardPresets();
        } catch (IOException failure) {
            screen.notice("结构预设保存失败");
        }
    }

    private void discardPresets() {
        draft.clear();
        resetPresets = false;
        close();
    }

    boolean scroll(double x, double y, double delta) {
        if (mode == Mode.SETTINGS && iconResult == null && colorResult == null && settingsBox().at(x, y)) {
            rowOffset = Math.max(0, rowOffset - (int) Math.signum(delta) * 2);
        }
        return true;
    }

    private void renderEditor(InGameMapUi ui) {
        Box box = editorBox();
        title(ui, box, "pin", editing == null ? "添加标点" : "编辑标点", this::close);
        ui.text("名称", box.x() + 13, editorRowY(box, 0) + 5, TEXT);
        field(ui, editorName(box));
        ui.text("坐标", box.x() + 13, editorRowY(box, 1) + 5, TEXT);
        String[] coords = {"X", "Y", "Z"};
        for (int i = 0; i < coords.length; i++) {
            Box area = editorCoord(box, i);
            ui.text(coords[i], area.x() - 10, area.y() + 5, MUTED);
            field(ui, area);
        }
        ui.text("简介", box.x() + 13, editorRowY(box, 2) + 5, TEXT);
        field(ui, editorNote(box));
        ui.text("样式", box.x() + 13, editorRowY(box, 3) + 5, TEXT);
        Box icon = new Box(box.x() + 58, editorRowY(box, 3) - 1, (box.w() - 76) / 2, 19);
        ui.button(icon, markerIcon.label, false, () -> icons(value -> markerIcon = value));
        ui.glyph(markerIcon, icon.x() + 4, icon.y() + 3, 13, 0xff000000 | markerColor);
        Box color = new Box(icon.right() + 7, icon.y(), box.right() - icon.right() - 20, 19);
        ui.button(color, hex(markerColor), false, () -> colors(markerColor, value -> markerColor = value));
        ui.fill(new Box(color.x() + 5, color.y() + 5, 9, 9), 0xff000000 | markerColor);
        int footer = box.bottom() - 24;
        ui.divider(box.x() + 9, footer - 6, box.w() - 18);
        ui.button(new Box(box.right() - 105, footer, 45, 17), "取消", false, this::close);
        ui.button(new Box(box.right() - 54, footer, 45, 17), "保存", true, this::saveMarker);
    }

    private void field(InGameMapUi ui, Box box) {
        ui.fill(box, FIELD);
        ui.border(box, LINE);
    }

    private void saveMarker() {
        String name = editorValues[0].trim();
        if (name.isEmpty()) {
            screen.notice("请输入标点名称");
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(editorValues[1].trim());
            y = Integer.parseInt(editorValues[2].trim());
            z = Integer.parseInt(editorValues[3].trim());
        } catch (NumberFormatException failure) {
            screen.notice("请输入整数坐标");
            return;
        }
        if (editing == null) {
            screen.backend.createMarker(x, y, z, name, markerIcon, MapMarkerStore.DEFAULT_PRIMARY_COLOR,
                    markerColor, MapMarkerStore.DEFAULT_LINE_COLOR, editorValues[4]);
        } else {
            screen.backend.updateMarker(editing.uuid(), x, y, z, name, markerIcon,
                    editing.primary(), markerColor, editing.line(), editorValues[4]);
        }
        screen.refreshMarkers();
        screen.notice("标点已保存");
        close();
    }

    private void renderAbout(InGameMapUi ui) {
        Box box = centered(270, 143);
        title(ui, box, "info", "关于", this::close);
        ui.glyph("map", box.x() + 13, box.y() + 36, 29, ACCENT);
        ui.smallText("地图册 · Map Atlas", box.x() + 50, box.y() + 37, box.w() - 60, TEXT);
        ui.smallText("Cartograph V3.1 · 游戏内地图", box.x() + 50, box.y() + 52, box.w() - 60, MUTED);
        ui.divider(box.x() + 12, box.y() + 71, box.w() - 24);
        ui.text("地图风格", box.x() + 13, box.y() + 83, SOFT);
        ui.text("卫星 · 写实", box.x() + 83, box.y() + 83, TEXT);
        ui.text("地图数据", box.x() + 13, box.y() + 100, SOFT);
        ui.smallText("已生成区块的地表数据", box.x() + 83, box.y() + 100, box.w() - 93, TEXT);
        ui.button(new Box(box.right() - 55, box.bottom() - 25, 43, 17), "关闭", true, this::close);
    }

    private void icons(Consumer<MapMarkerStore.Icon> result) {
        iconResult = result;
        rebuildInputs();
    }

    private void colors(int value, IntConsumer result) {
        colorResult = result;
        hexValue = hex(value);
        rebuildInputs();
    }

    private void dismissPicker() {
        colorResult = null;
        iconResult = null;
        rebuildInputs();
    }

    private void renderIcons(InGameMapUi ui) {
        Box box = iconsBox();
        title(ui, box, "pin", "选择图标", this::dismissPicker);
        var icons = InGameMapUi.selectableIcons();
        int columns = Math.max(6, (box.w() - 16) / 18);
        String hovered = "";
        for (int i = 0; i < icons.size(); i++) {
            var icon = icons.get(i);
            Box cell = new Box(box.x() + 8 + i % columns * 18, box.y() + 30 + i / columns * 18, 17, 17);
            ui.iconButton(cell, InGameMapUi.iconName(icon), TEXT, () -> {
                var callback = iconResult;
                dismissPicker();
                callback.accept(icon);
            });
            if (ui.hovered(cell)) hovered = icon.label;
        }
        ui.center(hovered, new Box(box.x() + 4, box.bottom() - 16, box.w() - 8, 12), MUTED);
    }

    private void renderColors(InGameMapUi ui) {
        Box box = colorsBox();
        title(ui, box, "edit", "选择颜色", this::dismissPicker);
        int step = (box.w() - 20) / 12;
        for (int i = 0; i < COLORS.length; i++) {
            int color = COLORS[i];
            Box item = new Box(box.x() + 10 + i * step, box.y() + 37, step - 2, 17);
            ui.fill(item, 0xff000000 | color);
            ui.border(item, ui.hovered(item) ? TEXT : LINE);
            ui.action(item, () -> hexField.setValue(hex(color)));
        }
        ui.text("颜色", box.x() + 12, box.bottom() - 46, TEXT);
        field(ui, hexBox(box));
        ui.button(new Box(box.right() - 104, box.bottom() - 25, 45, 17), "取消", false, this::dismissPicker);
        ui.button(new Box(box.right() - 53, box.bottom() - 25, 43, 17), "应用", true, this::applyColor);
    }

    private void applyColor() {
        if (!hexValue.matches("#?[0-9a-fA-F]{6}")) {
            screen.notice("请输入 6 位十六进制颜色");
            return;
        }
        int value = Integer.parseInt(hexValue.replace("#", ""), 16);
        var callback = colorResult;
        dismissPicker();
        callback.accept(value);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xffffff);
    }
}
