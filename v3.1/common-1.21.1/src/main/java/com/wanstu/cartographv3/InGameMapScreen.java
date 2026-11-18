package com.wanstu.cartographv3;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import static com.wanstu.cartographv3.InGameMapPreferences.Flag;
import static com.wanstu.cartographv3.InGameMapPreferences.FONT_SCALE;
import static com.wanstu.cartographv3.InGameMapPreferences.UI_SCALE;
import static com.wanstu.cartographv3.InGameMapUi.*;

final class InGameMapScreen extends Screen {
    private static final int HEADER = 31;
    private static final int ROW = 19;
    private static final int GRID_LINE = 0x610b161b;
    static final int DEFAULT_PLAYER_SIZE = 12;
    static final int DEFAULT_MARKER_SIZE = 11;

    private enum Panel { NONE, MARKERS, LAYERS }
    private enum Nav {
        MAP("地图", "map"), MARKERS("标点", "pin"), LAYERS("图层", "layers"),
        SETTINGS("设置", "settings"), ABOUT("关于", "info");
        final String label;
        final String icon;
        Nav(String label, String icon) { this.label = label; this.icon = icon; }
    }

    record Point(int x, int y, int z, String biome) {}
    private record Menu(int left, int top, Point point, MapMarkerStore.Marker marker) {}
    private record MapLabel(MapMarkerStore.Marker marker, String text, int width) {}
    private record Pin(MapLabel label, Box glyph, Box name, boolean nameVisible) {}
    private record Layer(String id, String name, MapMarkerStore.Icon icon,
                         MapMarkerStore.StructurePreset preset, int count) {}
    private record Row(String label, String icon, boolean checked, int count, boolean child, Runnable run) {}

    final CartographBackendApi backend;
    final InGameMapPreferences preferences;
    private final InGameMapCamera camera = new InGameMapCamera();
    private final InGameMapTiles tiles;
    private final InGameMapDialogs dialogs;
    private final Consumer<CartographBackendApi.Viewport> viewport;
    private final Consumer<InGameMapCamera.Position> onRemoved;
    private final InGameMapCamera.Position savedPosition;
    private final List<Pin> pins = new ArrayList<>();
    private List<MapLabel> markerLabels = List.of();
    private List<MapMarkerStore.Marker> customMarkers = List.of();
    private List<Layer> structureLayers = List.of();
    private InGameMapUi ui;
    private int layoutWidth;
    private int layoutHeight;
    private double pixelsPerUnit;
    private float fontScale = 1;
    private double guiUnitsPerLayoutUnit;
    private double mouseXFactor;
    private double mouseYFactor;
    private Panel panel = Panel.NONE;
    private Menu menu;
    private Box sidebar;
    private Point cursor;
    private boolean structuresExpanded = true;
    private boolean initialized;
    private boolean removed;
    private boolean dragging;
    private int panelScroll;
    private int ticks;
    private int viewportDelay;
    private int hoveredX;
    private int hoveredZ;
    private boolean hoverPending;
    private String toast = "";
    private int toastTicks;
    private String lastClick = "";
    private long lastClickAt;

    InGameMapScreen(CartographBackendApi backend, InGameMapPreferences preferences, InGameMapTiles tiles,
                    InGameMapCamera.Position savedPosition,
                    Consumer<CartographBackendApi.Viewport> viewport,
                    Consumer<InGameMapCamera.Position> onRemoved, Runnable openWeb) {
        super(Component.literal("地图册"));
        this.backend = backend;
        this.preferences = preferences;
        this.savedPosition = savedPosition;
        this.viewport = viewport;
        this.onRemoved = onRemoved;
        this.tiles = tiles;
        this.dialogs = new InGameMapDialogs(this, openWeb);
    }

    @Override
    protected void init() {
        updateLayout();
        if (!initialized) {
            initialized = true;
            if (savedPosition != null && savedPosition.dimension().equals(backend.dimension())) {
                camera.restore(savedPosition);
            } else {
                centerPlayer();
            }
            refreshMarkers();
        }
        dialogs.resize();
        viewportDelay = 1;
    }

