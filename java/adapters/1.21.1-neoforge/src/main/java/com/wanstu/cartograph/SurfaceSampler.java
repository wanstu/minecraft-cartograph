package com.wanstu.cartograph;

import com.wanstu.cartograph.world.McaChunkReader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Samples ACTUAL existing surface blocks only. Loaded chunks use getChunkNow()
 * (never getChunk()); unloaded ones are decoded from a confirmed saved MCA
 * FULL chunk. Missing/incomplete chunks remain blank. Seed is never inspected.
 * Does not scan structure starts or underground rooms.
 */
public final class SurfaceSampler {
    private SurfaceSampler() {}

    private static Path regionDir(ServerLevel level) {
        Path world = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation id = level.dimension().location();
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) return world.resolve("region");
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) return world.resolve("DIM-1/region");
        if (level.dimension() == net.minecraft.world.level.Level.END) return world.resolve("DIM1/region");
        return world.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");
    }

    public static Optional<byte[]> surface(ServerLevel level, int chunkX, int chunkZ) {
        try {
            LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (loaded != null && loaded.getPersistedStatus().isOrAfter(ChunkStatus.FULL)) {
                return Optional.of(sampleLoaded(loaded, chunkX, chunkZ));
            }
            Optional<byte[]> raw = McaChunkReader.readNbt(regionDir(level), chunkX, chunkZ);
            if (raw.isEmpty()) return Optional.empty();
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw.get()))) {
                CompoundTag nbt = NbtIo.read(in, NbtAccounter.create(8 * 1024 * 1024));
                if (!nbt.getString("Status").equals("minecraft:full")
                        && !nbt.getString("Status").equals("full")) return Optional.empty();
                if (nbt.getInt("xPos") != chunkX || nbt.getInt("zPos") != chunkZ) return Optional.empty();
                return Optional.ofNullable(sampleSaved(level, nbt));
            }
        } catch (Exception badOrUnsupportedSave) {
            // Unsupported compression/chunk data must never reveal inferred terrain.
            return Optional.empty();
        }
    }

    private static byte[] sampleLoaded(LevelChunk chunk, int chunkX, int chunkZ) {
        byte[] result = new byte[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            pos.set(chunkX * 16 + x, y, chunkZ * 16 + z);
            result[x + z * 16] = sampleColor(chunk.getBlockState(pos), pos);
        }
        return result;
    }

    private static byte sampleColor(BlockState block, BlockPos pos) {
        try {
            MapColor color = block.getMapColor(null, pos);
            return (byte) (color.id + 1); // zero reserved for unmapped tiles
        } catch (Exception worldDependentColor) {
            return (byte) (MapColor.STONE.id + 1);
        }
    }

    /** Version-specific decoder: 1.21.1 saved FULL chunk NBT and local block palette. */
    private static byte[] sampleSaved(ServerLevel level, CompoundTag nbt) {
        CompoundTag maps = nbt.getCompound("Heightmaps");
        long[] height = maps.getLongArray("WORLD_SURFACE");
        int bits = 32 - Integer.numberOfLeadingZeros(level.getHeight());
        int expectedLongs = (256 + (64 / bits) - 1) / (64 / bits);
        if (height.length != expectedLongs) return null;
        SimpleBitStorage surface = new SimpleBitStorage(bits, 256, height);

        Map<Integer, BlockState[]> palettes = new HashMap<>();
        Map<Integer, SimpleBitStorage> indices = new HashMap<>();
        ListTag sections = nbt.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int sectionY = section.getByte("Y");
            CompoundTag blocks = section.getCompound("block_states");
            ListTag paletteList = blocks.getList("palette", Tag.TAG_COMPOUND);
            if (paletteList.isEmpty()) continue;
            BlockState[] states = new BlockState[paletteList.size()];
            for (int j = 0; j < states.length; j++) {
                // Decode actual saved palette IDs. Unknown mods use a conservative
                // fallback color, not a guessed structure or seed-derived block.
                states[j] = BlockState.CODEC.parse(NbtOps.INSTANCE, paletteList.get(j))
                        .result().orElse(Blocks.STONE.defaultBlockState());
            }
            palettes.put(sectionY, states);
            if (states.length > 1) {
                int blockBits = Math.max(4, 32 - Integer.numberOfLeadingZeros(states.length - 1));
                long[] packed = blocks.getLongArray("data");
                int longs = (4096 + (64 / blockBits) - 1) / (64 / blockBits);
                if (packed.length != longs) return null;
                indices.put(sectionY, new SimpleBitStorage(blockBits, 4096, packed));
            }
        }
        byte[] result = new byte[256];
        int minBuild = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = x + z * 16;
            int y = minBuild + surface.get(i) - 1;
            int sectionY = Math.floorDiv(y, 16);
            BlockState[] palette = palettes.get(sectionY);
            if (palette == null) return null;
            SimpleBitStorage storage = indices.get(sectionY);
            int blockIndex = storage == null ? 0 : storage.get(((y & 15) << 8) | (z << 4) | x);
            if (blockIndex < 0 || blockIndex >= palette.length) return null;
            pos.set(nbt.getInt("xPos") * 16 + x, y, nbt.getInt("zPos") * 16 + z);
            result[i] = sampleColor(palette[blockIndex], pos);
        }
        return result;
    }
}
