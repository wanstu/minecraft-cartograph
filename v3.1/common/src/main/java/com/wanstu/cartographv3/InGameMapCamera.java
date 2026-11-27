package com.wanstu.cartographv3;

/** View coordinates are map layout units. Zoom is measured in framebuffer pixels per block. */
final class InGameMapCamera {
    record Position(String dimension, double x, double z, double zoom) {}

    private static final double MIN_ZOOM = 1.0 / 16;
    private static final double MAX_ZOOM = 64;

    private int width;
    private int height;
    private int top;
    private double pixelsPerUnit = 1;
    private double centerX;
    private double centerZ;
    private double zoom = 1.0;

    void resize(int width, int height, int top, double pixelsPerUnit) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height - top);
        this.top = top;
        this.pixelsPerUnit = pixelsPerUnit;
    }

    void restore(Position position) {
        center(position.x(), position.z());
        zoom = clampZoom(position.zoom());
    }

    Position position(String dimension) {
        return new Position(dimension, centerX, centerZ, zoom);
    }

    void center(double x, double z) {
        centerX = x;
        centerZ = z;
    }

    void pan(double dx, double dy) {
        centerX -= dx / scale();
        centerZ -= dy / scale();
    }

    void zoom(double factor, double mouseX, double mouseY) {
        if (!Double.isFinite(factor) || factor <= 0) return;
        double next = clampZoom(zoom * factor);
        if (next == zoom) return;
        double x = worldX(mouseX);
        double z = worldZ(mouseY);
        zoom = next;
        centerX += x - worldX(mouseX);
        centerZ += z - worldZ(mouseY);
    }

    void scroll(double steps, double mouseX, double mouseY) {
        if (!Double.isFinite(steps)) return;
        zoom(Math.pow(1.12, Math.clamp(steps, -1.0, 1.0)), mouseX, mouseY);
    }

    private static double clampZoom(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }

    double zoom() { return zoom; }
    double scale() { return zoom / pixelsPerUnit; }
    double middleX() { return width / 2.0; }
    double middleY() { return top + height / 2.0; }
    int top() { return top; }
    int width() { return width; }
    int bottom() { return top + height; }

    double screenX(double x) { return middleX() + (x - centerX) * scale(); }
    double screenY(double z) { return middleY() + (z - centerZ) * scale(); }
    double worldX(double x) { return centerX + (x - middleX()) / scale(); }
    double worldZ(double y) { return centerZ + (y - middleY()) / scale(); }

    CartographBackendApi.Viewport viewport(boolean dragging) {
        return new CartographBackendApi.Viewport(
                centerX, centerZ, scale(), width, height, dragging);
    }
}
