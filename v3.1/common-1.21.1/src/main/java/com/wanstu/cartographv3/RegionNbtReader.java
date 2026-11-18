package com.wanstu.cartographv3;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class RegionNbtReader {
    private static final int SECTOR_BYTES = 4096;
    private static final int EXTERNAL_STREAM_FLAG = 0x80;
    private static final int MAX_NBT_BYTES = 16 * 1024 * 1024;

    private RegionNbtReader() {}

    static Optional<CompoundTag> readChunk(Path regionDirectory, int chunkX, int chunkZ)
            throws IOException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        Path file = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) return Optional.empty();

        int compression;
        byte[] compressed = null;
        try (RandomAccessFile region = new RandomAccessFile(file.toFile(), "r")) {
            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            long tableOffset = (long) (localX + localZ * 32) * Integer.BYTES;
            if (region.length() < tableOffset + Integer.BYTES) return Optional.empty();
            region.seek(tableOffset);
            int location = region.readInt();
            int sector = location >>> 8;
            int sectors = location & 0xff;
            if (sector < 2 || sectors == 0) return Optional.empty();

            long chunkOffset = (long) sector * SECTOR_BYTES;
            if (chunkOffset + Integer.BYTES + 1 > region.length()) return Optional.empty();
            region.seek(chunkOffset);
            int length = region.readInt();
            if (length < 1 || length > sectors * SECTOR_BYTES - Integer.BYTES
                    || chunkOffset + Integer.BYTES + length > region.length()) {
                throw new IOException("Invalid MCA chunk length");
            }
            compression = region.readUnsignedByte();
            if ((compression & EXTERNAL_STREAM_FLAG) != 0) {
                if (length != 1) throw new IOException("Invalid external MCA chunk header");
            } else {
                if (length == 1) throw new IOException("Missing MCA chunk data");
                compressed = new byte[length - 1];
                region.readFully(compressed);
            }
        }

        int versionId = compression & ~EXTERNAL_STREAM_FLAG;
        RegionFileVersion version = RegionFileVersion.fromId(versionId);
        if (version == null || version == RegionFileVersion.VERSION_CUSTOM) {
            throw new IOException("Unsupported MCA compression " + versionId);
        }

        try (InputStream raw = compressed == null
                     ? Files.newInputStream(regionDirectory.resolve("c." + chunkX + "." + chunkZ + ".mcc"))
                     : new ByteArrayInputStream(compressed);
             DataInputStream input = new DataInputStream(version.wrap(raw))) {
            CompoundTag nbt = NbtIo.read(input, NbtAccounter.create(MAX_NBT_BYTES));
            String status = nbt.getString("Status");
            if (!("full".equals(status) || "minecraft:full".equals(status))) {
                return Optional.empty();
            }
            if (nbt.getInt("xPos") != chunkX || nbt.getInt("zPos") != chunkZ) {
                throw new IOException("Chunk coordinate mismatch");
            }
            return Optional.of(nbt);
        }
    }
}
