package com.wanstu.cartographv3;

import com.mojang.math.Axis;
import net.ethrocky.pane.core.Component;
import net.ethrocky.pane.core.Rect;
import net.ethrocky.pane.core.Size;
import net.ethrocky.pane.core.UiEvent;
import net.ethrocky.pane.core.style.Theme;
import net.ethrocky.pane.render.PaneRenderer;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Comparator;
import java.util.List;

/**
 * Native-theme map viewport implemented as a Pane component.
 *
 * It owns presentation state (center/zoom/hover/drag) and renders through the
 * active MapStyleRenderer. It never reads network packets or persistent cache
 * directly; all world facts come through CartographBackendApi.
 */
final class NativeMapCanvas extends Component implements AutoCloseable {
    interface Listener {
        void openContext(ContextTarget target, int screenX, int screenY);
    }

    record ContextTarget(
            int x, int y, int z,
            MapMarkerStore.Marker marker) {}

    private static final double[] ZOOM_LEVELS = {
            0.0625, 0.0833333333, 0.1, 0.125, 0.1666666667, 0.2,
            0.25, 0.3333333333, 0.5, 0.625, 0.75, 1.0, 1.25, 1.5,
            2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0,
            20.0, 24.0, 32.0, 40.0, 48.0, 64.0
    };

    private final CartographBackendApi backend;
    private final MapRegionTextureCache regionTextures;
    private final Listener listener;

    private double centerX;
    private double centerZ;
    private double zoom = 2.0;
    private boolean initialized;
    private boolean dragging;
    private double dragLastX;
    private double dragLastY;
    private double mouseX;
    private double mouseY;
    private MapMarkerStore.Marker hoveredMarker;

    NativeMapCanvas(
            CartographBackendApi backend,
            MapStyleRenderer mapStyle,
            Listener listener) {
        this.backend = backend;
        this.regionTextures = new MapRegionTextureCache(backend, mapStyle);
        this.listener = listener;
    }

    @Override
    public Size measure(TextMeasurer tm) {
        return new Size(
                Math.max(1, style.width == null ? 1 : style.width),
                Math.max(1, style.height == null ? 1 : style.height));
    }

    @Override
    public void layout(Rect next, TextMeasurer tm) {
        super.layout(next, tm);
        ensureInitialCenter();
        syncViewport(false);
    }

    double centerX() {
        ensureInitialCenter();
        return centerX;
    }

    double centerZ() {
        ensureInitialCenter();
        return centerZ;
    }

    double zoom() {
        return zoom;
    }

    String zoomLabel() {
        if (zoom >= 1.0) {
            return String.format(java.util.Locale.ROOT, "%.0f%%", zoom * 100.0);
        }
        return String.format(java.util.Locale.ROOT, "%.0f%%", zoom * 100.0);
    }

    int cursorBlockX() {
        return worldX(mouseX);
    }

    int cursorBlockZ() {
        return worldZ(mouseY);
    }

    void centerOnPlayer() {
        CartographBackendApi.PlayerState player = backend.player();
        if (!player.present()) return;
        centerX = player.x();
        centerZ = player.z();
        syncViewport(true);
    }

    void zoomStep(int direction) {
        int current = nearestZoomIndex(zoom);
        int next = Math.max(
                0,
                Math.min(
                        ZOOM_LEVELS.length - 1,
                        current + Integer.signum(direction)));
        setZoomAt(ZOOM_LEVELS[next], bounds.x() + bounds.w() * 0.5,
                bounds.y() + bounds.h() * 0.5);
    }

    void setMapStyle(MapStyleRenderer style) {
        regionTextures.setStyle(style);
        invalidate();
    }

