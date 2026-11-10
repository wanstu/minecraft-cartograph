package com.wanstu.cartograph;

import com.wanstu.cartograph.core.TerrainRelief;
import com.wanstu.cartograph.world.McaChunkReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * No seed, no forced chunk load. All pixels/roads/rails and structure markers
 * derive from real loaded FULL chunks or previously saved FULL chunk NBT only.
 * Underground materials are deliberately never scanned or drawn.
 */
public final class SurfaceSampler {
    private SurfaceSampler() {}
    /**
     * Snapshot of saved-read parameters captured on the server thread.
     * Disk workers must not access a live ServerLevel/LevelChunk.
     */
    public record SavedRequest(Path regionDir, int chunkX, int chunkZ,
                               int minBuildHeight, int worldHeight) {
        public SavedRequest {
            if (regionDir == null || worldHeight < 16 || worldHeight > 4096)
                throw new IllegalArgumentException("Invalid saved chunk request");
        }
    }

    /** 0 no feature, 1 natural dirt-path road, 2 vanilla railway, 3 Create railway. */
    public record SurfaceTile(byte[] colors, byte[] features, short[] heights,
                              byte[] waterDepths, List<SurfaceStructure> structures) {
        public SurfaceTile {
            if (colors.length != 256 || features.length != 256
                    || heights.length != 256 || waterDepths.length != 256)
                throw new IllegalArgumentException("Expected 16x16 genuine surface samples");
            structures = List.copyOf(structures);
        }
    }

