package com.wanstu.cartograph.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Raw, read-only world-save region access. It never creates a region file,
 * touches Minecraft's chunk load API, or derives terrain from a world seed.
 * Fully-generated status and NBT coordinates MUST be checked by the adapter.
 */
public final class McaChunkReader {
    private static final int SECTOR = 4096;
    private static final int MAX_NBT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COMPRESSED_BYTES = 2 * 1024 * 1024;
    private McaChunkReader() {}

    /** Last saved chunk revision from the MCA header (4 bytes per chunk).
     * The region file may change without this particular chunk changing. */
    public static int chunkTimestamp(Path regionDir, int chunkX, int chunkZ) throws IOException {
        int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
        int lx = Math.floorMod(chunkX, 32), lz = Math.floorMod(chunkZ, 32);
        Path region = regionDir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(region)) return 0;
        try (FileChannel ch = FileChannel.open(region, StandardOpenOption.READ)) {
            if (ch.size() < SECTOR * 2L) return 0;
            long index = (long) (lz * 32 + lx) * 4;
            ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            if (ch.read(header, index) != 4) return 0;
            header.flip();
            int location = header.getInt();
            // No allocated region sector: old surface tiles must not resurrect
            // chunks that were removed from the world file.
            if ((location >>> 8) < 2 || (location & 255) == 0) return 0;
            ByteBuffer stamp = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            if (ch.read(stamp, SECTOR + index) != 4) return 0;
            stamp.flip();
            return stamp.getInt();
        }
    }

    public static Optional<byte[]> readNbt(Path regionDir, int chunkX, int chunkZ) throws IOException {
        int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
        int lx = Math.floorMod(chunkX, 32), lz = Math.floorMod(chunkZ, 32);
        Path region = regionDir.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(region)) return Optional.empty();
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
            if (channel.size() < SECTOR * 2L) return Optional.empty();
            ByteBuffer header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            if (channel.read(header, (long) (lz * 32 + lx) * 4) != 4) return Optional.empty();
            header.flip();
            int entry = header.getInt();
            int sector = entry >>> 8, count = entry & 255;
            if (sector < 2 || count == 0) return Optional.empty();
            long offset = (long) sector * SECTOR;
            if (offset + 5 > channel.size()) return Optional.empty();
            ByteBuffer length = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            if (channel.read(length, offset) != 5) return Optional.empty();
            length.flip();
            int total = length.getInt();
            int mode = Byte.toUnsignedInt(length.get());
            // External .mcc entries have bit 7 set, and must be handled separately.
            if ((mode & 0x80) != 0) return Optional.empty();
            if (total < 2 || total > MAX_COMPRESSED_BYTES + 1) return Optional.empty();
            if ((long) total + 4 > (long) count * SECTOR || offset + 4L + total > channel.size())
                return Optional.empty();
            ByteBuffer payload = ByteBuffer.allocate(total - 1);
            int received = 0;
            while (payload.hasRemaining()) {
                int n = channel.read(payload, offset + 5 + received);
                if (n <= 0) return Optional.empty();
                received += n;
            }
            byte[] bytes = payload.array();
            try (InputStream base = new ByteArrayInputStream(bytes);
                 InputStream decoded = switch (mode) {
                     case 1 -> new GZIPInputStream(base);
                     case 2 -> new InflaterInputStream(base);
                     case 3 -> base;
                     default -> null; // LZ4/unknown compression: unsupported, never invent map pixels.
                 }) {
                if (decoded == null) return Optional.empty();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = decoded.read(buffer)) >= 0) {
                    if ((long) output.size() + n > MAX_NBT_BYTES)
                        throw new IOException("Chunk uncompressed NBT exceeds safe limit");
                    output.write(buffer, 0, n);
                }
                return Optional.of(output.toByteArray());
            }
        }
    }
}
