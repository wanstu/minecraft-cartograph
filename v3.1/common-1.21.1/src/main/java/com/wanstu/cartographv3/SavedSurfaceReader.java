package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.*;

final class SavedSurfaceReader {
    private record Section(
            ListTag palette,
            BlockState[] states,
            SimpleBitStorage indices,
            int[] biomeIds,
            SimpleBitStorage biomeIndices) {
        BlockState at(int x, int y, int z) {
            int id = indices == null ? 0 : indices.get(((y & 15) << 8) | (z << 4) | x);
            BlockState state = states[id];
            if (state == null) {
                state = BlockState.CODEC.parse(NbtOps.INSTANCE, palette.getCompound(id))
                        .result().orElse(Blocks.STONE.defaultBlockState());
                states[id] = state;
            }
            return state;
        }

        int biomeIdAt(int x, int y, int z) {
            if (biomeIds == null || biomeIds.length == 0) return -1;
            int index = (((y >> 2) & 3) << 4) | ((z >> 2) << 2) | (x >> 2);
            int id = biomeIndices == null ? 0 : biomeIndices.get(index);
            return id >= 0 && id < biomeIds.length ? biomeIds[id] : -1;
        }
    }

    private SavedSurfaceReader() {}

    static Optional<SurfaceTile> fromSaved(CompoundTag nbt, String dimension,
                                           int chunkX, int chunkZ,
                                           int minBuildHeight, int worldHeight,
                                           Registry<Biome> biomeRegistry) {
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

            int paletteSize = paletteTag.size();

            SimpleBitStorage indices = null;
            if (paletteSize > 1) {
                int bits = Math.max(4,
                        32 - Integer.numberOfLeadingZeros(paletteSize - 1));
                long[] data = blockStates.getLongArray("data");
                int perLong = 64 / bits;
                int expected = (4096 + perLong - 1) / perLong;
                if (data.length != expected) return Optional.empty();
                indices = new SimpleBitStorage(bits, 4096, data);
            }

            int[] biomePaletteIds = null;
            SimpleBitStorage biomeIndices = null;
            CompoundTag biomes = section.getCompound("biomes");
            ListTag biomePalette = biomes.getList("palette", Tag.TAG_STRING);
            if (!biomePalette.isEmpty()) {
                biomePaletteIds = new int[biomePalette.size()];
                for (int p = 0; p < biomePaletteIds.length; p++) {
                    ResourceLocation id = ResourceLocation.tryParse(biomePalette.getString(p));
                    Biome biome = id == null ? null : biomeRegistry.get(id);
                    biomePaletteIds[p] = biome == null ? -1 : biomeRegistry.getId(biome);
                }

                if (biomePaletteIds.length > 1) {
                    int bits = 32 - Integer.numberOfLeadingZeros(biomePaletteIds.length - 1);
                    long[] data = biomes.getLongArray("data");
                    int perLong = 64 / bits;
                    int expected = (64 + perLong - 1) / perLong;
                    if (data.length == expected) {
                        biomeIndices = new SimpleBitStorage(bits, 64, data);
                    }
                }
            }

            sections.put((int) section.getByte("Y"),
                    new Section(paletteTag, new BlockState[paletteSize],
                            indices, biomePaletteIds, biomeIndices));
        }

        int[] colors = new int[SurfaceTile.PIXELS];
        short[] heights = new short[SurfaceTile.PIXELS];
        int[] stateIds = new int[SurfaceTile.PIXELS];
        int[] biomeIds = new int[SurfaceTile.PIXELS];
        short[] surfaceHeights = new short[SurfaceTile.PIXELS];
        short[] reliefHeights = new short[SurfaceTile.PIXELS];
        byte[] waterDepths = new byte[SurfaceTile.PIXELS];
        Arrays.fill(heights, SurfaceColors.UNKNOWN_HEIGHT);
        Arrays.fill(stateIds, -1);
        Arrays.fill(biomeIds, -1);
        Arrays.fill(surfaceHeights, SurfaceTile.UNKNOWN_HEIGHT);
        Arrays.fill(reliefHeights, SurfaceTile.UNKNOWN_HEIGHT);
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
                    stateIds[index] = Block.getId(state);
                    biomeIds[index] = section.biomeIdAt(x, y, z);
                    surfaceHeights[index] = (short) y;
                    reliefHeights[index] = (short) reliefY;
                    waterDepths[index] = (byte) Math.min(255, waterDepth);
                    break;
                }
            }
        }

        return Optional.of(new SurfaceTile(
                new ChunkKey(dimension, chunkX, chunkZ),
                SurfaceColors.shade(colors, heights),
                stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths));
    }
}
