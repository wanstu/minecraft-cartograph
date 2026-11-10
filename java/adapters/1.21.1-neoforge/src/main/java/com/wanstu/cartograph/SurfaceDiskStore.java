package com.wanstu.cartograph;

import com.wanstu.cartograph.world.McaChunkReader;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Authoritative-side derived terrain cache. Only raw actual 16x16 surface
 * samples, NEVER atlas ink/paid/colored masks. Uses one small compressed file
 * per saved chunk, grouped by region. Every load checks the original MCA
 * chunk's timestamp, so offline world changes cannot silently keep old pixels.
 * Call only from disk worker threads, never from render/server tick.
 */
final class SurfaceDiskStore {
    private static final int MAGIC = 0x43545333; // CTS3
    private static final int MAX_STRUCTURES = 64;
    private SurfaceDiskStore() {}

    private static Path file(SurfaceSampler.SavedRequest request) {
        int rx = Math.floorDiv(request.chunkX(), 32), rz = Math.floorDiv(request.chunkZ(), 32);
        return request.regionDir().resolveSibling("cartograph-surface-v3")
                .resolve("r." + rx + "." + rz)
                .resolve("c." + request.chunkX() + "." + request.chunkZ() + ".gz");
    }

    static SurfaceSampler.SurfaceTile read(SurfaceSampler.SavedRequest request) {
        Path path = file(request);
        if (!Files.isRegularFile(path)) return null;
        try {
            int stamp = McaChunkReader.chunkTimestamp(request.regionDir(), request.chunkX(), request.chunkZ());
            if (stamp == 0) return null;
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
                if (in.readInt() != MAGIC || in.readInt() != request.chunkX()
                        || in.readInt() != request.chunkZ() || in.readInt() != stamp) return null;
                byte[] colors = in.readNBytes(256);
                byte[] features = in.readNBytes(256);
                short[] heights = new short[256];
                for (int i = 0; i < heights.length; i++) heights[i] = in.readShort();
                byte[] depths = in.readNBytes(256);
                if (colors.length != 256 || features.length != 256 || depths.length != 256) return null;
                int count = in.readUnsignedByte();
                if (count > MAX_STRUCTURES) return null;
                List<SurfaceStructure> structures = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String type = in.readUTF();
                    int x = in.readInt(), z = in.readInt();
                    if (type.length() > 64) return null;
                    structures.add(new SurfaceStructure(type, x, z));
                }
                if (in.read() != -1) return null;
                if (McaChunkReader.chunkTimestamp(request.regionDir(),
                        request.chunkX(), request.chunkZ()) != stamp) return null;
                return new SurfaceSampler.SurfaceTile(colors, features, heights, depths, structures);
            }
        } catch (IOException | RuntimeException invalidOrOld) {
            return null; // the real FULL MCA chunk remains authoritative
        }
    }

    static void write(SurfaceSampler.SavedRequest request, SurfaceSampler.SurfaceTile tile) {
        if (tile == null) return;
        Path dest = file(request);
        Path temp = null;
        try {
            int stamp = McaChunkReader.chunkTimestamp(request.regionDir(), request.chunkX(), request.chunkZ());
            if (stamp == 0) return;
            Files.createDirectories(dest.getParent());
            temp = Files.createTempFile(dest.getParent(), "tile-", ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                out.writeInt(request.chunkX()); out.writeInt(request.chunkZ());
                out.writeInt(stamp);
                out.write(tile.colors()); out.write(tile.features());
                for (short height : tile.heights()) out.writeShort(height);
                out.write(tile.waterDepths());
                int count = Math.min(MAX_STRUCTURES, tile.structures().size());
                out.writeByte(count);
                for (int i = 0; i < count; i++) {
                    SurfaceStructure marker = tile.structures().get(i);
                    out.writeUTF(marker.type()); out.writeInt(marker.x()); out.writeInt(marker.z());
                }
            }
            try {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Cache is best-effort. Never break game or prevent real sampling.
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    static void invalidate(SurfaceSampler.SavedRequest request) {
        try { Files.deleteIfExists(file(request)); } catch (IOException ignored) {}
    }

    static boolean equal(SurfaceSampler.SurfaceTile a, SurfaceSampler.SurfaceTile b) {
        return a != null && b != null
                && Arrays.equals(a.colors(), b.colors())
                && Arrays.equals(a.features(), b.features())
                && Arrays.equals(a.heights(), b.heights())
                && Arrays.equals(a.waterDepths(), b.waterDepths())
                && a.structures().equals(b.structures());
    }
}
