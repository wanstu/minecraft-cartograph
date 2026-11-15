package com.wanstu.cartographv3;

import net.ethrocky.pane.core.Component;
import net.ethrocky.pane.core.Rect;
import net.ethrocky.pane.core.Size;
import net.ethrocky.pane.core.State;
import net.ethrocky.pane.core.UiEvent;
import net.ethrocky.pane.core.layout.Flex;
import net.ethrocky.pane.core.style.Style;
import net.ethrocky.pane.core.style.Theme;
import net.ethrocky.pane.widget.Button;
import net.ethrocky.pane.widget.Checkbox;
import net.ethrocky.pane.widget.Label;
import net.ethrocky.pane.widget.Panel;
import net.ethrocky.pane.widget.ScrollContainer;
import net.ethrocky.pane.widget.Slider;
import net.ethrocky.pane.widget.Tabs;
import net.ethrocky.pane.widget.Window;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Cartograph V3.1 UI shell implemented with Pane.
 *
 * The map renderer/cache remains owned by MapScreenV3.  This class owns UI
 * layout, widgets, scrolling and input routing.  It intentionally avoids the
 * old MapHifiUi/MapUiControls coordinate plumbing.
 */
final class MapPaneUi {
    private static final int TOPBAR_H = 62;
    private static final Theme THEME = new Theme(
            0xff10191e,
            0xff172128,
            0xffeef2f4,
            0xff95a2aa,
            0xffffd34e,
            0xff40505a,
            8,
            6);

    private final MapScreenV3 host;
    private final Panel topbar = Panel.row();
    private final Panel zoomHud = Panel.row();
    private final List<Window> windows = new ArrayList<>();

    private final State<String> statusText = State.of("V3.1 · Pane");
    private final State<String> zoomText = State.of("100%");

    private final State<Float> uiScaleLevel =
            State.of((float) MapUiSettings.uiScaleLevel());
    private final State<String> uiScaleText =
            State.of(scaleLabel(MapUiSettings.uiScaleLevel()));
    private final State<Float> fontScaleLevel =
            State.of((float) MapUiSettings.fontScaleLevel());
    private final State<String> fontScaleText =
            State.of(scaleLabel(MapUiSettings.fontScaleLevel()));

    private final State<Boolean> markerLayer =
            State.of(MapUiSettings.markerLayerEnabled());
    private final State<Boolean> structureLayer =
            State.of(MapUiSettings.structureLayerEnabled());
    private final State<Boolean> labels = State.of(MapUiSettings.labels());
    private final State<Boolean> grid = State.of(MapUiSettings.grid());
    private final State<Boolean> coords = State.of(MapUiSettings.showCoords());
    private final State<Boolean> playerRing = State.of(MapUiSettings.playerRing());

    private final EnumMap<MapMarkerStore.StructurePreset, State<Boolean>>
            structureVisibility = new EnumMap<>(MapMarkerStore.StructurePreset.class);

    private final State<Float> playerSize =
            State.of((float) MapUiSettings.markerSize());
    private final State<String> playerSizeText =
            State.of(MapUiSettings.markerSize() + " px");
    private final State<Float> markerScale =
            State.of((float) MapUiSettings.markerScalePercent());
    private final State<String> markerScaleText =
            State.of(MapUiSettings.markerScalePercent() + "%");
    private final State<Float> requestBatch =
            State.of((float) MapUiSettings.requestBatchSize());
    private final State<String> requestBatchText =
            State.of(Integer.toString(MapUiSettings.requestBatchSize()));
    private final State<Float> scanInterval =
            State.of((float) MapUiSettings.liveScanIntervalTicks());
    private final State<String> scanIntervalText =
            State.of(MapUiSettings.liveScanIntervalTicks() + " tick");

    private Window layerWindow;
    private Window settingsWindow;
    private Window aboutWindow;
    private Window markerWindow;

