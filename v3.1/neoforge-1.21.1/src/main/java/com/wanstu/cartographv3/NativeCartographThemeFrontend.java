package com.wanstu.cartographv3;

import net.ethrocky.pane.core.Component;
import net.ethrocky.pane.core.Modals;
import net.ethrocky.pane.core.PaneClipboard;
import net.ethrocky.pane.core.Rect;
import net.ethrocky.pane.core.Size;
import net.ethrocky.pane.core.State;
import net.ethrocky.pane.core.layout.Flex;
import net.ethrocky.pane.core.style.Style;
import net.ethrocky.pane.core.style.Theme;
import net.ethrocky.pane.widget.Button;
import net.ethrocky.pane.widget.Checkbox;
import net.ethrocky.pane.widget.ColorPicker;
import net.ethrocky.pane.widget.Dialog;
import net.ethrocky.pane.widget.Label;
import net.ethrocky.pane.widget.NumberField;
import net.ethrocky.pane.widget.Panel;
import net.ethrocky.pane.widget.ScrollContainer;
import net.ethrocky.pane.widget.Slider;
import net.ethrocky.pane.widget.Tabs;
import net.ethrocky.pane.widget.TextArea;
import net.ethrocky.pane.widget.TextInput;
import net.ethrocky.pane.widget.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * First complete V3.1 frontend theme.
 *
 * "Native" means modern/satellite Cartograph presentation, not the old
 * MapScreenV3 implementation. Every visible large-map element lives in this
 * Pane component tree.
 */
final class NativeCartographThemeFrontend implements CartographThemeFrontend {
    private static final Theme THEME = new Theme(
            0xff10191e,
            0xff172128,
            0xfff2f5f7,
            0xffaab5bc,
            0xfff3c94f,
            0xff46555f,
            8,
            6);

    private final CartographBackendApi backend;
    private final NativeThemeRoot root;

    NativeCartographThemeFrontend(
            CartographBackendApi backend,
            Runnable closeScreen) {
        this.backend = backend;
        this.root = new NativeThemeRoot(backend, closeScreen);
    }

    @Override
    public String id() {
        return "native";
    }

    @Override
    public String displayName() {
        return "原生";
    }

    @Override
    public Component root() {
        return root;
    }

    @Override
    public Theme paneTheme() {
        return THEME;
    }

    @Override
    public void tick() {
        root.tick();
    }

    @Override
    public void close() {
        root.close();
    }