    static Path regionDir(ServerLevel level) {
        Path world = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation id = level.dimension().location();
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) return world.resolve("region");
        if (level.dimension() == net.minecraft.world.level.Level.NETHER) return world.resolve("DIM-1/region");
        if (level.dimension() == net.minecraft.world.level.Level.END) return world.resolve("DIM1/region");
        return world.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");
    }

    public static SavedRequest request(ServerLevel level, int chunkX, int chunkZ) {
        return new SavedRequest(regionDir(level), chunkX, chunkZ,
                level.getMinBuildHeight(), level.getHeight());
    }

    /**
     * Main server thread ONLY. Querying getChunkNow does not load/generate
     * chunks; both the chunk and its world-backed map color are sampled here.
     */
    public static Optional<SurfaceTile> loaded(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk == null ? Optional.empty() :
                Optional.of(sampleLoaded(level, chunk, chunkX, chunkZ));
    }

    /**
     * Disk worker ONLY: region I/O, NBT parse, block palettes and pixel
     * conversion use captured immutable parameters; never touch ServerLevel.
     */
    public static Optional<SurfaceTile> saved(SavedRequest request) {
        return readFromDisk(request, false);
    }

    /** Explicit full refresh bypasses derived caches, never ignores saved chunks. */
    public static Optional<SurfaceTile> savedFresh(SavedRequest request) {
        return readFromDisk(request, true);
    }

    private static Optional<SurfaceTile> readFromDisk(SavedRequest request, boolean force) {
        SurfaceTile existing = force ? null : SurfaceDiskStore.read(request);
        if (existing != null) return Optional.of(existing);
        try {
            int beforeStamp = McaChunkReader.chunkTimestamp(
                    request.regionDir(), request.chunkX(), request.chunkZ());
            Optional<byte[]> raw = McaChunkReader.readNbt(
                    request.regionDir(), request.chunkX(), request.chunkZ());
            if (raw.isEmpty()) return Optional.empty();
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw.get()))) {
                CompoundTag nbt = NbtIo.read(in, NbtAccounter.create(8 * 1024 * 1024));
                String status = nbt.getString("Status");
                if (!status.equals("minecraft:full") && !status.equals("full"))
                    return Optional.empty();
                if (nbt.getInt("xPos") != request.chunkX()
                        || nbt.getInt("zPos") != request.chunkZ()) return Optional.empty();
                SurfaceTile tile = sampleSaved(
                        request.minBuildHeight(), request.worldHeight(), nbt);
                if (McaChunkReader.chunkTimestamp(request.regionDir(),
                        request.chunkX(), request.chunkZ()) != beforeStamp) return Optional.empty();
                if (tile != null) SurfaceDiskStore.write(request, tile);
                return Optional.ofNullable(tile);
            }
        } catch (Exception malformedOrUnsupportedChunk) {
            // Unsupported/partial save is NOT generated terrain; never predict.
            return Optional.empty();
        }
    }

    private static SurfaceTile sampleLoaded(ServerLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        byte[] result = new byte[256], features = new byte[256], depths = new byte[256];
        short[] heights = new short[256];
        Arrays.fill(heights, TerrainRelief.UNKNOWN_HEIGHT);
        String[] surfaceNames = new String[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = chunk.getMinBuildHeight();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = x + z * 16;
            int wx = chunkX * 16 + x, wz = chunkZ * 16 + z;
            // ChunkAccess#getHeight already subtracts one from the heightmap's first free Y.
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = top; y >= minY; y--) {
                pos.set(wx, y, wz);
                BlockState block = chunk.getBlockState(pos);
                boolean water = isWater(block);
                byte color = water ? (byte) (MapColor.WATER.id + 1) : sampleColor(block, chunk, pos);
                if (y == top || color != 0) {
                    String key = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
                    if (features[i] == 0) features[i] = classifyFeature(key);
                    if (surfaceNames[i] == null) surfaceNames[i] = key;
                }
                if (color == 0) continue;
                result[i] = color;
                heights[i] = (short) y;
                if (water) {
                    depths[i] = waterDepth(y, minY,
                            below -> isWater(chunk.getBlockState(pos.set(wx, below, wz))));
                }
                break;
            }
        }
        List<SurfaceStructure> markers = new ArrayList<>();
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (var entry : chunk.getAllStarts().entrySet()) {
            var start = entry.getValue();
            if (start == null || !start.isValid()) continue;
            ResourceLocation id = structureRegistry.getKey(entry.getKey());
            if (id == null) continue;
            String type = id.toString();
            if (verifiedSurfaceStructure(type, surfaceNames, features)) {
                markers.add(new SurfaceStructure(type, chunkX * 16 + 8, chunkZ * 16 + 8));
                if (markers.size() >= 4) break;
            }
        }
        return new SurfaceTile(result, features, heights, depths, markers);
    }

    private static boolean isWater(BlockState block) {
        return !block.blocksMotion() && block.getFluidState().is(FluidTags.WATER);
    }

    private static byte waterDepth(int surfaceY, int minY, IntPredicate waterAt) {
        int depth = 1;
        while (depth < TerrainRelief.MAX_WATER_DEPTH && surfaceY - depth >= minY
                && waterAt.test(surfaceY - depth)) depth++;
        return (byte) depth;
    }

    private static byte sampleColor(BlockState block, BlockGetter world, BlockPos pos) {
        try {
            MapColor color = block.getMapColor(world, pos);
            return color == MapColor.NONE ? 0 : (byte) (color.id + 1);
        } catch (Exception worldDependentColor) {
            return (byte) (MapColor.STONE.id + 1);
        }
    }

    private static byte classifyFeature(String key) {
        if ("minecraft:dirt_path".equals(key)) return 1;
        if ("minecraft:rail".equals(key) || "minecraft:powered_rail".equals(key)
                || "minecraft:detector_rail".equals(key) || "minecraft:activator_rail".equals(key)) return 2;
        // Independent optional Create support: the mod is never a required dependency.
        if ("create:track".equals(key)) return 3;
        return 0;
    }

    private static boolean verifiedSurfaceStructure(String id, String[] top, byte[] features) {
        int count = 0;
        if (id.startsWith("minecraft:village_")) {
            for (byte f : features) if (f == 1) count++;
            if (count >= 2) return true;
            for (String block : top) if ("minecraft:bell".equals(block) || "minecraft:hay_block".equals(block))
                return true;
            return false;
        }
        for (String block : top) {
            if (block == null) continue;
            boolean evidence;
            if (id.equals("minecraft:desert_pyramid"))
                evidence = block.equals("minecraft:chiseled_sandstone") || block.equals("minecraft:cut_sandstone");
            else if (id.equals("minecraft:jungle_temple"))
                evidence = block.equals("minecraft:mossy_cobblestone");
            else if (id.equals("minecraft:pillager_outpost"))
                evidence = block.equals("minecraft:dark_oak_log") || block.equals("minecraft:spruce_planks");
            else if (id.equals("minecraft:woodland_mansion"))
                evidence = block.equals("minecraft:dark_oak_planks");
            else if (id.startsWith("minecraft:ruined_portal"))
                evidence = block.equals("minecraft:obsidian") || block.equals("minecraft:crying_obsidian");
            else if (id.equals("minecraft:swamp_hut"))
                evidence = block.equals("minecraft:spruce_planks");
            else if (id.equals("minecraft:igloo"))
                evidence = block.equals("minecraft:snow_block");
            else if (id.equals("minecraft:shipwreck_beached"))
                evidence = block.endsWith("_planks");
            else return false;
            if (evidence && ++count >= 2) return true;
        }
        return false;
    }

    /** Decode 1.21.1 saved FULL chunk NBT without loading it into Minecraft. */
    private static SurfaceTile sampleSaved(int minBuildHeight, int worldHeight, CompoundTag nbt) {
        CompoundTag maps = nbt.getCompound("Heightmaps");
        long[] height = maps.getLongArray("WORLD_SURFACE");
        int bits = 32 - Integer.numberOfLeadingZeros(worldHeight);
        int expectedLongs = (256 + (64 / bits) - 1) / (64 / bits);
        if (height.length != expectedLongs) return null;
        SimpleBitStorage surface = new SimpleBitStorage(bits, 256, height);

        Map<Integer, SavedSection> savedSections = new HashMap<>();
        ListTag sections = nbt.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int sectionY = section.getByte("Y");
            CompoundTag blocks = section.getCompound("block_states");
            ListTag paletteList = blocks.getList("palette", Tag.TAG_COMPOUND);
            if (paletteList.isEmpty()) continue;
            BlockState[] states = new BlockState[paletteList.size()];
            String[] registryIds = new String[paletteList.size()];
            for (int j = 0; j < states.length; j++) {
                CompoundTag palette = paletteList.getCompound(j);
                registryIds[j] = palette.getString("Name");
                // Unknown mod blocks use a safe stone map color, but retaining
                // the actual palette name still lets us identify Create:track.
                states[j] = BlockState.CODEC.parse(NbtOps.INSTANCE, palette)
                        .result().orElse(Blocks.STONE.defaultBlockState());
            }
            SimpleBitStorage indices = null;
            if (states.length > 1) {
                int blockBits = Math.max(4, 32 - Integer.numberOfLeadingZeros(states.length - 1));
                long[] packed = blocks.getLongArray("data");
                int longs = (4096 + (64 / blockBits) - 1) / (64 / blockBits);
                if (packed.length != longs) return null;
                indices = new SimpleBitStorage(blockBits, 4096, packed);
            }
            savedSections.put(sectionY, new SavedSection(states, registryIds, indices));
        }

        byte[] result = new byte[256], features = new byte[256], depths = new byte[256];
        short[] heights = new short[256];
        Arrays.fill(heights, TerrainRelief.UNKNOWN_HEIGHT);
        String[] topNames = new String[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cx = nbt.getInt("xPos"), cz = nbt.getInt("zPos");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = x + z * 16;
            int offset = surface.get(i);
            if (offset > worldHeight) return null;
            int top = minBuildHeight + offset - 1;
            int lx = x, lz = z;
            for (int y = top; y >= minBuildHeight; y--) {
                SavedSection section = savedSections.get(Math.floorDiv(y, 16));
                if (section == null) {
                    if (y == top) return null;
                    y = Math.floorDiv(y, 16) * 16;
                    continue;
                }
                int index = section.index(x, y, z);
                BlockState block = section.palette()[index];
                pos.set(cx * 16 + x, y, cz * 16 + z);
                boolean water = isWater(block);
                byte color = water ? (byte) (MapColor.WATER.id + 1) : sampleColor(block, null, pos);
                if (y == top || color != 0) {
                    String key = section.names()[index];
                    if (features[i] == 0) features[i] = classifyFeature(key);
                    if (topNames[i] == null) topNames[i] = key;
                }
                if (color == 0) continue;
                result[i] = color;
                heights[i] = (short) y;
                if (water) {
                    depths[i] = waterDepth(y, minBuildHeight, below -> {
                        SavedSection lower = savedSections.get(Math.floorDiv(below, 16));
                        return lower != null && isWater(lower.palette()[lower.index(lx, below, lz)]);
                    });
                }
                break;
            }
        }

        List<SurfaceStructure> markers = new ArrayList<>();
        CompoundTag starts = nbt.getCompound("structures").getCompound("starts");
        for (String type : starts.getAllKeys()) {
            CompoundTag structure = starts.getCompound(type);
            if (structure.getString("id").isBlank() || structure.getString("id").equals("INVALID")
                    || structure.getList("Children", Tag.TAG_COMPOUND).isEmpty()) continue;
            // A seed-predicted reference is not enough: only persisted FULL
            // chunks with actual placed, visible signature blocks qualify.
            if (verifiedSurfaceStructure(type, topNames, features)) {
                markers.add(new SurfaceStructure(type, cx * 16 + 8, cz * 16 + 8));
                if (markers.size() >= 4) break;
            }
        }
        return new SurfaceTile(result, features, heights, depths, markers);
    }

    private record SavedSection(BlockState[] palette, String[] names, SimpleBitStorage indices) {
        int index(int x, int y, int z) {
            return indices == null ? 0 : indices.get(((y & 15) << 8) | (z << 4) | x);
        }
    }
}