    private void updateLayout() {
        var window = minecraft.getWindow();
        int frameWidth = window.getWidth();
        int frameHeight = window.getHeight();
        int base = frameWidth >= 640 && frameHeight >= 480 ? 2 : 1;
        double preferred = base * preferences.scalePercent(UI_SCALE) / 100.0;
        double fitsWindow = Math.min(frameWidth / 230.0, frameHeight / 160.0);
        pixelsPerUnit = Math.min(preferred, Math.max(base, fitsWindow));
        fontScale = preferences.scalePercent(FONT_SCALE) / 100f;
        layoutWidth = (int) Math.ceil(frameWidth / pixelsPerUnit);
        layoutHeight = (int) Math.ceil(frameHeight / pixelsPerUnit);
        guiUnitsPerLayoutUnit = pixelsPerUnit / window.getGuiScale();
        mouseXFactor = frameWidth / (width * pixelsPerUnit);
        mouseYFactor = frameHeight / (height * pixelsPerUnit);
        camera.resize(layoutWidth, layoutHeight, HEADER, pixelsPerUnit);
    }

    void displayScale(String key, int percent) {
        if (preferences.scalePercent(key) == percent) return;
        preferences.number(key, percent);
        updateLayout();
        ui = null;
        if (key.equals(FONT_SCALE)) refreshMarkers();
        else viewportDelay = 1;
    }

    int layoutWidth() { return layoutWidth; }
    int layoutHeight() { return layoutHeight; }
    double pixelsPerUnit() { return pixelsPerUnit; }
    float fontScale() { return fontScale; }

    @Override
    public void tick() {
        ticks++;
        if (toastTicks > 0) toastTicks--;
        if (!dragging && viewportDelay > 0 && --viewportDelay == 0) submitViewport();
        tiles.update(camera);
        if (ticks % 5 == 0) refreshMarkers();
        if (hoverPending && !dragging && !dialogs.open()) {
            hoverPending = false;
            if (cursor == null || cursor.x != hoveredX || cursor.z != hoveredZ || ticks % 10 == 0) {
                cursor = point(hoveredX, hoveredZ);
            }
        }
    }

    private void submitViewport() {
        viewport.accept(camera.viewport(dragging));
    }

    private void centerPlayer() {
        var player = backend.player();
        if (player.present()) camera.center(player.x(), player.z());
        viewportDelay = 1;
    }

    private void zoom(double amount, double x, double y) {
        camera.zoom(amount, x, y);
        viewportDelay = 2;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The full-screen map canvas provides the backdrop underneath the input fields.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw the entire map in its own layout, including the vanilla text fields.
        var pose = graphics.pose();
        pose.pushPose();
        try {
            float factor = (float) guiUnitsPerLayoutUnit;
            pose.scale(factor, factor, 1);
            var window = minecraft.getWindow();
            double x = minecraft.mouseHandler.xpos() * width / window.getScreenWidth() * mouseXFactor;
            double y = minecraft.mouseHandler.ypos() * height / window.getScreenHeight() * mouseYFactor;
            renderMap(graphics, x, y, partialTick);
        } finally {
            pose.popPose();
        }
    }

    private void renderMap(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        int x = (int) Math.floor(mouseX);
        int y = (int) Math.floor(mouseY);
        var draw = new InGameMapUi(graphics, font, x, y, guiUnitsPerLayoutUnit, fontScale);
        ui = draw;
        RenderSystem.enableBlend();
        draw.fill(new Box(0, 0, layoutWidth, layoutHeight), BACKGROUND);
        draw.clip(new Box(0, HEADER, layoutWidth, layoutHeight - HEADER));
        tiles.draw(graphics, camera);
        if (preferences.enabled(Flag.GRID)) drawGrid(graphics);
        drawMarkers(draw);
        drawPlayer(graphics, draw);
        draw.unclip();

        drawHeader(draw);
        drawHud(draw);
        drawSidebar(draw);
        if (menu != null) drawMenu(draw);
        if (dialogs.open()) {
            draw.clearActions();
            dialogs.render(draw);
        }

        super.render(graphics, x, y, partialTick);
        if (toastTicks > 0) {
            int w = Math.min(layoutWidth - 12, draw.textWidth(toast) + 18);
            Box box = new Box((layoutWidth - w) / 2, layoutHeight - 47, w, 18);
            draw.fill(box, PANEL);
            draw.border(box, LINE);
            draw.center(toast, box, TEXT);
        }
        if (mouseY >= HEADER && !dialogs.open() && menu == null && !draw.blocked(mouseX, mouseY)) {
            hoveredX = (int) Math.round(camera.worldX(mouseX));
            hoveredZ = (int) Math.round(camera.worldZ(mouseY));
            hoverPending = true;
        }
    }

