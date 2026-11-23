package com.wanstu.cartographv3;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MapProtocol.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LoadedSurfaceGameTest {
    @GameTest(template = "performance_empty", timeoutTicks = 160)
    public static void budgetedCapturePreservesFacts(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
        int chunkX = Math.floorDiv(anchor.getX(), 16);
        int chunkZ = Math.floorDiv(anchor.getZ(), 16);
        int baseY = anchor.getY() + 8;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int worldX = chunkX * 16 + x;
                int worldZ = chunkZ * 16 + z;
                int bed = baseY + ((x + z) & 1);
                level.setBlock(new BlockPos(worldX, bed, worldZ), Blocks.STONE.defaultBlockState(), 2);
                if (x >= 7) {
                    boolean wall = x == 7 || x == 15 || z == 0 || z == 15;
                    for (int y = bed + 1; y <= baseY + 4; y++) {
                        level.setBlock(new BlockPos(worldX, y, worldZ),
                                (wall ? Blocks.STONE : Blocks.WATER).defaultBlockState(), 2);
                    }
                } else if (x < 3) {
                    level.setBlock(new BlockPos(worldX, bed + 3, worldZ), Blocks.GLASS.defaultBlockState(), 2);
                } else {
                    level.setBlock(new BlockPos(worldX, bed + 3, worldZ),
                            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 2);
                }
            }
        }
        var expected = LoadedSurfaceReference.capture(level, chunkX, chunkZ).orElseThrow();
        var future = LoadedSurfaceReader.captureAsync(level, chunkX, chunkZ);
        helper.assertTrue(!future.isDone(), "capture must yield before scanning a full chunk");
        helper.assertTrue(future == LoadedSurfaceReader.captureAsync(level, chunkX, chunkZ),
                "duplicate loaded captures share work");
        int unloadedX = 100000;
        helper.assertTrue(level.getChunkSource().getChunkNow(unloadedX, unloadedX) == null,
                "unloaded fixture is outside the generated area");
        helper.assertTrue(LoadedSurfaceReader.captureAsync(level, unloadedX, unloadedX).isCompletedExceptionally(),
                "unloaded captures fail without generating terrain");
        future.thenAccept(tile -> helper.assertTrue(level.getServer().isSameThread(),
                "world sampling completes on the server thread"));
        helper.runAtTickTime(40, () -> {
            helper.assertTrue(future.isDone() && !future.isCompletedExceptionally(), "budgeted capture completes");
            var actual = future.join().orElseThrow();
            helper.assertTrue(actual.matchesFacts(expected.stateIds(), expected.biomeIds(),
                    expected.surfaceHeights(), expected.reliefHeights(), expected.waterDepths()),
                    "state, biome, heights and water depth match the synchronous reference");
            helper.assertTrue(java.util.Arrays.equals(actual.rgb(), expected.rgb()),
                    "budgeting preserves surface shading");
            helper.assertTrue(level.getChunkSource().getChunkNow(unloadedX, unloadedX) == null,
                    "sampling never force-loads unseen chunks");
            helper.succeed();
        });
    }
}