    @Override
    protected boolean handleMouse(UiEvent.Mouse e) {
        mouseX = e.x();
        mouseY = e.y();

        if (e.kind() == UiEvent.Kind.MOVE) {
            updateHoveredMarker(e.x(), e.y());
            if (dragging) {
                double dx = e.x() - dragLastX;
                double dy = e.y() - dragLastY;
                dragLastX = e.x();
                dragLastY = e.y();
                centerX -= dx / zoom;
                centerZ -= dy / zoom;
                syncViewport(false);
                return true;
            }
            return e.inside(bounds);
        }

        if (!e.inside(bounds)) {
            if (e.kind() == UiEvent.Kind.UP) dragging = false;
            return false;
        }

        switch (e.kind()) {
            case DOWN -> {
                if (e.button() == 0 && MapUiSettings.dragMap()) {
                    dragging = true;
                    dragLastX = e.x();
                    dragLastY = e.y();
                    return true;
                }
                if (e.button() == 1) {
                    int blockX = worldX(e.x());
                    int blockZ = worldZ(e.y());
                    int blockY = backend.surfaceYAt(blockX, blockZ);
                    MapMarkerStore.Marker marker = markerAt(e.x(), e.y());
                    if (listener != null) {
                        listener.openContext(
                                new ContextTarget(blockX, blockY, blockZ, marker),
                                (int) Math.round(e.x()),
                                (int) Math.round(e.y()));
                    }
                    return true;
                }
            }
            case UP -> {
                if (e.button() == 0 && dragging) {
                    dragging = false;
                    syncViewport(true);
                    return true;
                }
            }
            case SCROLL -> {
                if (e.scrollY() == 0.0) return true;
                int current = nearestZoomIndex(zoom);
                int steps = Math.max(1, (int) Math.round(Math.abs(e.scrollY())));
                int next = Math.max(
                        0,
                        Math.min(
                                ZOOM_LEVELS.length - 1,
                                current + (e.scrollY() > 0 ? steps : -steps)));
                setZoomAt(ZOOM_LEVELS[next], e.x(), e.y());
                return true;
            }
            default -> { }
        }

        return true;
    }

    @Override
    protected void paintSelf(PaneRenderer renderer, Theme theme) {
        ensureInitialCenter();
        renderer.fill(bounds, 0xff17383c);

        if (!(renderer instanceof MapPaneRenderer paneRenderer)) {
            return;
        }

        GuiGraphics graphics = paneRenderer.graphics();
        renderer.pushClip(bounds);
        try {
            regionTextures.render(
                    graphics,
                    backend.dimension(),
                    centerX, centerZ, zoom,
                    bounds.x(), bounds.y(), bounds.w(), bounds.h());

            if (MapUiSettings.grid()) {
                renderGrid(renderer);
            }

            renderMarkers(renderer, graphics);
            renderPlayer(graphics);
            renderCursorInfo(renderer);
        } finally {
            renderer.popClip();
        }

        syncViewport(false);
    }

    private void renderGrid(PaneRenderer renderer) {
        double step = 16.0 * zoom;
        if (step < 8.0) return;

        double worldLeft = centerX - bounds.w() / (2.0 * zoom);
        double worldTop = centerZ - bounds.h() / (2.0 * zoom);

        int firstChunkX = Math.floorDiv((int) Math.floor(worldLeft), 16);
        int firstChunkZ = Math.floorDiv((int) Math.floor(worldTop), 16);

        double firstX = bounds.x()
                + ((firstChunkX * 16.0) - worldLeft) * zoom;
        double firstY = bounds.y()
                + ((firstChunkZ * 16.0) - worldTop) * zoom;

        for (double x = firstX; x <= bounds.right(); x += step) {
            int px = (int) Math.round(x);
            renderer.fill(
                    new Rect(px, bounds.y(), 1, bounds.h()),
                    0x24e4edf0);
        }
        for (double y = firstY; y <= bounds.bottom(); y += step) {
            int py = (int) Math.round(y);
            renderer.fill(
                    new Rect(bounds.x(), py, bounds.w(), 1),
                    0x24e4edf0);
        }
    }