    private void drawGrid(GuiGraphics graphics) {
        int step = InGameMapTiles.CHUNK;
        while (step * camera.zoom() < 6) step *= 2;
        float stroke = (float) (1 / pixelsPerUnit);
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f pose = graphics.pose().last().pose();
        long x0 = (long) Math.ceil(camera.worldX(0) / step) * step;
        long z0 = (long) Math.ceil(camera.worldZ(HEADER) / step) * step;
        double right = camera.worldX(layoutWidth);
        double bottom = camera.worldZ(layoutHeight);
        for (long x = x0; x < right; x += step) {
            float screenX = Math.round(camera.screenX(x) * pixelsPerUnit) * stroke;
            gridQuad(buffer, pose, screenX, HEADER, screenX + stroke, layoutHeight);
        }
        for (long z = z0; z < bottom; z += step) {
            float screenY = Math.round(camera.screenY(z) * pixelsPerUnit) * stroke;
            gridQuad(buffer, pose, 0, screenY, layoutWidth, screenY + stroke);
        }
        graphics.flush();
    }

    private static void gridQuad(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1) {
        buffer.addVertex(pose, x0, y0, 0).setColor(GRID_LINE);
        buffer.addVertex(pose, x0, y1, 0).setColor(GRID_LINE);
        buffer.addVertex(pose, x1, y1, 0).setColor(GRID_LINE);
        buffer.addVertex(pose, x1, y0, 0).setColor(GRID_LINE);
    }

    private void drawHeader(InGameMapUi draw) {
        Box header = new Box(0, 0, layoutWidth, HEADER);
        draw.fill(header, TOP);
        draw.divider(0, HEADER - 1, layoutWidth);
        draw.block(header);
        draw.glyph("map", 10, 7, 16, TEXT);
        int navWidth = Math.clamp((layoutWidth - 88) / 6, 23, 44);
        int left = layoutWidth - 36 - navWidth * Nav.values().length;
        draw.smallText("地图册", 31, 11, left - 32, TEXT);
        int subtitleX = Math.max(67, draw.textWidth("地图册") + 35);
        if (left > 150) {
            int right = left > 350 ? 147 : left - 7;
            draw.smallText("Map Atlas · V3.1", subtitleX, 12, right - subtitleX, SOFT, SMALL_TEXT);
        }
        if (left > 350) {
            draw.fill(new Box(152, 9, 1, 13), LINE);
            draw.glyph("map", 164, 10, 10, MUTED);
            draw.smallText("卫星 · 写实", 178, 12, 43, TEXT, SMALL_TEXT);
            draw.fill(new Box(225, 9, 1, 13), LINE);
            String status = "结构 " + (markerLabels.size() - customMarkers.size()) + " · 标点 "
                    + customMarkers.size() + " · 缓存 " + backend.cachedCount();
            draw.smallText(status, 233, 12, left - 241, MUTED, SMALL_TEXT);
        }
        for (Nav nav : Nav.values()) {
            Box button = new Box(left, 0, navWidth, HEADER);
            if (draw.hovered(button) || active(nav)) draw.fill(button, FIELD);
            if (active(nav)) draw.fill(new Box(left, HEADER - 2, navWidth, 2), ACCENT);
            draw.glyph(nav.icon, left + (navWidth - 11) / 2, 5, 11, TEXT);
            draw.center(nav.label, new Box(left, 17, navWidth, 11), TEXT, SMALL_TEXT);
            draw.action(button, () -> navigate(nav));
            left += navWidth;
        }
        draw.iconButton(new Box(layoutWidth - 32, 4, 22, 22), "close", TEXT, this::closeScreen);
    }

