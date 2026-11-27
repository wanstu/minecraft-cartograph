package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class LoadedSurfaceReader {
    private static final long BUDGET_NANOS = 1_000_000L;
    private static final int MAX_COLUMNS_PER_TICK = 512;
    private record Key(ServerLevel level, int x, int z) {}
    private static final LinkedHashMap<Key, Capture> PENDING = new LinkedHashMap<>();
    private static final TickWorkQueue WORK = new TickWorkQueue();
    private static long drainCycle;
    private static long capturesStarted;
    private static long columnsSampled;

    private LoadedSurfaceReader() {}

    static CompletableFuture<Optional<SurfaceTile>> captureAsync(
            ServerLevel level, int chunkX, int chunkZ) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("Loaded chunk sampling must run on server thread");
        Key key = new Key(level, chunkX, chunkZ);
        Capture existing = PENDING.get(key);
        if (existing != null) return existing.result;
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) return CompletableFuture.failedFuture(
                new IllegalStateException("Surface chunk is no longer loaded"));
        capturesStarted++;
        Capture capture = new Capture(key, chunk);
        PENDING.put(key, capture);
        WORK.add(capture);
        return capture.result;
    }

    static void tick(MinecraftServer server) {
        if (!server.isSameThread())
            throw new IllegalStateException("Surface work must drain on server thread");
        drainCycle++;
        WORK.drain(BUDGET_NANOS, MAX_COLUMNS_PER_TICK);
    }

    static void clear(MinecraftServer server) {
        var captures = java.util.List.copyOf(PENDING.values());
        PENDING.clear();
        WORK.clear();
        for (Capture capture : captures) capture.result.cancel(false);
    }

    private static final class Capture implements TickWorkQueue.Work {
        final Key key;
        final LevelChunk chunk;
        final long sourceRevision;
        final int minY;
        final CompletableFuture<Optional<SurfaceTile>> result = new CompletableFuture<>();
        final int[] colors = new int[SurfaceTile.PIXELS];
        final short[] heights = new short[SurfaceTile.PIXELS];
        final int[] stateIds = new int[SurfaceTile.PIXELS];
        final int[] biomeIds = new int[SurfaceTile.PIXELS];
        final short[] surfaceHeights = new short[SurfaceTile.PIXELS];
        final short[] reliefHeights = new short[SurfaceTile.PIXELS];
        final byte[] waterDepths = new byte[SurfaceTile.PIXELS];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes;
        int next;
        long checkedCycle;

        Capture(Key key, LevelChunk chunk) {
            this.key = key;
            this.chunk = chunk;
            this.sourceRevision = SurfaceService.loadedRevision(chunk);
            this.minY = MapGameAccess.minY(chunk);
            biomes = MapGameAccess.registry(key.level().registryAccess(), Registries.BIOME);
            Arrays.fill(heights, SurfaceColors.UNKNOWN_HEIGHT);
            Arrays.fill(stateIds, -1);
            Arrays.fill(biomeIds, -1);
            Arrays.fill(surfaceHeights, SurfaceTile.UNKNOWN_HEIGHT);
            Arrays.fill(reliefHeights, SurfaceTile.UNKNOWN_HEIGHT);
        }

        @Override
        public boolean step() {
            if (result.isDone()) return true;
            try {
                ServerLevel level = key.level();
                // The server thread owns the chunk and this drain. Recheck
                // when a partial capture resumes in a later tick.
                if (checkedCycle != drainCycle) {
                    if (level.getChunkSource().getChunkNow(key.x(), key.z()) != chunk)
                        throw new IllegalStateException("Surface chunk unloaded during sampling");
                    checkedCycle = drainCycle;
                }
                sampleColumn(level, next++);
                if (next < SurfaceTile.PIXELS) return false;
                SurfaceTile tile = new SurfaceTile(
                        new ChunkKey(MapGameAccess.dimension(level), key.x(), key.z()),
                        SurfaceColors.shade(colors, heights),
                        stateIds, biomeIds, surfaceHeights, reliefHeights, waterDepths);
                SurfaceService.loadedCaptured(chunk, tile, sourceRevision);
                PENDING.remove(key, this);
                result.complete(Optional.of(tile));
            } catch (Throwable failure) {
                PENDING.remove(key, this);
                result.completeExceptionally(failure);
            }
            return true;
        }

        private void sampleColumn(ServerLevel level, int index) {
            columnsSampled++;
            int x = index & 15;
            int z = index >>> 4;
            int worldX = key.x() * 16 + x;
            int worldZ = key.z() * 16 + z;
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int reliefY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int oceanFloor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            for (int y = top; y >= minY; y--) {
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
                colors[index] = SurfaceColors.surfaceColor(color, waterDepth, worldX, worldZ);
                heights[index] = (short) Math.max(minY, reliefY);
                stateIds[index] = Block.getId(state);
                biomeIds[index] = biomes.getId(level.getBiome(pos).value());
                surfaceHeights[index] = (short) y;
                reliefHeights[index] = heights[index];
                waterDepths[index] = (byte) Math.min(255, waterDepth);
                break;
            }
        }
    }
}
