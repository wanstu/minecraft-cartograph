package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

final class SavedSurfaceReader {
    private record Section(BlockState[] states, SimpleBitStorage indices) {
        BlockState at(int x, int y, int z) {
            int id = indices == null ? 0 : indices.get(((y & 15) << 8) | (z << 4) | x);
            return states[id];
        }
    }

    private SavedSurfaceReader() {}

    static Optional<SurfaceTile> read(Path regionDirectory, String dimension,
                                      int chunkX, int chunkZ,
                                      int minBuildHeight, int worldHeight) throws IOException {
        Optional<DataInputStream> opened = RegionNbtReader.openChunk(regionDirectory, chunkX, chunkZ);
        if (opened.isEmpty()) return Optional.empty();

        try (DataInputStream input = opened.get()) {
            CompoundTag nbt = NbtIo.read(input, NbtAccounter.create(16 * 1024 * 1024));
            String status = nbt.getString("Status");
            if (!("full".equals(status) || "minecraft:full".equals(status)))
                return Optional.empty();
            if (nbt.getInt("xPos") != chunkX || nbt.getInt("zPos") != chunkZ)
                throw new IOException("Chunk coordinate mismatch");

            CompoundTag heightmaps = nbt.getCompound("Heightmaps");
            long[] packedHeights = heightmaps.getLongArray("WORLD_SURFACE");
            int heightBits = 32 - Integer.numberOfLeadingZeros(worldHeight);
            int valuesPerLong = 64 / heightBits;
            int expectedHeightLongs = (256 + valuesPerLong - 1) / valuesPerLong;
            if (packedHeights.length != expectedHeightLongs) return Optional.empty();
            SimpleBitStorage heightmap =
                    new SimpleBitStorage(heightBits, 256, packedHeights);

            SimpleBitStorage oceanFloor = null;
            long[] packedOceanFloor = heightmaps.getLongArray("OCEAN_FLOOR");
            if (packedOceanFloor.length == expectedHeightLongs) {
                oceanFloor = new SimpleBitStorage(heightBits, 256, packedOceanFloor);
            }

            SimpleBitStorage reliefHeightmap = null;
            long[] packedRelief = heightmaps.getLongArray("MOTION_BLOCKING_NO_LEAVES");
            if (packedRelief.length == expectedHeightLongs) {
                reliefHeightmap = new SimpleBitStorage(heightBits, 256, packedRelief);
            }

            Map<Integer, Section> sections = new HashMap<>();
            ListTag rawSections = nbt.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < rawSections.size(); i++) {
                CompoundTag section = rawSections.getCompound(i);
                CompoundTag blockStates = section.getCompound("block_states");
                ListTag paletteTag = blockStates.getList("palette", Tag.TAG_COMPOUND);
                if (paletteTag.isEmpty()) continue;

                BlockState[] palette = new BlockState[paletteTag.size()];
                for (int p = 0; p < palette.length; p++) {
                    palette[p] = BlockState.CODEC.parse(NbtOps.INSTANCE, paletteTag.getCompound(p))
                            .result().orElse(Blocks.STONE.defaultBlockState());
                }

                SimpleBitStorage indices = null;
                if (palette.length > 1) {
                    int bits = Math.max(4,
                            32 - Integer.numberOfLeadingZeros(palette.length - 1));
                    long[] data = blockStates.getLongArray("data");
                    int perLong = 64 / bits;
                    int expected = (4096 + perLong - 1) / perLong;
                    if (data.length != expected) return Optional.empty();
                    indices = new SimpleBitStorage(bits, 4096, data);
                }
                sections.put((int) section.getByte("Y"), new Section(palette, indices));
            }

            int[] colors = new int[SurfaceTile.PIXELS];
            short[] heights = new short[SurfaceTile.PIXELS];
            Arrays.fill(heights, SurfaceColors.UNKNOWN_HEIGHT);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = z * 16 + x;
                    int stored = heightmap.get(index);
                    if (stored < 0 || stored > worldHeight) continue;
                    int topY = minBuildHeight + stored - 1;

                    for (int y = topY; y >= minBuildHeight; y--) {
                        Section section = sections.get(Math.floorDiv(y, 16));
                        if (section == null) continue;
                        BlockState state = section.at(x, y, z);
                        pos.set(chunkX * 16 + x, y, chunkZ * 16 + z);
                        MapColor color;
                        try {
                            color = state.getMapColor(EmptyBlockGetter.INSTANCE, pos);
                        } catch (RuntimeException unsupported) {
                            color = MapColor.STONE;
                        }
                        if (color == MapColor.NONE) continue;
                        int waterDepth = 0;
                        if (color == MapColor.WATER && oceanFloor != null) {
                            int floorStored = oceanFloor.get(index);
                            if (floorStored > 0 && floorStored <= worldHeight) {
                                int floorY = minBuildHeight + floorStored - 1;
                                waterDepth = Math.max(0, y - floorY);
                            }
                        }
                        colors[index] = SurfaceColors.surfaceColor(
                                color, waterDepth, chunkX * 16 + x, chunkZ * 16 + z);
                        int reliefY = y;
                        if (reliefHeightmap != null) {
                            int reliefStored = reliefHeightmap.get(index);
                            if (reliefStored > 0 && reliefStored <= worldHeight) {
                                reliefY = minBuildHeight + reliefStored - 1;
                            }
                        }
                        heights[index] = (short) reliefY;
                        break;
                    }
                }
            }

            return Optional.of(new SurfaceTile(
                    new ChunkKey(dimension, chunkX, chunkZ),
                    SurfaceColors.shade(colors, heights)));
        }
    }
}