    private void renderMarkers(PaneRenderer renderer, GuiGraphics graphics) {
        List<MapMarkerStore.Marker> markers = backend.markers();
        markers.sort(Comparator.comparingInt(marker ->
                marker.origin == MapMarkerStore.Origin.AUTO ? 0 : 1));

        for (MapMarkerStore.Marker marker : markers) {
            boolean automatic =
                    marker.origin == MapMarkerStore.Origin.AUTO
                            && !marker.modifiedAuto;
            if (automatic && !MapUiSettings.structureLayerEnabled()) continue;
            if (!automatic && !MapUiSettings.markerLayerEnabled()) continue;

            int sx = screenX(marker.x);
            int sy = screenY(marker.z);
            if (sx < bounds.x() - 48 || sx > bounds.right() + 48
                    || sy < bounds.y() - 48 || sy > bounds.bottom() + 48) {
                continue;
            }

            int baseSize = automatic ? 25 : 28;
            int scalePercent = MapUiSettings.markerScalePercent();
            double zoomFactor = 1.0 + (zoom - 1.0) * (scalePercent / 100.0);
            int size = Math.max(14, Math.min(52,
                    (int) Math.round(baseSize * Math.max(0.55, zoomFactor))));

            boolean hover = hoveredMarker != null
                    && hoveredMarker.id.equals(marker.id);
            if (hover) size += 2;

            int color = marker.accent();
            MapIconAtlas.drawMarkerMono(
                    graphics, marker.icon,
                    sx, sy, size,
                    color == 0 ? 0xffffff : color);

            if (MapUiSettings.labels()) {
                renderMarkerLabel(renderer, marker, sx, sy, size);
            }
        }
    }

    private void renderMarkerLabel(
            PaneRenderer renderer,
            MapMarkerStore.Marker marker,
            int centerX,
            int centerY,
            int iconSize) {
        String text = marker.name == null || marker.name.isBlank()
                ? marker.icon.label
                : marker.name;
        int textW = renderer.width(text);
        int textH = renderer.height();
        int x = centerX - textW / 2 - 6;
        int y = centerY + iconSize / 2 + 4;
        Rect label = new Rect(x, y, textW + 12, textH + 6);
        renderer.fill(label, 0xcc12191d);
        renderer.border(label, 0xff536068, 1);
        renderer.text(text, x + 6, y + 3, 0xfff4f6f7);
    }