    private boolean active(Nav nav) {
        if (dialogs.open()) return (dialogs.settingsOpen() && nav == Nav.SETTINGS)
                || (dialogs.aboutOpen() && nav == Nav.ABOUT);
        return switch (nav) {
            case MAP -> panel == Panel.NONE;
            case MARKERS -> panel == Panel.MARKERS;
            case LAYERS -> panel == Panel.LAYERS;
            default -> false;
        };
    }

    private void navigate(Nav nav) {
        menu = null;
        Panel next = switch (nav) {
            case MARKERS -> Panel.MARKERS;
            case LAYERS -> Panel.LAYERS;
            default -> Panel.NONE;
        };
        panel = next == panel ? Panel.NONE : next;
        panelScroll = 0;
        if (nav == Nav.SETTINGS) dialogs.settings();
        if (nav == Nav.ABOUT) dialogs.about();
    }

    private void drawHud(InGameMapUi draw) {
        if (preferences.enabled(Flag.COORDS) && cursor != null) {
            String coords = "X " + cursor.x + "   Y " + cursor.y + "   Z " + cursor.z;
            int textWidth = draw.textWidth(coords, SMALL_TEXT);
            Box box = new Box(9, layoutHeight - 72,
                    Math.min(layoutWidth / 2, Math.max(86, textWidth + 24)), 31);
            draw.panel(box);
            draw.glyph("locate", box.x() + 6, box.y() + 6, 9, MUTED);
            draw.smallText(coords, box.x() + 19, box.y() + 7, box.w() - 24, TEXT, SMALL_TEXT);
            draw.glyph("tree", box.x() + 6, box.y() + 19, 9, MUTED);
            draw.smallText(cursor.biome, box.x() + 19, box.y() + 20, box.w() - 24, MUTED, SMALL_TEXT);
        }
        Box scale = new Box(9, layoutHeight - 30, 86, 20);
        draw.panel(scale);
        long blocks = Math.round(72 / camera.scale());
        String distance = blocks >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000.0) : blocks + " m";
        draw.center(distance, new Box(10, scale.y() + 1, 84, 11), TEXT, SMALL_TEXT);
        draw.fill(new Box(16, scale.y() + 14, 72, 1), MUTED);
        draw.fill(new Box(16, scale.y() + 11, 1, 4), MUTED);
        draw.fill(new Box(87, scale.y() + 11, 1, 4), MUTED);
        if (preferences.enabled(Flag.DEBUG)) {
            draw.smallText("缓存 " + backend.cachedCount() + " · 待处理 " + backend.pendingCount(),
                    12, layoutHeight - 87, layoutWidth / 2 - 15, MUTED);
        }

