package com.wanstu.cartographv3;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class RegionNbtReader {
    private RegionNbtReader() {}

    static Optional<DataInputStream> openChunk(Path regionDirectory, int chunkX, int chunkZ)
            throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        Path file = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) return Optional.empty();

        try (RandomAccessFile region = new RandomAccessFile(file.toFile(), "r")) {
            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            long tableOffset = (long) (localX + localZ * 32) * 4L;
            if (region.length() < tableOffset + 4) return Optional.empty();
            region.seek(tableOffset);
            int location = region.readInt();
            int sector = location >>> 8;
            int sectors = location & 0xff;
            if (sector < 2 || sectors == 0) return Optional.empty();

            long chunkOffset = (long) sector * 4096L;
            if (chunkOffset + 5 > region.length()) return Optional.empty();
            region.seek(chunkOffset);
            int length = region.readInt();
            if (length <= 1 || length > sectors * 4096 - 4
                    || chunkOffset + 4L + length > region.length())
                throw new IOException("Invalid MCA chunk length");
            int compression = region.readUnsignedByte();
            byte[] compressed = new byte[length - 1];
            region.readFully(compressed);

            InputStream raw = new ByteArrayInputStream(compressed);
            InputStream decoded = switch (compression) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new IOException("Unsupported MCA compression " + compression);
            };
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            decoded.transferTo(copy);
            return Optional.of(new DataInputStream(
                    new ByteArrayInputStream(copy.toByteArray())));
        }
    }
}
