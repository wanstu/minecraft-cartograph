package com.wanstu.cartograph;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Render-side persistent page cache, never authoritative for survey credits.
 * Stores only server-issued, fully loaded map pixels; world + atlas + dimension
 * are isolated by a collision-resistant on-disk namespace.
 * All I/O is off render thread; GPU textures are always recreated on render.
 */
final class ClientPageStore {
    record Key(String world, String atlas, String dimension, int x, int z) {}
    record Loaded(Key key, MapNetwork.Page page) {}
    private static final int MAGIC = 0x43545035; // CTP5
    private static final int MAX_PENDING = 192;
    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, job -> {
        Thread t = new Thread(job, "cartograph-client-page-" + THREADS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final Set<Key> attempted = ConcurrentHashMap.newKeySet();
    private static final Set<Key> pending = ConcurrentHashMap.newKeySet();
    private static final Queue<Loaded> received = new ConcurrentLinkedQueue<>();
    private static final ConcurrentMap<Key, Long> latestWrite = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong writeId = new java.util.concurrent.atomic.AtomicLong();
    private static final Object[] WRITE_LOCKS = new Object[64];
    static { for (int i = 0; i < WRITE_LOCKS.length; i++) WRITE_LOCKS[i] = new Object(); }
    private ClientPageStore() {}

    static String namespace(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Path file(Path gameRoot, Key k) {
        return gameRoot.resolve("cartograph").resolve("page-cache-v5")
                .resolve(k.world).resolve(namespace(k.atlas)).resolve(namespace(k.dimension))
                .resolve("p." + k.x + "." + k.z + ".gz");
    }

    static void request(Path gameRoot, Key key) {
        if (pending.size() >= MAX_PENDING || !attempted.add(key) || !pending.add(key)) return;
        IO.execute(() -> {
            MapNetwork.Page page = null;
            try { page = read(file(gameRoot, key), key); }
            finally {
                received.add(new Loaded(key, page));
                pending.remove(key);
            }
        });
    }

    static Loaded poll() { return received.poll(); }

    static void save(Path gameRoot, Key key, MapNetwork.Page page) {
        if (!page.complete() || page.painted() < 1) return;
        long token = writeId.incrementAndGet();
        latestWrite.put(key, token);
        IO.execute(() -> {
            Object lock = WRITE_LOCKS[Math.floorMod(key.hashCode(), WRITE_LOCKS.length)];
            synchronized (lock) {
                // An older disk-writing task may not replace a newer refresh.
                if (Objects.equals(latestWrite.get(key), token)) write(file(gameRoot, key), page);
            }
        });
    }

    static void reset() {
        attempted.clear();
        received.clear();
        // In-flight old-world jobs are allowed to finish; the client verifies
        // world/atlas/dimension when accepting, never mixes their pixels.
    }

    static void write(Path dest, MapNetwork.Page p) {
        Path temp = null;
        try {
            Files.createDirectories(dest.getParent());
            temp = Files.createTempFile(dest.getParent(), "page-", ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeUTF(p.atlasId()); out.writeUTF(p.dimension());
                out.writeInt(p.pageX()); out.writeInt(p.pageZ());
                out.write(p.pixels()); out.write(p.features());
                out.writeInt(p.heights().length);
                for (short h : p.heights()) out.writeShort(h);
                out.write(p.waterDepths());
                out.writeLong(p.surveyedChunks()); out.writeLong(p.coloredChunks());
                out.writeInt(p.inkUnits()); out.writeInt(p.pigmentUnits()); out.writeInt(p.glowUnits());
                out.writeBoolean(p.compass()); out.writeBoolean(p.inRange());
                out.writeInt(p.scanned()); out.writeInt(p.generated()); out.writeInt(p.painted());
                out.writeUTF(p.anchorDimension()); out.writeInt(p.anchorX()); out.writeInt(p.anchorZ());
                out.writeInt(p.totalPages()); out.writeInt(p.sheetSideBlocks());
                out.writeInt(p.totalSurveyed()); out.writeInt(p.totalColored());
                out.writeInt(p.markers().size());
                for (AtlasMarker m : p.markers()) {
                    out.writeUTF(m.id()); out.writeUTF(m.dimension());
                    out.writeInt(m.x()); out.writeInt(m.z()); out.writeUTF(m.name());
                    out.writeInt(m.icon()); out.writeInt(m.color());
                    out.writeBoolean(m.glowing()); out.writeBoolean(m.glowEverPaid());
                }
                out.writeInt(p.structures().size());
                for (SurfaceStructure f : p.structures()) {
                    out.writeUTF(f.type()); out.writeInt(f.x()); out.writeInt(f.z());
                }
            }
            try {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Persistent cache is optional. Never block UI or consume materials.
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    static MapNetwork.Page read(Path path, Key key) {
        if (!Files.isRegularFile(path)) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (in.readInt() != MAGIC) return null;
            String atlas = in.readUTF(), dim = in.readUTF();
            int x = in.readInt(), z = in.readInt();
            if (!atlas.equals(key.atlas) || !dim.equals(key.dimension) || x != key.x || z != key.z) return null;
            byte[] pixels = in.readNBytes(MapNetwork.Page.PIXELS);
            byte[] features = in.readNBytes(MapNetwork.Page.PIXELS);
            if (pixels.length != MapNetwork.Page.PIXELS || features.length != MapNetwork.Page.PIXELS) return null;
            int heightsCount = in.readInt();
            if (heightsCount != 0 && heightsCount != MapNetwork.Page.PIXELS) return null;
            short[] heights = new short[heightsCount];
            for (int i = 0; i < heights.length; i++) heights[i] = in.readShort();
            byte[] depths = in.readNBytes(heightsCount);
            if (depths.length != heightsCount) return null;
            long surveyed = in.readLong(), colored = in.readLong();
            int ink = in.readInt(), pigment = in.readInt(), glow = in.readInt();
            boolean compass = in.readBoolean(), inRange = in.readBoolean();
            int scanned = in.readInt(), generated = in.readInt(), painted = in.readInt();
            String anchorDim = in.readUTF();
            int anchorX = in.readInt(), anchorZ = in.readInt();
            int pages = in.readInt(), sideBlocks = in.readInt();
            int totalSurveyed = in.readInt(), totalColored = in.readInt();
            if (scanned < 0 || scanned > 64 || generated < 0 || generated > 64
                    || painted < 0 || painted > 64 || pages < 1 || pages > AtlasState.MAX_PAGES
                    || !com.wanstu.cartograph.core.MapSheetCoverage.valid(sideBlocks)) return null;
            int count = in.readInt();
            if (count < 0 || count > 128) return null;
            List<AtlasMarker> markers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                markers.add(new AtlasMarker(in.readUTF(), in.readUTF(),
                        in.readInt(), in.readInt(), in.readUTF(), in.readInt(), in.readInt(),
                        in.readBoolean(), in.readBoolean()));
            }
            int structureCount = in.readInt();
            if (structureCount < 0 || structureCount > 64) return null;
            List<SurfaceStructure> structures = new ArrayList<>(structureCount);
            for (int i = 0; i < structureCount; i++)
                structures.add(new SurfaceStructure(in.readUTF(), in.readInt(), in.readInt()));
            if (in.read() != -1) return null;
            return new MapNetwork.Page(atlas, dim, x, z, pixels, features, heights, depths,
                    surveyed, colored, ink, pigment, glow, compass, inRange,
                    true, false, scanned, generated, painted,
                    anchorDim, anchorX, anchorZ, pages, sideBlocks, totalSurveyed, totalColored,
                    List.copyOf(markers), List.copyOf(structures));
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }
}
