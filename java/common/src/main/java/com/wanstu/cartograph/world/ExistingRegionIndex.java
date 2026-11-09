package com.wanstu.cartograph.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only MCA persisted-chunk index; never calls Level#getChunk, never
 * loads a chunk, never uses a seed or predicts geometry. A valid entry is
 * evidence that serialized chunk bytes exist, not proof of surface geometry.
 * Region path is passed explicitly to avoid cross-dimension leakage.
 */
public final class ExistingRegionIndex {
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_BYTES = 4096;

    public record ChunkLocation(int x, int z) {}

    private ExistingRegionIndex() {}

    public static boolean isPersisted(Path regionDirectory, int chunkX, int chunkZ) throws IOException {
        int rx = Math.floorDiv(chunkX, 32), rz = Math.floorDiv(chunkZ, 32);
        int localX = Math.floorMod(chunkX, 32), localZ = Math.floorMod(chunkZ, 32);
        Path region = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(region)) return false;
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
            return validEntry(channel, localZ * 32 + localX);
        }
    }

    public static List<ChunkLocation> listPersisted(Path regionDirectory, int rx, int rz) throws IOException {
        Path file = regionDirectory.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.isRegularFile(file)) return List.of();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < HEADER_BYTES) return List.of();
            List<ChunkLocation> out = new ArrayList<>();
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    if (validEntry(channel, z * 32 + x)) out.add(new ChunkLocation(rx * 32 + x, rz * 32 + z));
                }
            }
            return List.copyOf(out);
        }
    }

    private static boolean validEntry(FileChannel channel, int index) throws IOException {
        if (channel.size() < HEADER_BYTES) return false;
        ByteBuffer entry = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        if (channel.read(entry, (long) index * 4) != 4) return false;
        entry.flip();
        int encoded = entry.getInt();
        int sector = encoded >>> 8;
        int sectors = encoded & 0xff;
        if (sector < 2 || sectors == 0) return false;
        long pos = (long) sector * SECTOR_BYTES;
        if (pos + 5 > channel.size()) return false;
        ByteBuffer length = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        if (channel.read(length, pos) != 4) return false;
        length.flip();
        int payload = length.getInt();
        return payload > 1 && (long) payload + 4 <= (long) sectors * SECTOR_BYTES
                && pos + 4L + payload <= channel.size();
    }
}
