package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
        Arrays.fill(heights, SurfaceColors.UNKNOWN_HEIGHT);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = top; y >= chunk.getMinBuildHeight(); y--) {
                    pos.set(worldX, y, worldZ);
                    var state = chunk.getBlockState(pos);
                    MapColor color;
                    try {
                        color = state.getMapColor(level, pos);
                    } catch (RuntimeException unsupported) {
                        color = MapColor.STONE;
                    }
                    if (color == MapColor.NONE) continue;
                    colors[index] = color.col & 0x00ffffff;
                    heights[index] = (short) y;
                    break;
                }
            }
        }
        return Optional.of(new SurfaceTile(
                new ChunkKey(level.dimension().location().toString(), chunkX, chunkZ),
                SurfaceColors.shade(colors, heights)));
    }
}
