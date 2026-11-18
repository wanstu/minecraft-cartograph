package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.Optional;

final class LoadedSurfaceReader {
    private LoadedSurfaceReader() {}

    static Optional<SurfaceTile> capture(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Loaded chunk sampling must run on server thread");

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) return Optional.empty();

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
        var biomeRegistry = MapGameAccess.registry(level.registryAccess(), Registries.BIOME);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int reliefY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int oceanFloor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                for (int y = top; y >= MapGameAccess.minY(chunk); y--) {
                    pos.set(worldX, y, worldZ);
                    var state = chunk.getBlockState(pos);
                    MapColor color;
                    try {
                        color = state.getMapColor(level, pos);
                    } catch (RuntimeException unsupported) {
                        color = MapColor.STONE;
                    }
                    if (color == MapColor.NONE) continue;
                    int waterDepth = color == MapColor.WATER ? Math.max(0, y - oceanFloor) : 0;
                    colors[index] = SurfaceColors.surfaceColor(
                            color, waterDepth, worldX, worldZ);
                    heights[index] = (short) Math.max(MapGameAccess.minY(chunk), reliefY);
                    stateIds[index] = Block.getId(state);
                    biomeIds[index] = biomeRegistry.getId(level.getBiome(pos).value());
                    surfaceHeights[index] = (short) y;
                    reliefHeights[index] = (short) Math.max(MapGameAccess.minY(chunk), reliefY);
                    waterDepths[index] = (byte) Math.min(255, waterDepth);
                    break;
                }
            }
        }
        return Optional.of(new SurfaceTile(
                new ChunkKey(MapGameAccess.dimension(level), chunkX, chunkZ),
                SurfaceColors.shade(colors, heights),
                stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths));
    }
}
