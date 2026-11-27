package com.wanstu.cartograph.v4;

/** Pure orthographic camera math. Coordinates are world X/Y/Z; screen Y increases down. */
public record MapCamera(double x, double y, double z, double pixelsPerBlock,
                        double yawDegrees, double elevationDegrees) {
    public record World(double x, double y, double z) {}
    public record Screen(double x, double y, double depth) {}

    public MapCamera {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) ||
                !Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0 ||
                !Double.isFinite(yawDegrees) || !Double.isFinite(elevationDegrees) ||
                elevationDegrees < 30 || elevationDegrees > 90)
            throw new IllegalArgumentException("Invalid map camera");
        yawDegrees = ((yawDegrees % 360) + 360) % 360;
    }

    private double yaw() { return Math.toRadians(yawDegrees); }
    private double pitch() { return Math.toRadians(elevationDegrees); }
    private double sinPitch() { return elevationDegrees == 90 ? 1 : Math.sin(pitch()); }
    private double cosPitch() { return elevationDegrees == 90 ? 0 : Math.cos(pitch()); }

    /** Screen origin is viewport center; depth is in world units. */
    public Screen project(World point) {
        double dx = point.x - x, dy = point.y - y, dz = point.z - z;
        double right = dx * Math.cos(yaw()) + dz * Math.sin(yaw());
        double forward = -dx * Math.sin(yaw()) + dz * Math.cos(yaw());
        return new Screen(right * pixelsPerBlock,
                (forward * sinPitch() - dy * cosPitch()) * pixelsPerBlock,
                forward * cosPitch() + dy * sinPitch());
    }

    public World unproject(Screen point) {
        double right = point.x / pixelsPerBlock, down = point.y / pixelsPerBlock;
        double forward = down * sinPitch() + point.depth * cosPitch();
        double dy = -down * cosPitch() + point.depth * sinPitch();
        return new World(x + right * Math.cos(yaw()) - forward * Math.sin(yaw()),
                y + dy, z + right * Math.sin(yaw()) + forward * Math.cos(yaw()));
    }

    /** Intersect view ray with known horizontal plane. Terrain picking must use actual mesh/depth. */
    public World atHeight(double screenX, double screenY, double worldY) {
        double down = screenY / pixelsPerBlock;
        double depth = (worldY - y + down * cosPitch()) / sinPitch();
        return unproject(new Screen(screenX, screenY, depth));
    }

    public MapCamera withAngles(double yaw, double elevation) {
        return new MapCamera(x,y,z,pixelsPerBlock,yaw,elevation);
    }

    /** Keep a known world anchor fixed in screen space during zoom. */
    public MapCamera zoomAt(double scale, World anchor) {
        Screen before = project(anchor);
        MapCamera next = new MapCamera(x,y,z,scale,yawDegrees,elevationDegrees);
        World under = next.atHeight(before.x,before.y,anchor.y);
        return new MapCamera(x+anchor.x-under.x,y,z+anchor.z-under.z,
                scale,yawDegrees,elevationDegrees);
    }
}