        Box controls = new Box(layoutWidth - 93, layoutHeight - 32, 84, 22);
        Box locate = new Box(controls.x() - 26, controls.y(), 22, controls.h());
        draw.panel(locate);
        draw.iconButton(locate, "locate", TEXT, this::centerPlayer);
        draw.panel(controls);
        int x = controls.x();
        draw.button(new Box(x + 1, controls.y() + 1, 21, 20), "−", false,
                () -> zoom(0.8, camera.middleX(), camera.middleY()));
        draw.center(Math.round(camera.zoom() * 100) + "%", new Box(x + 22, controls.y() + 1, 40, 20), TEXT);
        draw.button(new Box(x + 62, controls.y() + 1, 21, 20), "+", false,
                () -> zoom(1.25, camera.middleX(), camera.middleY()));
    }

    private void drawPlayer(GuiGraphics graphics, InGameMapUi draw) {
        var player = backend.player();
        if (!player.present()) return;
        int x = px(camera.screenX(player.x()));
        int y = px(camera.screenY(player.z()));
        if (x < -20 || x > layoutWidth + 20 || y < HEADER - 20 || y > layoutHeight + 20) return;
        int size = preferences.number("playerSize", DEFAULT_PLAYER_SIZE, 6, 30);
        if (!preferences.enabled(Flag.FIX_PLAYER_SIZE)) size = Math.clamp(px(size * Math.pow(camera.zoom(), .35)), 6, 35);
        if (preferences.enabled(Flag.PLAYER_RING)) {
            int radius = Math.max(16, px(size * 1.6));
            for (int i = 0; i < 72; i++) {
                double angle = i * Math.PI / 36;
                draw.fill(new Box(x + px(Math.cos(angle) * radius),
                        y + px(Math.sin(angle) * radius), 1, 1), 0x50ffffff);
            }
        }
        int color = 0xff000000 | preferences.number("playerColor", 0xffe14e, 0, 0xffffff);
        boolean cross = preferences.enabled(Flag.CROSS_PLAYER);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        if (!cross) graphics.pose().mulPose(Axis.ZP.rotationDegrees(180 + player.yaw()));
        draw.glyph(cross ? "cross" : "player", -size / 2, -size / 2, size, color);
        graphics.pose().popPose();
    }

    void refreshMarkers() {
        List<MapMarkerStore.Marker> source = backend.markers();
        markerLabels = source.stream().sorted(Comparator
                        .comparingInt(InGameMapScreen::priority).reversed().thenComparing(marker -> marker.id))
                .map(marker -> {
                    String name = font.plainSubstrByWidth(marker.name, (int) (140 / fontScale));
                    int width = (int) Math.ceil(font.width(name) * SMALL_TEXT * fontScale);
                    return new MapLabel(marker, name, width);
                }).toList();
        customMarkers = source.stream().filter(MapMarkerStore.Marker::appearsAsCustom).toList();
        var groups = new LinkedHashMap<String, Layer>();
        for (MapMarkerStore.Marker marker : source) {
            if (marker.appearsAsCustom()) continue;
            var preset = MapMarkerStore.structurePreset(marker.structureId);
            String id = layerId(marker, preset);
            Layer old = groups.get(id);
            String name = preset == null ? marker.name : preset.label;
            groups.put(id, new Layer(id, name, marker.icon, preset, old == null ? 1 : old.count + 1));
        }
        structureLayers = new ArrayList<>(groups.values());
    }

    private static int priority(MapMarkerStore.Marker marker) {
        if (marker.appearsAsCustom()) return 2;
        return MapMarkerStore.structurePreset(marker.structureId) == MapMarkerStore.StructurePreset.VILLAGE ? 1 : 0;
    }

    private static String layerId(MapMarkerStore.Marker marker, MapMarkerStore.StructurePreset preset) {
        if (preset != null) return preset.name();
        return marker.structureId == null ? marker.name : marker.structureId;
    }

    private void drawMarkers(InGameMapUi draw) {
        pins.clear();
        List<Box> occupied = new ArrayList<>();
        boolean showNames = preferences.enabled(Flag.NAMES)
                && (!preferences.enabled(Flag.HIDE_LOW_ZOOM) || camera.zoom() >= .25);
        boolean avoid = preferences.enabled(Flag.AVOID_LABELS);
        int labelHeight = Math.max(12, draw.textHeight(SMALL_TEXT) + 4);
        double scaleFactor = Math.pow(Math.clamp(camera.zoom(), .15, 4),
                .2 * preferences.number("markerScale", 35, 0, 100) / 100.0);
        for (MapLabel label : markerLabels) {
            var marker = label.marker;
            if (marker.appearsAsCustom()) {
                if (!preferences.enabled(Flag.CUSTOM)) continue;
            } else {
                var preset = MapMarkerStore.structurePreset(marker.structureId);
                if (!preferences.enabled(Flag.STRUCTURES) || !preferences.layer(layerId(marker, preset), preset)) continue;
            }
            int x = px(camera.screenX(marker.x));
            int y = px(camera.screenY(marker.z));
            if (x < -40 || x > layoutWidth + 40 || y < HEADER - 40 || y > layoutHeight + 40) continue;
            double base = marker.appearsAsCustom() ? preferences.number("markerSize", DEFAULT_MARKER_SIZE, 7, 25) : 13.5;
            int size = Math.clamp(px(base * scaleFactor), 8, 24);
            Box icon = new Box(x - size / 2, y - size / 2, size, size);
            Box name = new Box(x - label.width / 2 - 4, icon.bottom() + 2, label.width + 8, labelHeight);
            Box padded = new Box(name.x() - 2, name.y() - 2, name.w() + 4, name.h() + 4);
            boolean nameVisible = showNames && name.bottom() > HEADER && name.y() < layoutHeight
                    && name.right() > 0 && name.x() < layoutWidth
                    && (!avoid || occupied.stream().noneMatch(padded::overlaps));
            if (nameVisible && avoid) occupied.add(padded);
            pins.add(new Pin(label, icon, name, nameVisible));
        }
        for (int i = pins.size() - 1; i >= 0; i--) {
            Pin pin = pins.get(i);
            Box box = pin.glyph;
            draw.glyph(pin.label.marker.icon, box.x() + 1, box.y() + 1, box.w(), 0x9905090b);
            draw.glyph(pin.label.marker.icon, box.x(), box.y(), box.w(), 0xff000000 | pin.label.marker.accent());
        }
        for (Pin pin : pins) {
            if (!pin.nameVisible) continue;
            draw.fill(pin.name, 0xcc12191d);
            draw.border(pin.name, LINE);
            draw.center(pin.label.text, pin.name, TEXT, SMALL_TEXT);
        }
    }

    private MapMarkerStore.Marker hitMarker(double x, double y) {
        for (Pin pin : pins) {
            if (pin.glyph.at(x, y) || (pin.nameVisible && pin.name.at(x, y))) return pin.label.marker;
        }
        return null;
    }

    private List<Row> layerRows() {
        var rows = new ArrayList<Row>();
        rows.add(new Row("自定义标点", "pin", preferences.enabled(Flag.CUSTOM), customMarkers.size(), false,
                () -> preferences.toggle(Flag.CUSTOM)));
        rows.add(new Row("自然结构", "temple", preferences.enabled(Flag.STRUCTURES),
                markerLabels.size() - customMarkers.size(), false, () -> preferences.toggle(Flag.STRUCTURES)));
        if (structuresExpanded) {
            for (Layer layer : structureLayers) {
                rows.add(new Row(layer.name, InGameMapUi.iconName(layer.icon), preferences.layer(layer.id, layer.preset),
                        layer.count, true, () -> preferences.toggleLayer(layer.id, layer.preset)));
            }
        }
        rows.add(new Row("名称标签", "info", preferences.enabled(Flag.NAMES), -1, false, () -> preferences.toggle(Flag.NAMES)));
        rows.add(new Row("区块网格", "frame", preferences.enabled(Flag.GRID), -1, false, () -> preferences.toggle(Flag.GRID)));
        return rows;
    }

    private void drawSidebar(InGameMapUi draw) {
        sidebar = null;
        if (panel == Panel.NONE) return;
        boolean layers = panel == Panel.LAYERS;
        List<Row> rows = layers ? layerRows() : List.of();
        int count = layers ? rows.size() : customMarkers.size();
        int bodyTop = HEADER + 34;
        int rowHeight = layers ? ROW : Math.max(ROW, draw.textHeight() * 2 + 1);
        int availableRows = Math.max(1, (layoutHeight - bodyTop - 45) / rowHeight);
        int visibleRows = Math.max(1, Math.min(availableRows, count));
        sidebar = new Box(8, HEADER + 9, Math.min(layers ? 150 : 177, layoutWidth / 2 + 20),
                bodyTop - (HEADER + 9) + visibleRows * rowHeight + 6);
        Box box = sidebar;
        draw.panel(box);
        draw.glyph(layers ? "layers" : "pin", box.x() + 7, box.y() + 7, 11, TEXT);
        draw.text(layers ? "图层" : "自定义标点", box.x() + 23, box.y() + 8, TEXT);
        draw.iconButton(new Box(box.right() - 22, box.y() + 3, 18, 18), "close", MUTED, () -> panel = Panel.NONE);
        draw.divider(box.x(), bodyTop - 2, box.w());
        panelScroll = Math.clamp(panelScroll, 0, Math.max(0, count - visibleRows));
        for (int i = panelScroll; i < Math.min(count, panelScroll + visibleRows); i++) {
            int y = bodyTop + (i - panelScroll) * rowHeight;
            Box row = new Box(box.x() + 5, y, box.w() - 11, rowHeight);
            if (draw.hovered(row)) draw.fill(row, FIELD);
            draw.divider(row.x(), row.bottom() - 1, row.w());
            if (layers) drawLayerRow(draw, rows.get(i), i, row);
            else drawMarkerRow(draw, customMarkers.get(i), row);
        }
        if (!layers && count == 0) draw.smallText("暂无自定义标点", box.x() + 8, bodyTop + 5, box.w() - 16, MUTED);
        if (count > visibleRows) {
            int track = visibleRows * rowHeight;
            int thumb = Math.max(9, track * visibleRows / count);
            int offset = (track - thumb) * panelScroll / (count - visibleRows);
            draw.fill(new Box(box.right() - 4, bodyTop + offset, 2, thumb), SOFT);
        }
    }

    private void drawLayerRow(InGameMapUi draw, Row layer, int index, Box row) {
        int x = row.x() + (layer.child ? 14 : 1);
        draw.check(new Box(x, row.y() + 5, 9, 9), layer.checked);
        draw.glyph(layer.icon, x + 14, row.y() + 4, 11, TEXT);
        draw.smallText(layer.label, x + 30, row.y() + 5, row.right() - x - 50, TEXT);
        if (layer.count >= 0) {
            String text = Integer.toString(layer.count);
            draw.text(text, row.right() - draw.textWidth(text) - (index == 1 ? 15 : 3), row.y() + 5, MUTED);
        }
        draw.action(row, layer.run);
        if (index == 1) {
            Box toggle = new Box(row.right() - 13, row.y() + 1, 12, 16);
            if (structuresExpanded) draw.center("⌃", toggle, MUTED);
            else draw.glyph("chevron", toggle.x() + 2, toggle.y() + 4, 8, MUTED);
            draw.action(toggle, () -> structuresExpanded = !structuresExpanded);
        }
    }

    private void drawMarkerRow(InGameMapUi draw, MapMarkerStore.Marker marker, Box row) {
        draw.glyph(marker.icon, row.x() + 2, row.y() + 3, 13, 0xff000000 | marker.accent());
        draw.smallText(marker.name, row.x() + 19, row.y() + 1, row.w() - 55, TEXT);
        String coord = marker.x + ", " + marker.y + ", " + marker.z;
        draw.smallText(coord, row.x() + 19, row.y() + 1 + draw.textHeight(), row.w() - 55, SOFT);
        draw.action(row, () -> {
            camera.center(marker.x, marker.z);
            viewportDelay = 1;
        });
        draw.iconButton(new Box(row.right() - 32, row.y() + 1, 15, 17), "edit", MUTED,
                () -> dialogs.edit(marker, null));
        draw.iconButton(new Box(row.right() - 16, row.y() + 1, 15, 17),
                marker.modifiedAuto ? "reset" : "trash", MUTED, () -> removeMarker(marker));
    }

    private void drawMenu(InGameMapUi draw) {
        Menu current = menu;
        int height = 77 + Math.max(0, draw.textHeight() - font.lineHeight);
        Box box = new Box(Math.clamp(current.left, 3, Math.max(3, layoutWidth - 129)),
                Math.clamp(current.top, HEADER + 2, Math.max(HEADER + 2, layoutHeight - height - 6)),
                126, height);
        draw.panel(box);
        Point point = current.point;
        draw.smallText(point.x + " / " + point.y + " / " + point.z, box.x() + 6, box.y() + 5, box.w() - 12, MUTED);
        draw.divider(box.x() + 3, box.bottom() - 61, box.w() - 6);
        menuItem(draw, box, 0, "teleport", "传送", TEXT, () -> {
            backend.teleport(point.x, point.z);
            notice("已发送传送请求");
        });
        if (current.marker == null) {
            menuItem(draw, box, 1, "add", "添加标点", TEXT, () -> dialogs.edit(null, point));
            menuItem(draw, box, 2, "copy", "复制坐标", TEXT, () -> {
                minecraft.keyboardHandler.setClipboard(point.x + " " + point.y + " " + point.z);
                notice("已复制坐标");
            });
        } else {
            var marker = current.marker;
            menuItem(draw, box, 1, "edit", "编辑标点", TEXT, () -> dialogs.edit(marker, null));
            menuItem(draw, box, 2, marker.modifiedAuto ? "reset" : "trash",
                    marker.modifiedAuto ? "还原自动结构" : "删除标点", DANGER, () -> removeMarker(marker));
        }
    }

    private void menuItem(InGameMapUi draw, Box box, int index, String icon, String title, int color, Runnable action) {
        Box row = new Box(box.x() + 3, box.bottom() - 58 + index * 18, box.w() - 6, 17);
        if (draw.hovered(row)) draw.fill(row, HOVER);
        draw.glyph(icon, row.x() + 4, row.y() + 3, 11, color);
        draw.smallText(title, row.x() + 20, row.y() + (row.h() - draw.textHeight()) / 2,
                row.w() - 23, color);
        draw.action(row, () -> {
            menu = null;
            action.run();
        });
    }

    private void removeMarker(MapMarkerStore.Marker marker) {
        if (marker.modifiedAuto) {
            backend.resetAutomaticMarker(marker.uuid());
            notice("已还原自动结构");
        } else {
            backend.deleteMarker(marker.uuid());
            notice("标点已删除");
        }
        refreshMarkers();
    }

    private Point point(int x, int z) {
        String biome = backend.biomeLabelAt(x, z);
        if (biome.startsWith("群系：")) biome = biome.substring(3);
        return new Point(x, backend.surfaceYAt(x, z), z, biome);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        x *= mouseXFactor;
        y *= mouseYFactor;
        if (ui == null) return false;
        if (dialogs.open()) {
            if (super.mouseClicked(x, y, button)) return true;
            if (button == 0) ui.click(x, y);
            return true;
        }
        if (button == 0 && ui.click(x, y)) return true;
        if (menu != null) {
            menu = null;
            return true;
        }
        if (ui.blocked(x, y)) return true;
        var marker = hitMarker(x, y);
        if (button == 1) {
            Point point = marker == null
                    ? point((int) Math.round(camera.worldX(x)), (int) Math.round(camera.worldZ(y)))
                    : new Point(marker.x, marker.y, marker.z, "");
            menu = new Menu(px(x), px(y), point, marker);
            return true;
        }
        if (button != 0) return false;
        if (marker != null) {
            long now = System.nanoTime();
            if (lastClick.equals(marker.id) && now - lastClickAt < 400_000_000L) {
                lastClick = "";
                dialogs.edit(marker, null);
            } else {
                lastClick = marker.id;
                lastClickAt = now;
            }
            return true;
        }
        if (preferences.enabled(Flag.DRAG_MAP)) {
            dragging = true;
            submitViewport();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging && button == 0) {
            camera.pan(dx * mouseXFactor, dy * mouseYFactor);
            return true;
        }
        return super.mouseDragged(x * mouseXFactor, y * mouseYFactor, button,
                dx * mouseXFactor, dy * mouseYFactor);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (dragging && button == 0) {
            dragging = false;
            viewportDelay = 1;
            return true;
        }
        return super.mouseReleased(x * mouseXFactor, y * mouseYFactor, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        x *= mouseXFactor;
        y *= mouseYFactor;
        if (dialogs.open()) return dialogs.scroll(x, y, scrollY);
        if (sidebar != null && sidebar.at(x, y)) {
            panelScroll = Math.max(0, panelScroll - (int) Math.signum(scrollY) * 3);
            return true;
        }
        if (ui != null && !ui.blocked(x, y)) {
            menu = null;
            zoom(Math.pow(1.12, scrollY), x, y);
        }
        return true;
    }

    void addInput(EditBox field) { addRenderableWidget(field); }

    void clearInputs() {
        setFocused(null);
        clearWidgets();
    }

    void refreshRaster() {
        tiles.close();
        SatelliteSurfaceRenderer.beginMapSession();
        viewportDelay = 1;
        notice("地图图像已重新着色");
    }

    void notice(String message) {
        toast = message;
        toastTicks = 50;
    }

    private static int px(double value) { return (int) Math.round(value); }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (dialogs.open()) {
            dialogs.close();
        } else if (menu != null) {
            menu = null;
        } else {
            closeScreen();
        }
    }

    private void closeScreen() { super.onClose(); }

    @Override
    public void removed() {
        if (removed) return;
        removed = true;
        onRemoved.accept(camera.position(backend.dimension()));
        super.removed();
    }
}
