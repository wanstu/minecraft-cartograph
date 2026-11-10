package com.wanstu.cartograph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Atlas-owned geographic annotation; not a land claim or block mutation. */
public record NamedRegion(String id, String dimension, String name, int type, int color,
                          String note, List<Point> boundary) {
    public static final int MAX_VERTICES = 64;
    public static final int MAX_NAME = 24;
    public static final int MAX_NOTE = 100;
    public static final int MAX_REGIONS = 80;
    public static final String[] TYPES = {"海域", "山脉", "森林", "平原", "湖泊", "河谷", "自定义"};

    public record Point(int x, int z) {}

    public NamedRegion {
        if (id == null || id.isBlank() || id.length() > 48
                || dimension == null || dimension.isBlank() || dimension.length() > 128)
            throw new IllegalArgumentException("Invalid named region identity");
        name = name == null ? "" : name.strip();
        if (name.isBlank() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("Invalid region name");
        if (type < 0 || type >= TYPES.length || color < 0 || color > 0xffffff)
            throw new IllegalArgumentException("Invalid region category or color");
        note = note == null ? "" : note.strip();
        if (note.length() > MAX_NOTE) throw new IllegalArgumentException("Region note too long");
        if (boundary == null || boundary.size() < 3 || boundary.size() > MAX_VERTICES)
            throw new IllegalArgumentException("Invalid number of region vertices");
        boundary = List.copyOf(boundary);
        for (Point point : boundary) {
            if (point == null || Math.abs((long) point.x()) > 30_000_000
                    || Math.abs((long) point.z()) > 30_000_000)
                throw new IllegalArgumentException("Region outside world limits");
        }
        if (!simplePolygon(boundary))
            throw new IllegalArgumentException("Region must have non-self-intersecting area");
    }

    public static NamedRegion create(String dimension, String name, int type, int color,
                                     String note, List<Point> polygon) {
        return new NamedRegion(UUID.randomUUID().toString(), dimension, name, type, color, note, polygon);
    }

    /** Integer-safe planar area and exact crossing checks. Max world coordinate values
        are checked before this method. No arbitrary seed-derived geometry. */
    public static boolean simplePolygon(List<Point> vertices) {
        if (vertices == null || vertices.size() < 3 || vertices.size() > MAX_VERTICES)
            return false;
        double twiceArea = 0;
        for (int i = 0; i < vertices.size(); i++) {
            Point p = vertices.get(i), q = vertices.get((i + 1) % vertices.size());
            if (p == null || q == null || p.equals(q)) return false;
            twiceArea += (double) p.x() * q.z() - (double) q.x() * p.z();
            for (int j = i + 1; j < vertices.size(); j++) {
                int nextI = (i + 1) % vertices.size();
                int nextJ = (j + 1) % vertices.size();
                if (j == nextI || i == nextJ) continue;
                Point a = vertices.get(j), b = vertices.get(nextJ);
                if (segmentsIntersect(p, q, a, b)) return false;
            }
        }
        return Math.abs(twiceArea) >= 128; // at least 64 square blocks
    }

    private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        long o1 = orientation(a, b, c), o2 = orientation(a, b, d);
        long o3 = orientation(c, d, a), o4 = orientation(c, d, b);
        if (o1 == 0 && within(a, b, c)) return true;
        if (o2 == 0 && within(a, b, d)) return true;
        if (o3 == 0 && within(c, d, a)) return true;
        if (o4 == 0 && within(c, d, b)) return true;
        return Long.signum(o1) != Long.signum(o2) && Long.signum(o3) != Long.signum(o4);
    }

    private static long orientation(Point a, Point b, Point c) {
        return ((long) b.x() - a.x()) * ((long) c.z() - a.z())
                - ((long) b.z() - a.z()) * ((long) c.x() - a.x());
    }

    private static boolean within(Point a, Point b, Point p) {
        return p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
                && p.z() >= Math.min(a.z(), b.z()) && p.z() <= Math.max(a.z(), b.z());
    }

    /** Interior label anchor; remains stable when switching map styles. */
    public Point labelPosition() {
        double area = 0, sumX = 0, sumZ = 0;
        for (int i = 0; i < boundary.size(); i++) {
            Point a = boundary.get(i), b = boundary.get((i + 1) % boundary.size());
            double cross = (double) a.x() * b.z() - (double) b.x() * a.z();
            area += cross;
            sumX += ((double) a.x() + b.x()) * cross;
            sumZ += ((double) a.z() + b.z()) * cross;
        }
        if (Math.abs(area) < 1.0) return boundary.get(0);
        Point center = new Point((int) Math.round(sumX / (3 * area)),
                (int) Math.round(sumZ / (3 * area)));
        // Concave polygons may have centroids outside; return a known interior
        // vertex-median sample instead of placing the name on another region.
        return contains(center.x(), center.z()) ? center : boundary.get(0);
    }

    public boolean contains(double x, double z) {
        boolean inside = false;
        for (int i = 0, j = boundary.size() - 1; i < boundary.size(); j = i++) {
            Point a = boundary.get(i), b = boundary.get(j);
            if ((a.z() > z) != (b.z() > z)
                    && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x())
                inside = !inside;
        }
        return inside;
    }

    public CompoundTag save() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", id);
        nbt.putString("dim", dimension);
        nbt.putString("name", name);
        nbt.putInt("type", type);
        nbt.putInt("color", color);
        nbt.putString("note", note);
        ListTag polygon = new ListTag();
        for (Point point : boundary) {
            CompoundTag vertex = new CompoundTag();
            vertex.putInt("x", point.x()); vertex.putInt("z", point.z());
            polygon.add(vertex);
        }
        nbt.put("boundary", polygon);
        return nbt;
    }

    public static NamedRegion read(CompoundTag tag) {
        try {
            ListTag points = tag.getList("boundary", Tag.TAG_COMPOUND);
            List<Point> polygon = new ArrayList<>(points.size());
            if (points.size() > MAX_VERTICES) return null;
            for (int i = 0; i < points.size(); i++) {
                CompoundTag point = points.getCompound(i);
                polygon.add(new Point(point.getInt("x"), point.getInt("z")));
            }
            return new NamedRegion(tag.getString("id"), tag.getString("dim"),
                    tag.getString("name"), tag.getInt("type"), tag.getInt("color"), tag.getString("note"), polygon);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