    private void renderPlayer(GuiGraphics graphics) {
        CartographBackendApi.PlayerState player = backend.player();
        if (!player.present()) return;

        int x = screenX(player.x());
        int y = screenY(player.z());
        int size = MapUiSettings.markerSize();

        if (MapUiSettings.playerRing()) {
            int radius = Math.max(18, size * 2);
            MapIconAtlas.drawPlayerRing(
                    graphics, x, y, radius * 2 + 4,
                    0xffffff, 0.30f);
        }

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0f);
        pose.mulPose(Axis.ZP.rotationDegrees(180.0f + player.yaw()));
        int primary = MapUiSettings.markerPrimaryColor();
        int accent = MapUiSettings.markerColor();
        int border = MapUiSettings.markerLineColor();
        if (MapUiSettings.markerType() == MapUiSettings.MarkerType.CROSS) {
            MapIconAtlas.drawPlayerCross(
                    graphics, 0, 0, size,
                    primary, accent, border);
        } else {
            MapIconAtlas.drawPlayerArrow(
                    graphics, 0, 0, size,
                    primary, accent, border);
        }
        pose.popPose();
    }

    private void renderCursorInfo(PaneRenderer renderer) {
        if (!MapUiSettings.showCoords()) return;
        int blockX = cursorBlockX();
        int blockZ = cursorBlockZ();
        int blockY = backend.surfaceYAt(blockX, blockZ);

        String coord = "X " + blockX + "   Y " + blockY + "   Z " + blockZ;
        String biome = backend.biomeLabelAt(blockX, blockZ);

        int w = Math.max(renderer.width(coord), renderer.width(biome)) + 20;
        int h = renderer.height() * 2 + 16;
        Rect box = new Rect(
                bounds.x() + 18,
                bounds.bottom() - h - 20,
                w, h);
        renderer.fill(box, 0xe8172126);
        renderer.border(box, 0xff53616a, 1);
        renderer.text(coord, box.x() + 10, box.y() + 6, 0xffe9eef1);
        renderer.text(
                biome,
                box.x() + 10,
                box.y() + 8 + renderer.height(),
                0xffaab5bc);
    }

    private void ensureInitialCenter() {
        if (initialized) return;
        CartographBackendApi.PlayerState player = backend.player();
        if (player.present()) {
            centerX = player.x();
            centerZ = player.z();
        }
        initialized = true;
    }

    private int screenX(double worldX) {
        return (int) Math.round(
                bounds.x() + bounds.w() * 0.5 + (worldX - centerX) * zoom);
    }

    private int screenY(double worldZ) {
        return (int) Math.round(
                bounds.y() + bounds.h() * 0.5 + (worldZ - centerZ) * zoom);
    }

    private int worldX(double screenX) {
        return (int) Math.floor(
                centerX + (screenX - (bounds.x() + bounds.w() * 0.5)) / zoom);
    }

    private int worldZ(double screenY) {
        return (int) Math.floor(
                centerZ + (screenY - (bounds.y() + bounds.h() * 0.5)) / zoom);
    }

    private void setZoomAt(double nextZoom, double screenX, double screenY) {
        double next = Math.max(
                ZOOM_LEVELS[0],
                Math.min(ZOOM_LEVELS[ZOOM_LEVELS.length - 1], nextZoom));
        if (Double.compare(next, zoom) == 0) return;

        double worldAtX = centerX
                + (screenX - (bounds.x() + bounds.w() * 0.5)) / zoom;
        double worldAtZ = centerZ
                + (screenY - (bounds.y() + bounds.h() * 0.5)) / zoom;

        centerX = worldAtX
                - (screenX - (bounds.x() + bounds.w() * 0.5)) / next;
        centerZ = worldAtZ
                - (screenY - (bounds.y() + bounds.h() * 0.5)) / next;
        zoom = next;
        syncViewport(true);
    }

    private void syncViewport(boolean force) {
        if (bounds.w() <= 0 || bounds.h() <= 0) return;
        backend.updateViewport(
                new CartographBackendApi.Viewport(
                        centerX, centerZ, zoom,
                        bounds.w(), bounds.h(),
                        dragging),
                force);
    }

    private void updateHoveredMarker(double x, double y) {
        hoveredMarker = markerAt(x, y);
    }

    private MapMarkerStore.Marker markerAt(double x, double y) {
        MapMarkerStore.Marker best = null;
        double bestDistance = Double.MAX_VALUE;

        for (MapMarkerStore.Marker marker : backend.markers()) {
            boolean automatic =
                    marker.origin == MapMarkerStore.Origin.AUTO
                            && !marker.modifiedAuto;
            if (automatic && !MapUiSettings.structureLayerEnabled()) continue;
            if (!automatic && !MapUiSettings.markerLayerEnabled()) continue;

            double dx = x - screenX(marker.x);
            double dy = y - screenY(marker.z);
            double distance = dx * dx + dy * dy;
            if (distance <= 20.0 * 20.0 && distance < bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        return best;
    }

    private static int nearestZoomIndex(double value) {
        int best = 0;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < ZOOM_LEVELS.length; i++) {
            double nextDistance = Math.abs(ZOOM_LEVELS[i] - value);
            if (nextDistance < distance) {
                distance = nextDistance;
                best = i;
            }
        }
        return best;
    }

    @Override
    public void close() {
        regionTextures.close();
    }
}
