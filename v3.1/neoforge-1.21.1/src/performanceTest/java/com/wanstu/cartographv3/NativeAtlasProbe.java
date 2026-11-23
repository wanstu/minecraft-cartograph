package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;

/** Graphics smoke test using synthetic fixtures in an isolated development client. */
@EventBusSubscriber(modid = MapProtocol.MOD_ID, value = Dist.CLIENT)
public final class NativeAtlasProbe {
    private static final Map<ChunkKey, SurfaceTile> FACTS = new LinkedHashMap<>();
    private static final String DIMENSION = "minecraft:overworld";
    private static final UUID WORLD = UUID.randomUUID();
    private static InGameMapTiles tiles;
    private static CartographBackendApi backend;
    private static InGameMapCamera camera;
    private static int stage;
    private static int readyFrames;
    private static long started;
    private static long maxUpdateNanos;
    private static boolean finished;

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!Boolean.getBoolean("cartograph.nativePerformanceProbe") || finished) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getOverlay() != null) return;
        try {
            if (tiles == null) initialize();
            if (System.nanoTime() - started > 90_000_000_000L)
                throw new AssertionError("Native atlas probe timed out at stage " + stage);
            long before = System.nanoTime();
            tiles.update(camera);
            maxUpdateNanos = Math.max(maxUpdateNanos, System.nanoTime() - before);
            tiles.draw(new MapGraphics(event.getGuiGraphics()), camera);
            List<?> frame = (List<?>) field(tiles, "frame");
            long expected = MapPresentationRenderer.renderSignature();
            boolean complete = frame.size() >= 4;
            for (Object item : frame) {
                Object atlas = field(item, "atlas");
                if ((long) field(atlas, "signature") != expected)
                    throw new AssertionError("A previous presentation was displayed after a style switch");
                complete &= (boolean) field(atlas, "complete");
            }
            if (((Map<?, ?>) field(tiles, "reads")).size() > 4)
                throw new AssertionError("Unbounded atlas read queue");
            if (!complete || ++readyFrames < 20) return;
            readyFrames = 0;
            System.out.println("V31_NATIVE_ATLAS_PROBE stage=" + stage + " visible=" + frame.size());
            if (stage++ == 0) {
                camera.restore(new InGameMapCamera.Position(DIMENSION, 0, 0, 1.0 / 16.0));
            } else if (stage == 2) {
                CartographSettings.setDrawingMode(MapDrawingMode.HAND_DRAWN);
                CartographSettings.setVisualStyle("ancient");
            } else if (stage == 3) {
                tiles.close();
                FACTS.clear();
                tiles = new InGameMapTiles(backend);
            } else {
                tiles.close();
                finished = true;
                String result = "PASS stages=4 signature_isolation=true bounded_reads=true disk_only_restore=true max_update_ms="
                        + maxUpdateNanos / 1_000_000.0;
                Files.writeString(minecraft.gameDirectory.toPath().resolve("native-probe-result.txt"), result);
                System.out.println("V31_NATIVE_ATLAS_PROBE_PASS " + result);
                minecraft.stop();
            }
        } catch (Throwable failure) {
            finished = true;
            failure.printStackTrace();
            System.err.println("V31_NATIVE_ATLAS_PROBE_FAIL stage=" + stage);
            System.exit(5);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void initialize() {
        started = System.nanoTime();
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        int grass = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        int water = Block.getId(Blocks.WATER.defaultBlockState());
        for (int z = -16; z < 16; z++) {
            for (int x = -16; x < 16; x++) {
                int[] states = new int[SurfaceTile.PIXELS];
                int[] biomes = new int[SurfaceTile.PIXELS];
                short[] heights = new short[SurfaceTile.PIXELS];
                byte[] depths = new byte[SurfaceTile.PIXELS];
                Arrays.fill(states, (x & 3) == 0 ? water : ((z & 3) == 0 ? stone : grass));
                Arrays.fill(biomes, -1);
                Arrays.fill(heights, (short) (64 + Math.floorMod(x + z, 8)));
                if ((x & 3) == 0) Arrays.fill(depths, (byte) 3);
                ChunkKey key = new ChunkKey(DIMENSION, x, z);
                FACTS.put(key, SurfaceTile.fromFacts(key, states, biomes, heights, heights, depths));
            }
        }
        backend = (CartographBackendApi) Proxy.newProxyInstance(NativeAtlasProbe.class.getClassLoader(),
                new Class<?>[]{CartographBackendApi.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "dimension" -> DIMENSION;
                    case "worldId" -> WORLD;
                    case "tile" -> FACTS.get(arguments[0]);
                    case "isTileMissing" -> false;
                    case "regionRevision" -> 1L;
                    case "cachedKeysInBounds" -> FACTS.keySet().stream().filter(key ->
                            key.x() >= (int) arguments[1] && key.x() <= (int) arguments[2]
                                    && key.z() >= (int) arguments[3] && key.z() <= (int) arguments[4]).toList();
                    default -> throw new AssertionError("Unexpected backend operation " + method.getName());
                });
        CartographSettings.setDrawingMode(MapDrawingMode.SATELLITE);
        CartographSettings.setVisualStyle("modern");
        MapPresentationRenderer.beginMapSession();
        camera = new InGameMapCamera();
        camera.resize(1280, 720, 0, 1);
        camera.center(0, 0);
        tiles = new InGameMapTiles(backend);
    }
}