    private boolean mouseCaptured;
    private int logicalWidth;
    private int logicalHeight;
    private double lastMouseX;
    private double lastMouseY;

    MapPaneUi(MapScreenV3 host) {
        this.host = host;
        buildTopbar();
        buildZoomHud();
        bindStates();
        for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
            State<Boolean> state = State.of(MapUiSettings.structureVisible(preset));
            state.onChange(value -> MapUiSettings.setStructureVisible(preset, value));
            structureVisibility.put(preset, state);
        }
    }

    private void buildTopbar() {
        topbar.align(Flex.Align.STRETCH);
        topbar.style(Style.empty()
                .bg(0xf410191e)
                .borderColor(0xff40505a)
                .borderWidth(1)
                .padding(0)
                .gap(0)
                .height(TOPBAR_H));

        Panel brand = Panel.row();
        brand.align(Flex.Align.CENTER);
        brand.style(Style.empty().padding(10).gap(8).width(300).height(TOPBAR_H));
        Label title = Label.of("地图册");
        title.style(Style.empty().fg(0xffffffff).width(74));
        brand.add(title);
        Label status = Label.of(statusText).dim(true);
        status.style(Style.empty().width(190));
        brand.add(status);
        topbar.add(brand);

        Panel spacer = Panel.row();
        spacer.grow(1);
        spacer.style(Style.empty().height(1));
        topbar.add(spacer);

        topbar.add(navButton(
                MapIconAtlas.UiIcon.MAP, "地图",
                this::closeAllWindows,
                () -> windows.isEmpty()));
        topbar.add(navButton(
                MapIconAtlas.UiIcon.MARKERS, "标点",
                this::toggleMarkers,
                () -> markerWindow != null && windows.contains(markerWindow)));
        topbar.add(navButton(
                MapIconAtlas.UiIcon.LAYERS, "图层",
                this::toggleLayers,
                () -> layerWindow != null && windows.contains(layerWindow)));
        topbar.add(navButton(
                MapIconAtlas.UiIcon.SETTINGS, "设置",
                this::toggleSettings,
                () -> settingsWindow != null && windows.contains(settingsWindow)));
        topbar.add(navButton(
                MapIconAtlas.UiIcon.ABOUT, "关于",
                this::toggleAbout,
                () -> aboutWindow != null && windows.contains(aboutWindow)));

        topbar.add(new MapPaneIconButton(
                MapIconAtlas.UiIcon.CLOSE, "",
                52, TOPBAR_H,
                host::paneCloseScreen,
                () -> false));
    }

    private void buildZoomHud() {
        zoomHud.align(Flex.Align.CENTER);
        zoomHud.style(Style.empty()
                .bg(0xee172128)
                .borderColor(0xff40505a)
                .borderWidth(1)
                .padding(4)
                .gap(3));

        Button minus = Button.of("−").onClick(() -> host.paneZoomStep(-1));
        minus.style(Style.empty().width(28).height(24));
        zoomHud.add(minus);

        Label zoom = Label.of(zoomText);
        zoom.style(Style.empty().width(68));
        zoomHud.add(zoom);

        Button plus = Button.of("+").onClick(() -> host.paneZoomStep(1));
        plus.style(Style.empty().width(28).height(24));
        zoomHud.add(plus);

        Button locate = Button.of("定位").onClick(host::paneLocatePlayer);
        locate.style(Style.empty().width(44).height(24));
        zoomHud.add(locate);
    }

    private Component navButton(
            MapIconAtlas.UiIcon icon, String text,
            Runnable action, java.util.function.BooleanSupplier active) {
        return new MapPaneIconButton(
                icon, text, 72, TOPBAR_H, action, active);
    }

    private void bindStates() {
        markerLayer.onChange(MapUiSettings::setMarkerLayerEnabled);
        structureLayer.onChange(MapUiSettings::setStructureLayerEnabled);
        labels.onChange(MapUiSettings::setLabels);
        grid.onChange(MapUiSettings::setGrid);
        coords.onChange(MapUiSettings::setShowCoords);
        playerRing.onChange(MapUiSettings::setPlayerRing);

        uiScaleLevel.onChange(value -> {
            int level = discrete(uiScaleLevel, value, 1, 20);
            MapUiSettings.setUiScaleLevel(level);
            uiScaleText.set(scaleLabel(level));
        });
        fontScaleLevel.onChange(value -> {
            int level = discrete(fontScaleLevel, value, 1, 20);
            MapUiSettings.setFontScaleLevel(level);
            fontScaleText.set(scaleLabel(level));
        });
        playerSize.onChange(value -> {
            int size = discrete(playerSize, value, 6, 48);
            MapUiSettings.setMarkerSize(size);
            playerSizeText.set(size + " px");
        });
        markerScale.onChange(value -> {
            int scale = discrete(markerScale, value, 0, 100);
            MapUiSettings.setMarkerScalePercent(scale);
            markerScaleText.set(scale + "%");
        });
        requestBatch.onChange(value -> {
            int batch = discrete(requestBatch, value, 8, 256);
            MapUiSettings.setRequestBatchSize(batch);
            requestBatchText.set(Integer.toString(batch));
        });
        scanInterval.onChange(value -> {
            int ticks = discrete(scanInterval, value, 5, 100);
            MapUiSettings.setLiveScanIntervalTicks(ticks);
            scanIntervalText.set(ticks + " tick");
            host.paneSetScanInterval(ticks);
        });
    }

    private static int discrete(State<Float> state, Float raw, int min, int max) {
        int value = Math.max(min, Math.min(max, Math.round(raw == null ? min : raw)));
        if (raw == null || Math.abs(raw - value) > 0.001f) {
            state.set((float) value);
        }
        return value;
    }

    private static String scaleLabel(int level) {
        double factor = 0.5 + (Math.max(1, Math.min(20, level)) - 1) * 0.1;
        return String.format(Locale.ROOT, "%d/20 · %.1f×", level, factor);
    }

    void render(
            GuiGraphics graphics, Font font,
            int screenWidth, int screenHeight,
            int mouseX, int mouseY) {
        double uiScale = MapUiSettings.uiScaleFactor();
        double fontScale = MapUiSettings.fontScaleFactor();
        MapPaneRenderer renderer =
                new MapPaneRenderer(graphics, font, uiScale, fontScale);
        logicalWidth = renderer.logicalWidth(screenWidth);
        logicalHeight = renderer.logicalHeight(screenHeight);
        lastMouseX = renderer.logicalX(mouseX);
        lastMouseY = renderer.logicalY(mouseY);

        zoomText.set(host.paneZoomLabel());
        statusText.set(host.paneStatusLabel());
        syncToggleStates();

        renderer.begin();
        try {
            topbar.layout(new Rect(0, 0, logicalWidth, TOPBAR_H), renderer);
            topbar.onMouse(UiEvent.Mouse.move(lastMouseX, lastMouseY));
            topbar.paint(renderer, THEME);

            Size zoomSize = zoomHud.measure(renderer);
            zoomHud.layout(new Rect(
                    Math.max(4, logicalWidth - zoomSize.w() - 8),
                    Math.max(TOPBAR_H + 4, logicalHeight - zoomSize.h() - 8),
                    zoomSize.w(), zoomSize.h()), renderer);
            zoomHud.onMouse(UiEvent.Mouse.move(lastMouseX, lastMouseY));
            zoomHud.paint(renderer, THEME);

            for (Window window : windows) {
                window.onMouse(UiEvent.Mouse.move(lastMouseX, lastMouseY));
                layoutWindow(window, renderer);
                window.paint(renderer, THEME);
            }
        } finally {
            renderer.end();
        }
    }

    private void layoutWindow(Window window, MapPaneRenderer renderer) {
        if (!window.consumeDirty() && window.bounds().w() > 0) return;
        Size size = window.measure(renderer);
        int h = Math.min(size.h(), Math.max(80, logicalHeight - 48));
        int x = Math.max(4, Math.min(window.posX(), Math.max(4, logicalWidth - size.w() - 4)));
        int y = Math.max(TOPBAR_H + 4, Math.min(window.posY(), Math.max(TOPBAR_H + 4, logicalHeight - h - 4)));
        window.layout(new Rect(x, y, size.w(), h), renderer);
    }

    private void syncToggleStates() {
        markerLayer.set(MapUiSettings.markerLayerEnabled());
        structureLayer.set(MapUiSettings.structureLayerEnabled());
        labels.set(MapUiSettings.labels());
        grid.set(MapUiSettings.grid());
        coords.set(MapUiSettings.showCoords());
        playerRing.set(MapUiSettings.playerRing());
        for (var entry : structureVisibility.entrySet()) {
            entry.getValue().set(MapUiSettings.structureVisible(entry.getKey()));
        }
    }

    boolean mouseClicked(double guiX, double guiY, int button) {
        double scale = MapPaneRenderer.currentRenderScale();
        double x = guiX / scale;
        double y = guiY / scale;
        UiEvent.Mouse event = UiEvent.Mouse.down(x, y, button);

        for (int i = windows.size() - 1; i >= 0; i--) {
            Window window = windows.get(i);
            if (window.onMouse(event)) {
                mouseCaptured = true;
                if (i != windows.size() - 1) {
                    windows.remove(i);
                    windows.add(window);
                }
                return true;
            }
        }
        if (topbar.onMouse(event)) {
            mouseCaptured = true;
            return true;
        }
        if (zoomHud.onMouse(event)) {
            mouseCaptured = true;
            return true;
        }

        // Empty top-bar pixels still belong to UI, never start map dragging.
        return topbar.bounds().contains(x, y);
    }

    boolean mouseDragged(double guiX, double guiY) {
        if (!mouseCaptured) return false;
        double scale = MapPaneRenderer.currentRenderScale();
        UiEvent.Mouse event = UiEvent.Mouse.move(guiX / scale, guiY / scale);
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).onMouse(event)) return true;
        }
        if (topbar.onMouse(event)) return true;
        return zoomHud.onMouse(event);
    }

    boolean mouseReleased(double guiX, double guiY, int button) {
        if (!mouseCaptured) return false;
        double scale = MapPaneRenderer.currentRenderScale();
        UiEvent.Mouse event = UiEvent.Mouse.up(guiX / scale, guiY / scale, button);
        for (int i = windows.size() - 1; i >= 0; i--) {
            windows.get(i).onMouse(event);
        }
        topbar.onMouse(event);
        zoomHud.onMouse(event);
        mouseCaptured = false;
        return true;
    }

    boolean mouseScrolled(double guiX, double guiY, double scrollY) {
        double scale = MapPaneRenderer.currentRenderScale();
        double x = guiX / scale;
        double y = guiY / scale;
        UiEvent.Mouse event = UiEvent.Mouse.scroll(x, y, scrollY);
        for (int i = windows.size() - 1; i >= 0; i--) {
            Window window = windows.get(i);
            if (window.bounds().contains(x, y) && window.onMouse(event)) return true;
        }
        return topbar.bounds().contains(x, y) || zoomHud.bounds().contains(x, y);
    }

    boolean owns(double guiX, double guiY) {
        double scale = MapPaneRenderer.currentRenderScale();
        double x = guiX / scale;
        double y = guiY / scale;
        if (topbar.bounds().contains(x, y) || zoomHud.bounds().contains(x, y)) return true;
        for (Window window : windows) {
            if (window.bounds().contains(x, y)) return true;
        }
        return false;
    }

    boolean hasBlockingWindow() {
        return settingsWindow != null && windows.contains(settingsWindow);
    }

    boolean escape() {
        if (windows.isEmpty()) return false;
        Window top = windows.remove(windows.size() - 1);
        if (top == layerWindow) layerWindow = null;
        if (top == settingsWindow) settingsWindow = null;
        if (top == aboutWindow) aboutWindow = null;
        if (top == markerWindow) markerWindow = null;
        mouseCaptured = false;
        return true;
    }

    void closeAllWindows() {
        windows.clear();
        layerWindow = null;
        settingsWindow = null;
        aboutWindow = null;
        markerWindow = null;
    }

    private void toggleLayers() {
        if (layerWindow != null && windows.remove(layerWindow)) {
            layerWindow = null;
            return;
        }
        layerWindow = buildLayerWindow();
        windows.add(layerWindow);
    }

    private Window buildLayerWindow() {
        Window window = Window.of("图层")
                .at(Math.max(8, logicalWidth - 300), TOPBAR_H + 8)
                .size(284, Math.min(540, Math.max(320, logicalHeight - 72)))
                .minSize(250, 160)
                .resizable(true)
                .scrollable(true)
                .closable(() -> {
                    windows.remove(layerWindow);
                    layerWindow = null;
                });

        window.add(Checkbox.of("自定义标点", markerLayer));
        window.add(Checkbox.of("自然结构", structureLayer));
        window.add(Checkbox.of("名称标签", labels));
        window.add(Checkbox.of("区块网格", grid));

        Label section = Label.of("结构显示").dim(true);
        section.style(Style.empty().height(18));
        window.add(section);

        ScrollContainer structures = ScrollContainer.of(320);
        for (MapMarkerStore.StructurePreset preset : MapMarkerStore.StructurePreset.values()) {
            structures.add(Checkbox.of(preset.label, structureVisibility.get(preset)));
        }
        window.add(structures);
        return window;
    }

    private void toggleSettings() {
        if (settingsWindow != null && windows.remove(settingsWindow)) {
            settingsWindow = null;
            return;
        }
        settingsWindow = buildSettingsWindow();
        windows.add(settingsWindow);
    }

    private Window buildSettingsWindow() {
        Window window = Window.of("设置")
                .at(Math.max(12, (logicalWidth - 650) / 2), TOPBAR_H + 10)
                .size(Math.min(650, Math.max(420, logicalWidth - 24)),
                        Math.min(590, Math.max(340, logicalHeight - 76)))
                .minSize(400, 220)
                .resizable(true)
                .scrollable(true)
                .closable(() -> {
                    windows.remove(settingsWindow);
                    settingsWindow = null;
                });

        Tabs tabs = Tabs.of();
        tabs.tab("通用", buildGeneralSettings());
        tabs.tab("显示", buildDisplaySettings());
        tabs.tab("性能", buildPerformanceSettings());
        tabs.tab("标点", buildMarkerSettings());
        window.add(tabs);
        return window;
    }

    private Component buildGeneralSettings() {
        Panel panel = Panel.column();
        panel.add(Label.of("界面与字体").dim(true));
        panel.add(sliderRow("控件大小", uiScaleLevel, 1, 20, uiScaleText));
        panel.add(sliderRow("字体大小", fontScaleLevel, 1, 20, fontScaleText));
        panel.add(Label.of("玩家图标").dim(true));
        panel.add(sliderRow("图标大小", playerSize, 6, 48, playerSizeText));
        return panel;
    }

    private Component buildDisplaySettings() {
        Panel panel = Panel.column();
        panel.add(Checkbox.of("显示坐标与生物群系", coords));
        panel.add(Checkbox.of("显示玩家位置环", playerRing));
        panel.add(Checkbox.of("自定义标点", markerLayer));
        panel.add(Checkbox.of("自动结构", structureLayer));
        panel.add(Checkbox.of("名称标签", labels));
        panel.add(Checkbox.of("区块网格", grid));
        panel.add(sliderRow("图标随地图缩放", markerScale, 0, 100, markerScaleText));
        return panel;
    }

    private Component buildPerformanceSettings() {
        Panel panel = Panel.column();
        panel.add(Label.of("地图底层缓存 / 扫描 / 渲染管线不由 Pane 接管。").dim(true));
        panel.add(sliderRow("每批请求区块数", requestBatch, 8, 256, requestBatchText));
        panel.add(sliderRow("实时扫描间隔", scanInterval, 5, 100, scanIntervalText));
        return panel;
    }

    private Component buildMarkerSettings() {
        Panel panel = Panel.column();
        panel.add(Label.of("标点和结构的图标编辑会继续迁到 Pane。").dim(true));
        panel.add(Button.of("打开标点管理").onClick(() -> {
            if (settingsWindow != null) {
                windows.remove(settingsWindow);
                settingsWindow = null;
            }
            toggleMarkers();
        }));
        return panel;
    }

    private Component sliderRow(
            String label, State<Float> state, float min, float max,
            State<String> valueLabel) {
        Panel row = Panel.row();
        row.align(Flex.Align.CENTER);
        row.style(Style.empty().gap(8).height(34));

        Label name = Label.of(label);
        name.style(Style.empty().width(150));
        row.add(name);

        Slider slider = Slider.of(state, min, max);
        slider.style(Style.empty().width(240));
        slider.grow(1);
        row.add(slider);

        Label value = Label.of(valueLabel).dim(true);
        value.style(Style.empty().width(92));
        row.add(value);
        return row;
    }

    private void toggleAbout() {
        if (aboutWindow != null && windows.remove(aboutWindow)) {
            aboutWindow = null;
            return;
        }
        aboutWindow = Window.of("关于")
                .at(Math.max(10, (logicalWidth - 460) / 2), TOPBAR_H + 12)
                .size(460, 250)
                .resizable(false)
                .scrollable(false)
                .closable(() -> {
                    windows.remove(aboutWindow);
                    aboutWindow = null;
                });
        aboutWindow.add(Label.of("地图册 · Map Atlas"));
        aboutWindow.add(Label.of("Cartograph V3.1 · Pane UI").dim(true));
        aboutWindow.add(Label.of("地图事实：仅使用真实已生成区块"));
        aboutWindow.add(Label.of("地图渲染：继续使用 V3 缓存与卫星图管线"));
        aboutWindow.add(Label.of("UI：Pane 0.4.0，Minecraft 原生渲染").dim(true));
        windows.add(aboutWindow);
    }

    private void toggleMarkers() {
        if (markerWindow != null && windows.remove(markerWindow)) {
            markerWindow = null;
            return;
        }
        markerWindow = buildMarkerWindow();
        windows.add(markerWindow);
    }

    private Window buildMarkerWindow() {
        Window window = Window.of("自定义标点")
                .at(Math.max(8, logicalWidth - 350), TOPBAR_H + 8)
                .size(334, Math.min(520, Math.max(300, logicalHeight - 72)))
                .minSize(280, 180)
                .resizable(true)
                .scrollable(true)
                .closable(() -> {
                    windows.remove(markerWindow);
                    markerWindow = null;
                });

        window.add(Button.of("添加标点").onClick(() -> {
            host.paneCreateMarkerAtCenter();
            windows.remove(markerWindow);
            markerWindow = null;
        }));

        ScrollContainer list = ScrollContainer.of(360);
        List<MapMarkerStore.Marker> markers = host.paneVisibleMarkers();
        int count = 0;
        for (MapMarkerStore.Marker marker : markers) {
            if (marker.origin == MapMarkerStore.Origin.AUTO && !marker.modifiedAuto) continue;
            count++;
            String text = marker.name + "   X " + marker.x + "  Y " + marker.y + "  Z " + marker.z;
            list.add(Button.of(text).onClick(() -> {
                host.paneEditMarker(marker);
                windows.remove(markerWindow);
                markerWindow = null;
            }));
        }
        if (count == 0) list.add(Label.of("暂无自定义标点").dim(true));
        window.add(list);
        return window;
    }
}