    private static final class NativeThemeRoot
            extends Component implements NativeMapCanvas.Listener, AutoCloseable {
        private static final int TOPBAR_H = 62;

        private final CartographBackendApi backend;
        private final Runnable closeScreen;
        private final NativeMapCanvas canvas;

        private final Panel topbar = Panel.row();
        private final Panel zoomHud = Panel.row();
        private final State<String> status =
                State.of("原生 · 卫星");
        private final State<String> zoomText =
                State.of("200%");

        private Window activeWindow;
        private Window layerWindow;
        private Window markerWindow;
        private Window settingsWindow;
        private Window aboutWindow;
        private Component contextMenu;
        private int contextX;
        private int contextY;

        NativeThemeRoot(
                CartographBackendApi backend,
                Runnable closeScreen) {
            this.backend = backend;
            this.closeScreen = closeScreen;

            canvas = new NativeMapCanvas(
                    backend,
                    SatelliteMapStyleRenderer.INSTANCE,
                    this);

            buildTopbar();
            buildZoomHud();

            add(canvas);
            add(topbar);
            add(zoomHud);
        }

        void tick() {
            zoomText.set(canvas.zoomLabel());
            int structures = 0;
            int custom = 0;
            for (MapMarkerStore.Marker marker : backend.markers()) {
                if (marker.origin == MapMarkerStore.Origin.AUTO
                        && !marker.modifiedAuto) {
                    structures++;
                } else {
                    custom++;
                }
            }
            status.set(
                    "原生 · 卫星 · 结构 " + structures
                            + " · 标点 " + custom
                            + " · 缓存 " + backend.cachedCount());
        }

        private void buildTopbar() {
            topbar.align(Flex.Align.STRETCH);
            topbar.style(Style.empty()
                    .bg(0xf410191e)
                    .borderColor(0xff43515a)
                    .borderWidth(1)
                    .padding(0)
                    .gap(0)
                    .height(TOPBAR_H));

            Panel brand = Panel.row();
            brand.align(Flex.Align.CENTER);
            brand.style(Style.empty()
                    .padding(12)
                    .gap(10)
                    .width(370)
                    .height(TOPBAR_H));

            Label title = Label.of("地图册");
            title.style(Style.empty().fg(0xffffffff).width(70));
            brand.add(title);

            Label state = Label.of(status).dim(true);
            state.style(Style.empty().width(280));
            brand.add(state);
            topbar.add(brand);

            Panel spacer = Panel.row();
            spacer.grow(1);
            spacer.style(Style.empty().height(1));
            topbar.add(spacer);

            topbar.add(nav(
                    MapIconAtlas.UiIcon.MAP,
                    "地图",
                    this::closeWindow,
                    () -> activeWindow == null));
            topbar.add(nav(
                    MapIconAtlas.UiIcon.MARKERS,
                    "标点",
                    this::toggleMarkers,
                    () -> activeWindow == markerWindow && markerWindow != null));
            topbar.add(nav(
                    MapIconAtlas.UiIcon.LAYERS,
                    "图层",
                    this::toggleLayers,
                    () -> activeWindow == layerWindow && layerWindow != null));
            topbar.add(nav(
                    MapIconAtlas.UiIcon.SETTINGS,
                    "设置",
                    this::toggleSettings,
                    () -> activeWindow == settingsWindow && settingsWindow != null));
            topbar.add(nav(
                    MapIconAtlas.UiIcon.ABOUT,
                    "关于",
                    this::toggleAbout,
                    () -> activeWindow == aboutWindow && aboutWindow != null));

            topbar.add(new MapPaneIconButton(
                    MapIconAtlas.UiIcon.CLOSE,
                    "",
                    52,
                    TOPBAR_H,
                    closeScreen,
                    () -> false));
        }

        private Component nav(
                MapIconAtlas.UiIcon icon,
                String label,
                Runnable action,
                java.util.function.BooleanSupplier active) {
            return new MapPaneIconButton(
                    icon, label, 88, TOPBAR_H, action, active);
        }

        private void buildZoomHud() {
            zoomHud.align(Flex.Align.CENTER);
            zoomHud.style(Style.empty()
                    .bg(0xee182228)
                    .borderColor(0xff53616a)
                    .borderWidth(1)
                    .padding(0)
                    .gap(0));

            Button minus = Button.of("−").onClick(() -> canvas.zoomStep(-1));
            minus.style(Style.empty().width(44).height(44));
            zoomHud.add(minus);

            Label zoom = Label.of(zoomText);
            zoom.style(Style.empty().width(78).height(44));
            zoomHud.add(zoom);

            Button plus = Button.of("+").onClick(() -> canvas.zoomStep(1));
            plus.style(Style.empty().width(44).height(44));
            zoomHud.add(plus);

            Button locate = Button.of("定位").onClick(canvas::centerOnPlayer);
            locate.style(Style.empty().width(54).height(44));
            zoomHud.add(locate);
        }

        @Override
        public Size measure(TextMeasurer tm) {
            return new Size(1, 1);
        }

        @Override
        public void layout(Rect next, TextMeasurer tm) {
            bounds = next;

            Rect mapRect = new Rect(
                    0,
                    TOPBAR_H,
                    next.w(),
                    Math.max(1, next.h() - TOPBAR_H));
            canvas.layout(mapRect, tm);
            topbar.layout(new Rect(0, 0, next.w(), TOPBAR_H), tm);

            Size zoomSize = zoomHud.measure(tm);
            zoomHud.layout(
                    new Rect(
                            Math.max(8, next.w() - zoomSize.w() - 18),
                            Math.max(
                                    TOPBAR_H + 8,
                                    next.h() - zoomSize.h() - 20),
                            zoomSize.w(),
                            zoomSize.h()),
                    tm);

            if (activeWindow != null) {
                Size natural = activeWindow.measure(tm);
                boolean settings = activeWindow == settingsWindow;
                int w = settings
                        ? Math.min(980, Math.max(560, next.w() - 80))
                        : Math.min(360, Math.max(280, natural.w()));
                int h = settings
                        ? Math.min(680, Math.max(380, next.h() - TOPBAR_H - 40))
                        : Math.min(
                                Math.max(280, natural.h()),
                                Math.max(280, next.h() - TOPBAR_H - 36));
                int x = settings
                        ? Math.max(20, (next.w() - w) / 2)
                        : 16;
                int y = TOPBAR_H + 18;
                activeWindow.layout(new Rect(x, y, w, h), tm);
            }

            if (contextMenu != null) {
                Size size = contextMenu.measure(tm);
                int x = Math.max(
                        4,
                        Math.min(
                                contextX,
                                Math.max(4, next.w() - size.w() - 4)));
                int y = Math.max(
                        TOPBAR_H + 4,
                        Math.min(
                                contextY,
                                Math.max(
                                        TOPBAR_H + 4,
                                        next.h() - size.h() - 4)));
                contextMenu.layout(
                        new Rect(x, y, Math.max(218, size.w()), size.h()),
                        tm);
            }
        }

        @Override
        public boolean onMouse(net.ethrocky.pane.core.UiEvent.Mouse event) {
            if (event.kind() == net.ethrocky.pane.core.UiEvent.Kind.DOWN
                    && event.button() == 0
                    && contextMenu != null
                    && !contextMenu.bounds().contains(event.x(), event.y())) {
                closeContext();
            }
            return super.onMouse(event);
        }

        @Override
        public void openContext(
                NativeMapCanvas.ContextTarget target,
                int screenX,
                int screenY) {
            closeContext();
            contextX = screenX;
            contextY = screenY;
            contextMenu = buildContextMenu(target);
            add(contextMenu);
        }

        private Component buildContextMenu(
                NativeMapCanvas.ContextTarget target) {
            Panel menu = Panel.column();
            menu.align(Flex.Align.STRETCH);
            menu.style(Style.empty()
                    .bg(0xf8182228)
                    .borderColor(0xff53616a)
                    .borderWidth(1)
                    .padding(6)
                    .gap(2)
                    .width(218));

            if (target.marker() == null) {
                menu.add(contextButton(
                        "添加标点…",
                        () -> openMarkerEditor(null, target)));
                menu.add(contextButton(
                        "传送至此",
                        () -> backend.teleport(target.x(), target.z())));
                menu.add(contextButton(
                        "复制坐标",
                        () -> PaneClipboard.set(
                                target.x() + ", "
                                        + target.y() + ", "
                                        + target.z())));
            } else {
                MapMarkerStore.Marker marker = target.marker();
                menu.add(contextButton(
                        "传送",
                        () -> backend.teleport(marker.x, marker.z)));
                menu.add(contextButton(
                        "编辑",
                        () -> openMarkerEditor(marker, target)));
                if (marker.origin == MapMarkerStore.Origin.AUTO
                        && marker.modifiedAuto) {
                    menu.add(contextButton(
                            "重置为自动",
                            () -> backend.resetAutomaticMarker(marker.uuid())));
                } else {
                    menu.add(contextButton(
                            "删除",
                            () -> backend.deleteMarker(marker.uuid())));
                }
            }

            Label coords = Label.of(
                    "X " + target.x()
                            + "  Y " + target.y()
                            + "  Z " + target.z()).dim(true);
            coords.style(Style.empty().height(22));
            menu.add(coords);
            return menu;
        }

        private Button contextButton(String label, Runnable action) {
            Button button = Button.of(label).onClick(() -> {
                closeContext();
                action.run();
            });
            button.style(Style.empty().height(36));
            return button;
        }

        private void closeContext() {
            if (contextMenu == null) return;
            children.remove(contextMenu);
            contextMenu = null;
            invalidate();
        }

        private void toggleLayers() {
            if (activeWindow == layerWindow && layerWindow != null) {
                closeWindow();
                return;
            }
            layerWindow = buildLayerWindow();
            showWindow(layerWindow);
        }

        private Window buildLayerWindow() {
            Window window = Window.of("图层")
                    .size(320, 540)
                    .minSize(280, 220)
                    .resizable(true)
                    .scrollable(true)
                    .closable(this::closeWindow);

            State<Boolean> custom =
                    State.of(MapUiSettings.markerLayerEnabled());
            custom.onChange(MapUiSettings::setMarkerLayerEnabled);
            State<Boolean> structures =
                    State.of(MapUiSettings.structureLayerEnabled());
            structures.onChange(MapUiSettings::setStructureLayerEnabled);
            State<Boolean> labels =
                    State.of(MapUiSettings.labels());
            labels.onChange(MapUiSettings::setLabels);
            State<Boolean> grid =
                    State.of(MapUiSettings.grid());
            grid.onChange(MapUiSettings::setGrid);

            window.add(Checkbox.of("自定义标点", custom));
            window.add(Checkbox.of("自然结构", structures));
            window.add(Checkbox.of("名称标签", labels));
            window.add(Checkbox.of("区块网格", grid));

            Label structureTitle = Label.of("结构显示").dim(true);
            structureTitle.style(Style.empty().height(20));
            window.add(structureTitle);

            ScrollContainer list = ScrollContainer.of(340);
            for (MapMarkerStore.StructurePreset preset
                    : MapMarkerStore.StructurePreset.values()) {
                State<Boolean> state =
                        State.of(MapUiSettings.structureVisible(preset));
                state.onChange(value ->
                        MapUiSettings.setStructureVisible(preset, value));
                list.add(Checkbox.of(preset.label, state));
            }
            window.add(list);
            return window;
        }

        private void toggleMarkers() {
            if (activeWindow == markerWindow && markerWindow != null) {
                closeWindow();
                return;
            }
            markerWindow = buildMarkerWindow();
            showWindow(markerWindow);
        }

        private Window buildMarkerWindow() {
            Window window = Window.of("自定义标点")
                    .size(350, 520)
                    .minSize(300, 220)
                    .resizable(true)
                    .scrollable(true)
                    .closable(this::closeWindow);

            window.add(Button.of("添加标点").onClick(() -> {
                int x = (int) Math.floor(canvas.centerX());
                int z = (int) Math.floor(canvas.centerZ());
                openMarkerEditor(
                        null,
                        new NativeMapCanvas.ContextTarget(
                                x, backend.surfaceYAt(x, z), z, null));
            }));

            ScrollContainer list = ScrollContainer.of(390);
            List<MapMarkerStore.Marker> markers = new ArrayList<>();
            for (MapMarkerStore.Marker marker : backend.markers()) {
                if (marker.origin == MapMarkerStore.Origin.CUSTOM
                        || marker.modifiedAuto) {
                    markers.add(marker);
                }
            }
            markers.sort(Comparator.comparing(
                    marker -> marker.name,
                    String.CASE_INSENSITIVE_ORDER));

            if (markers.isEmpty()) {
                list.add(Label.of("暂无自定义标点").dim(true));
            } else {
                for (MapMarkerStore.Marker marker : markers) {
                    String text = marker.name
                            + "   X " + marker.x
                            + " Y " + marker.y
                            + " Z " + marker.z;
                    list.add(Button.of(text).onClick(
                            () -> openMarkerEditor(
                                    marker,
                                    new NativeMapCanvas.ContextTarget(
                                            marker.x, marker.y, marker.z, marker))));
                }
            }
            window.add(list);
            return window;
        }

        private void toggleSettings() {
            if (activeWindow == settingsWindow && settingsWindow != null) {
                closeWindow();
                return;
            }
            settingsWindow = buildSettingsWindow();
            showWindow(settingsWindow);
        }

        private Window buildSettingsWindow() {
            Window window = Window.of("设置 · 原生主题")
                    .size(900, 620)
                    .minSize(560, 360)
                    .resizable(true)
                    .scrollable(true)
                    .closable(this::closeWindow);

            Tabs tabs = Tabs.of();
            tabs.tab("界面", buildUiSettings());
            tabs.tab("地图", buildMapSettings());
            tabs.tab("性能", buildPerformanceSettings());
            window.add(tabs);
            return window;
        }

        private Component buildUiSettings() {
            Panel panel = Panel.column();
            panel.add(Label.of("主题：原生 / Native").dim(true));

            State<Float> uiScale =
                    State.of((float) MapUiSettings.uiScaleLevel());
            State<String> uiValue =
                    State.of(scaleLabel(MapUiSettings.uiScaleLevel()));
            uiScale.onChange(raw -> {
                int value = discrete(uiScale, raw, 1, 20);
                MapUiSettings.setUiScaleLevel(value);
                uiValue.set(scaleLabel(value));
            });
            panel.add(sliderRow(
                    "控件大小",
                    uiScale, 1, 20, uiValue));

            State<Float> fontScale =
                    State.of((float) MapUiSettings.fontScaleLevel());
            State<String> fontValue =
                    State.of(scaleLabel(MapUiSettings.fontScaleLevel()));
            fontScale.onChange(raw -> {
                int value = discrete(fontScale, raw, 1, 20);
                MapUiSettings.setFontScaleLevel(value);
                fontValue.set(scaleLabel(value));
            });
            panel.add(sliderRow(
                    "字体大小",
                    fontScale, 1, 20, fontValue));

            State<Boolean> coords =
                    State.of(MapUiSettings.showCoords());
            coords.onChange(MapUiSettings::setShowCoords);
            panel.add(Checkbox.of("显示坐标与群系", coords));

            State<Boolean> ring =
                    State.of(MapUiSettings.playerRing());
            ring.onChange(MapUiSettings::setPlayerRing);
            panel.add(Checkbox.of("玩家位置环", ring));
            return panel;
        }

        private Component buildMapSettings() {
            Panel panel = Panel.column();
            panel.add(Label.of(
                    "地图渲染器：SatelliteMapStyleRenderer").dim(true));
            panel.add(Label.of(
                    "V3.1 后端只保存世界事实，主题切换不重新扫描。").dim(true));

            State<Float> markerSize =
                    State.of((float) MapUiSettings.markerSize());
            State<String> markerSizeValue =
                    State.of(MapUiSettings.markerSize() + " px");
            markerSize.onChange(raw -> {
                int value = discrete(markerSize, raw, 6, 48);
                MapUiSettings.setMarkerSize(value);
                markerSizeValue.set(value + " px");
            });
            panel.add(sliderRow(
                    "玩家图标大小",
                    markerSize, 6, 48, markerSizeValue));

            State<Float> markerScale =
                    State.of((float) MapUiSettings.markerScalePercent());
            State<String> markerScaleValue =
                    State.of(MapUiSettings.markerScalePercent() + "%");
            markerScale.onChange(raw -> {
                int value = discrete(markerScale, raw, 0, 100);
                MapUiSettings.setMarkerScalePercent(value);
                markerScaleValue.set(value + "%");
            });
            panel.add(sliderRow(
                    "图标随地图缩放",
                    markerScale, 0, 100, markerScaleValue));
            return panel;
        }

        private Component buildPerformanceSettings() {
            Panel panel = Panel.column();
            panel.add(Label.of(
                    "这些参数属于后端请求策略，不属于主题地图渲染。").dim(true));

            State<Float> batch =
                    State.of((float) MapUiSettings.requestBatchSize());
            State<String> batchValue =
                    State.of(Integer.toString(MapUiSettings.requestBatchSize()));
            batch.onChange(raw -> {
                int value = discrete(batch, raw, 8, 256);
                MapUiSettings.setRequestBatchSize(value);
                batchValue.set(Integer.toString(value));
            });
            panel.add(sliderRow(
                    "每批请求区块数",
                    batch, 8, 256, batchValue));

            State<Float> scan =
                    State.of((float) MapUiSettings.liveScanIntervalTicks());
            State<String> scanValue =
                    State.of(MapUiSettings.liveScanIntervalTicks() + " tick");
            scan.onChange(raw -> {
                int value = discrete(scan, raw, 5, 100);
                backend.setLiveScanInterval(value);
                scanValue.set(value + " tick");
            });
            panel.add(sliderRow(
                    "实时扫描间隔",
                    scan, 5, 100, scanValue));
            return panel;
        }

        private Component sliderRow(
                String label,
                State<Float> state,
                float min,
                float max,
                State<String> value) {
            Panel row = Panel.row();
            row.align(Flex.Align.CENTER);
            row.style(Style.empty().gap(10).height(38));

            Label name = Label.of(label);
            name.style(Style.empty().width(160));
            row.add(name);

            Slider slider = Slider.of(state, min, max);
            slider.style(Style.empty().width(280));
            slider.grow(1);
            row.add(slider);

            Label valueLabel = Label.of(value).dim(true);
            valueLabel.style(Style.empty().width(100));
            row.add(valueLabel);
            return row;
        }

        private void toggleAbout() {
            if (activeWindow == aboutWindow && aboutWindow != null) {
                closeWindow();
                return;
            }
            aboutWindow = Window.of("关于")
                    .size(500, 250)
                    .resizable(false)
                    .scrollable(false)
                    .closable(this::closeWindow);
            aboutWindow.add(Label.of("地图册 · Cartograph V3.1"));
            aboutWindow.add(Label.of(
                    "主题：原生 / Native").dim(true));
            aboutWindow.add(Label.of(
                    "前端：Pane 完整大地图主题"));
            aboutWindow.add(Label.of(
                    "后端：世界事实 / 缓存 / 请求 API"));
            aboutWindow.add(Label.of(
                    "地图：SatelliteMapStyleRenderer").dim(true));
            showWindow(aboutWindow);
        }

        private void showWindow(Window window) {
            closeContext();
            if (activeWindow != null) {
                children.remove(activeWindow);
            }
            activeWindow = window;
            if (activeWindow != null) {
                add(activeWindow);
            }
            invalidate();
        }

        private void closeWindow() {
            if (activeWindow != null) {
                children.remove(activeWindow);
                activeWindow = null;
                invalidate();
            }
        }

        private void openMarkerEditor(
                MapMarkerStore.Marker marker,
                NativeMapCanvas.ContextTarget target) {
            closeContext();

            boolean editing = marker != null;
            State<String> name = State.of(
                    editing ? marker.name : "新标点");
            State<Float> x = State.of(
                    (float) (editing ? marker.x : target.x()));
            State<Float> y = State.of(
                    (float) (editing ? marker.y : target.y()));
            State<Float> z = State.of(
                    (float) (editing ? marker.z : target.z()));
            State<String> description = State.of(
                    editing && marker.description != null
                            ? marker.description
                            : "");

            MapMarkerStore.Icon initialIcon =
                    editing && marker.icon != null
                            ? marker.icon
                            : MapMarkerStore.Icon.PIN;
            State<String> iconName = State.of(initialIcon.name());

            State<Integer> primary = State.of(
                    editing ? marker.primary() : 0xffffff);
            State<Integer> accent = State.of(
                    editing ? marker.accent() : 0xffd34e);
            State<Integer> line = State.of(
                    editing ? marker.line() : 0x202020);

            Panel body = Panel.column();
            body.style(Style.empty().width(560).gap(8));

            body.add(formRow(
                    "名称",
                    TextInput.of(name)));
            body.add(formRow(
                    "X",
                    NumberField.unbounded(x)));
            body.add(formRow(
                    "Y",
                    NumberField.unbounded(y)));
            body.add(formRow(
                    "Z",
                    NumberField.unbounded(z)));

            Button iconCycle = Button.of(
                    "图标：" + iconLabel(initialIcon));
            final MapMarkerStore.Icon[] currentIcon =
                    {initialIcon};
            iconCycle.onClick(() -> {
                MapMarkerStore.Icon[] values =
                        MapMarkerStore.Icon.values();
                int next =
                        (currentIcon[0].ordinal() + 1) % values.length;
                currentIcon[0] = values[next];
                iconName.set(currentIcon[0].name());
            });
            body.add(formRow("图标", iconCycle));

            body.add(Label.of("主色").dim(true));
            body.add(ColorPicker.of(primary));
            body.add(Label.of("强调色").dim(true));
            body.add(ColorPicker.of(accent));
            body.add(Label.of("线条色").dim(true));
            body.add(ColorPicker.of(line));

            TextArea desc = TextArea.of(description).rows(4);
            body.add(formRow("简介", desc));

            Dialog dialog = Dialog.of(
                    editing ? "编辑标点" : "添加标点")
                    .content(body)
                    .button("取消", Modals::close)
                    .button("保存", () -> {
                        int nextX = Math.round(x.get());
                        int nextY = Math.round(y.get());
                        int nextZ = Math.round(z.get());
                        MapMarkerStore.Icon nextIcon;
                        try {
                            nextIcon = MapMarkerStore.Icon.valueOf(iconName.get());
                        } catch (RuntimeException invalid) {
                            nextIcon = MapMarkerStore.Icon.PIN;
                        }

                        if (editing) {
                            backend.updateMarker(
                                    marker.uuid(),
                                    nextX, nextY, nextZ,
                                    name.get(), nextIcon,
                                    primary.get(), accent.get(), line.get(),
                                    description.get());
                        } else {
                            backend.createMarker(
                                    nextX, nextY, nextZ,
                                    name.get(), nextIcon,
                                    primary.get(), accent.get(), line.get(),
                                    description.get());
                        }
                        Modals.close();
                        if (activeWindow == markerWindow) {
                            markerWindow = buildMarkerWindow();
                            showWindow(markerWindow);
                        }
                    });
            dialog.open();
        }

        private Component formRow(
                String label,
                Component control) {
            Panel row = Panel.row();
            row.align(Flex.Align.CENTER);
            row.style(Style.empty().gap(10).height(40));
            Label name = Label.of(label);
            name.style(Style.empty().width(80));
            row.add(name);
            control.grow(1);
            row.add(control);
            return row;
        }

        private static String iconLabel(MapMarkerStore.Icon icon) {
            return icon == null ? "标点" : icon.label;
        }

        private static int discrete(
                State<Float> state,
                Float raw,
                int min,
                int max) {
            int value = Math.max(
                    min,
                    Math.min(
                            max,
                            Math.round(raw == null ? min : raw)));
            if (raw == null || Math.abs(raw - value) > 0.001f) {
                state.set((float) value);
            }
            return value;
        }

        private static String scaleLabel(int level) {
            double factor =
                    0.5 + (Math.max(1, Math.min(20, level)) - 1) * 0.1;
            return String.format(
                    Locale.ROOT,
                    "%d/20 · %.1f×",
                    level,
                    factor);
        }

        @Override
        public void close() {
            Modals.close();
            closeContext();
            canvas.close();
        }
    }
}
