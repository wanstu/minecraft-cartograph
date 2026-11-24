package com.wanstu.cartographv3;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Behavior checks run by check without an external test framework or a game window. */
public final class PerformanceSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        budgetAndFairness();
        frontendLifecycle();
        viewStateRoundTrip();
        memoryBoundAndEviction();
        knownAtlasBounds();
        missingCacheLifecycleAndScale();
        System.out.println("Cartograph performance regressions: " + checks + " checks passed");
    }

    private static void budgetAndFairness() {
        AtomicLong nanos = new AtomicLong();
        TickWorkQueue queue = new TickWorkQueue(nanos::get);
        int[] remaining = {256, 256};
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            final int task = index;
            queue.add(() -> {
                order.add(task);
                nanos.addAndGet(300_000L);
                return --remaining[task] == 0;
            });
        }
        require(queue.drain(1_000_000L, 512) == 4, "slow work yields after one small-step overshoot");
        require(remaining[0] == 254 && remaining[1] == 254, "captures share the tick fairly");
        require(order.equals(List.of(0, 1, 0, 1)), "round-robin order");
        require(queue.drain(0L, 512) == 0, "zero budget does no work");
        require(queue.drain(Long.MAX_VALUE, 1) == 1, "column cap is independent of elapsed time");
        while (queue.drain(1_000_000L, 512) != 0) {}
        require(remaining[0] == 0 && remaining[1] == 0, "partial captures eventually finish");
        require(queue.drain(Long.MAX_VALUE, 512) == 0, "finished captures never repeat");
        AtomicInteger cancelled = new AtomicInteger();
        queue.add(() -> { cancelled.incrementAndGet(); return false; });
        queue.clear();
        queue.drain(Long.MAX_VALUE, 512);
        require(cancelled.get() == 0, "world teardown discards queued work");
    }

    private static void frontendLifecycle() {
        List<String> calls = new ArrayList<>();
        CartographBackendApi api = (CartographBackendApi) Proxy.newProxyInstance(
                PerformanceSelfTest.class.getClassLoader(), new Class<?>[]{CartographBackendApi.class},
                (proxy, method, arguments) -> {
                    calls.add(method.getName() + (method.getName().equals("updateViewport")
                            ? ":" + arguments[0] : ""));
                    return null;
                });
        CartographBackendRuntime runtime = new CartographBackendRuntime(api);
        runtime.setWorldActive(true);
        runtime.acquire("native");
        var nativeViewport = new CartographBackendApi.Viewport(0, 0, 1, 800, 600, false);
        runtime.updateViewport("native", nativeViewport, false);
        runtime.acquire("web");
        runtime.updateViewport("web", new CartographBackendApi.Viewport(100, 0, 1, 800, 600, false), false);
        runtime.release("web");
        require(calls.getLast().equals("updateViewport:" + nativeViewport), "closing one view restores the other viewport");
        runtime.release("native");
        require(calls.getLast().equals("updateViewport:null"), "last frontend clears stale viewport");
        require(calls.stream().filter("open"::equals).count() == 1, "frontends share one world collector");
        require(!calls.contains("close"), "closing the map preserves world fact collection");
        runtime.setWorldActive(false);
        require(calls.getLast().equals("close"), "leaving the world closes collector");
        runtime.close();
        require(calls.stream().filter("close"::equals).count() == 1, "teardown is idempotent");
    }

    private static void viewStateRoundTrip() {
        for (boolean collecting : new boolean[]{false, true}) {
            for (boolean viewing : new boolean[]{false, true}) {
                var input = new MapProtocol.MapViewState(collecting, viewing, 10, 12345L);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    MapProtocol.MapViewState.CODEC.encode(buffer, input);
                    var output = MapProtocol.MapViewState.CODEC.decode(buffer);
                    require(input.equals(output) && !buffer.isReadable(), "collection/view flags survive network round trip");
                } finally { buffer.release(); }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void memoryBoundAndEviction() throws Exception {
        Class<?> cachedType = Class.forName(MapClientCache.class.getName() + "$CachedTile");
        Constructor<?> cached = cachedType.getDeclaredConstructor(SurfaceTile.class, boolean.class);
        cached.setAccessible(true);
        Method put = MapClientCache.class.getDeclaredMethod("putTile", ChunkKey.class, cachedType);
        put.setAccessible(true);
        int[] states = new int[SurfaceTile.PIXELS];
        int[] biomes = new int[SurfaceTile.PIXELS];
        short[] surface = new short[SurfaceTile.PIXELS];
        short[] relief = new short[SurfaceTile.PIXELS];
        byte[] water = new byte[SurfaceTile.PIXELS];
        String dimension = "minecraft:overworld";
        for (int i = 0; i < 8192; i++) {
            ChunkKey key = new ChunkKey(dimension, i, 0);
            put.invoke(null, key, cached.newInstance(SurfaceTile.fromFacts(
                    key, states, biomes, surface, relief, water), true));
        }
        ChunkKey recent = new ChunkKey(dimension, 0, 0);
        MapClientCache.tile(recent);
        ChunkKey added = new ChunkKey(dimension, 8192, 0);
        put.invoke(null, added, cached.newInstance(SurfaceTile.fromFacts(
                added, states, biomes, surface, relief, water), false));
        ChunkKey evicted = new ChunkKey(dimension, 1, 0);
        require(MapClientCache.cachedCount() == 8192, "RAM facts stay bounded as exploration grows");
        require(MapClientCache.tile(recent) != null, "recently drawn facts survive eviction");
        require(MapClientCache.tile(evicted) == null, "least recently used facts leave RAM");
        require(!MapClientCache.isMissing(evicted), "evicted facts do not become missing terrain");
        require(MapClientCache.tile(added) != null, "new terrain remains available");
        var field = MapClientCache.class.getDeclaredField("REGION_TILE_COUNTS");
        field.setAccessible(true);
        Map<?, Integer> counts = (Map<?, Integer>) field.get(null);
        require(counts.values().stream().mapToInt(Integer::intValue).sum() == 8192,
                "region accounting follows RAM eviction");
    }


    @SuppressWarnings("unchecked")
    private static void missingCacheLifecycleAndScale() throws Exception {
        var mark = MapClientCache.class.getDeclaredMethod("markMissing", ChunkKey.class);
        var known = MapClientCache.class.getDeclaredMethod("knownMissingThisSession", ChunkKey.class);
        var session = MapClientCache.class.getDeclaredField("mapSession");
        var missingField = MapClientCache.class.getDeclaredField("MISSING_AT");
        var validatedField = MapClientCache.class.getDeclaredField("VALIDATED_SESSION");
        for (var member : new java.lang.reflect.AccessibleObject[]{mark, known, session, missingField, validatedField})
            member.setAccessible(true);
        long originalSession = session.getLong(null);
        Map<ChunkKey, Integer> missing = (Map<ChunkKey, Integer>) missingField.get(null);
        Map<ChunkKey, Long> validated = (Map<ChunkKey, Long>) validatedField.get(null);
        missing.clear(); validated.clear();
        ChunkKey first = new ChunkKey("qa:missing", 0, 0);
        mark.invoke(null, first);
        require((boolean) known.invoke(null, first), "confirmed absent terrain is not polled in the same map session");
        session.setLong(null, originalSession + 1);
        require(!(boolean) known.invoke(null, first), "a new map session revalidates absent terrain");
        for (int i = 0; i < 262144; i++) mark.invoke(null, new ChunkKey("qa:missing", i, -i));
        require(missing.size() == 8192, "262144 absent tiles leave only the bounded negative cache");
        require(validated.size() == 8192, "negative validation metadata is bounded as the view travels");
        require(!missing.containsKey(first), "old absent tiles can be retried after LRU eviction");
        require((boolean) known.invoke(null, new ChunkKey("qa:missing", 262143, -262143)),
                "the newest absent tile keeps its validation");
        missing.clear(); validated.clear(); session.setLong(null, originalSession);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    @SuppressWarnings("unchecked")
    private static void knownAtlasBounds() throws Exception {
        Class<?> keyType = Class.forName(PersistentRenderedAtlasStore.class.getName() + "$IndexKey");
        Constructor<?> keyConstructor = keyType.getDeclaredConstructor(java.util.UUID.class, String.class, long.class);
        keyConstructor.setAccessible(true);
        var world = java.util.UUID.randomUUID();
        var key = keyConstructor.newInstance(world, "minecraft:overworld", 7L);
        var indexField = PersistentRenderedAtlasStore.class.getDeclaredField("REGION_INDEX");
        indexField.setAccessible(true);
        Map<Object, Object> index = (Map<Object, Object>) indexField.get(null);
        index.put(key, java.util.Set.of("-3_4", "0_0", "2_-5", "12_20", "invalid"));
        var loadsField = PersistentRenderedAtlasStore.class.getDeclaredField("INDEX_LOADS");
        loadsField.setAccessible(true);
        ((Map<Object, Object>) loadsField.get(null)).put(key,
                java.util.concurrent.CompletableFuture.completedFuture(null));
        var regions = PersistentRenderedAtlasStore.regionsInBounds(world, "minecraft:overworld", 7L,
                -4, 0, 0, 5);
        require(regions.size() == 2 && regions.contains(new PersistentRenderedAtlasStore.RegionPosition(-3, 4))
                && regions.contains(new PersistentRenderedAtlasStore.RegionPosition(0, 0)),
                "atlas query uses known regions and handles negative coordinates");
        require(PersistentRenderedAtlasStore.regionsInBounds(world, "minecraft:overworld", 7L,
                1000000, 2000000, 1000000, 2000000).isEmpty(),
                "large empty views return no terrain candidates");
    }
}
