package com.wanstu.cartographv3;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
        atlasReleaseWhileWriterBusy();
        atlasBlockReadCompatibility();
        missingCacheLifecycleAndScale();
        selectiveSurfaceNbt();
        backgroundCompletionIsPerDimension();
        recentSavesShareBackgroundBudget();
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
        ChunkKey isolated = new ChunkKey("qa:other_dimension", -17, -16);
        put.invoke(null, isolated, cached.newInstance(SurfaceTile.fromFacts(
                isolated, states, biomes, surface, relief, water), true));
        require(MapClientCache.cachedRegionsInBounds(isolated.dimension(), -2, -2, -1, -1)
                        .equals(List.of(new CartographBackendApi.CachedRegion(-2, -1))),
                "region lookup floors negative chunk coordinates");
        require(MapClientCache.cachedRegionsInBounds(dimension, -2, -2, -1, -1).isEmpty(),
                "region lookup uses only the requested dimension");
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
        require(MapClientCache.cachedRegionsInBounds(isolated.dimension(), -2, -2, -1, -1).isEmpty(),
                "evicting a region's last chunk removes it from the region lookup");
        require(MapClientCache.cachedRegionsInBounds(dimension, 512, 512, 0, 0)
                        .equals(List.of(new CartographBackendApi.CachedRegion(512, 0))),
                "receiving the first tile makes its whole cache region discoverable");
        require(MapClientCache.cachedRegionsInBounds(dimension, 0, 0, 0, 0)
                        .equals(List.of(new CartographBackendApi.CachedRegion(0, 0))),
                "each populated region is returned once within inclusive bounds");
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


    @SuppressWarnings("unchecked")
    private static void backgroundCompletionIsPerDimension() throws Exception {
        Class<?> type = Class.forName(GeneratedChunkBackfill.class.getName() + "$State");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object state = constructor.newInstance();
        var pendingField = type.getDeclaredField("initialPending"); pendingField.setAccessible(true);
        Map<String, java.util.Set<ChunkKey>> groups =
                (Map<String, java.util.Set<ChunkKey>>) pendingField.get(state);
        // Looking through this foreign backlog is a regression even if a small fixture runs fast.
        java.util.Set<ChunkKey> foreign = new java.util.AbstractSet<>() {
            @Override public java.util.Iterator<ChunkKey> iterator() {
                throw new AssertionError("completion traversed a foreign dimension");
            }
            @Override public int size() { return 312391; }
        };
        groups.put("qa:foreign", foreign);
        ChunkKey first = new ChunkKey("qa:current", 1, 2), second = new ChunkKey("qa:current", 3, 4);
        java.util.Set<ChunkKey> current = java.util.concurrent.ConcurrentHashMap.newKeySet();
        current.add(first); current.add(second); groups.put("qa:current", current);
        var complete = GeneratedChunkBackfill.class.getDeclaredMethod("completeAttempt",
                net.minecraft.server.level.ServerPlayer.class, type, ChunkKey.class);
        complete.setAccessible(true);
        complete.invoke(null, null, state, first);
        require(current.size() == 1 && current.contains(second), "completion removes only its own pending key");
        complete.invoke(null, null, state, first);
        require(current.size() == 1, "duplicate completion cannot consume another pending key");
        complete.invoke(null, null, state, second);
        require(current.isEmpty() && groups.get("qa:foreign") == foreign,
                "finishing one dimension neither traverses nor clears another dimension");
        complete.invoke(null, null, state, new ChunkKey("qa:later", 5, 6));
        require(groups.size() == 2, "a later completion does not create or scan unrelated pending groups");
    }

    private static void recentSavesShareBackgroundBudget() {
        var queue = new GeneratedChunkBackfill.ReadyChunks();
        ChunkKey archive = new ChunkKey("qa:overworld", -120, 640);
        List<ChunkKey> saves = List.of(
                new ChunkKey("qa:overworld", -38, 753), new ChunkKey("qa:overworld", -37, 753),
                new ChunkKey("qa:overworld", -37, 754), new ChunkKey("qa:overworld", -36, 754));
        queue.addArchived(archive);
        for (ChunkKey key : saves) queue.addSaved(key, 0L);
        queue.addSaved(saves.getFirst(), 0L);
        require(queue.poll(0L).key().equals(archive), "disk-write grace uses archive budget");
        queue.addArchived(new ChunkKey("qa:overworld", -120, 641));
        long now = TimeUnit.SECONDS.toNanos(2);
        for (ChunkKey key : saves.subList(0, 3)) {
            require(queue.poll(now).key().equals(key), "recent saves pass the old archive");
        }
        require(queue.poll(now).recentAttempt() == 0, "continuous saves leave work for the archive");
        var claim = queue.poll(now);
        require(claim.key().equals(saves.getLast()) && queue.poll(now) == null,
                "duplicate save notifications produce only one read");
        require(queue.retryEmpty(claim, now) && queue.poll(now) == null,
                "an unfinished write can retry without exposing an empty tile");
        claim = queue.poll(now += TimeUnit.SECONDS.toNanos(2));
        require(claim.recentAttempt() == 2, "retry survives a temporary missing disk slot");
        queue.retryEmpty(claim, now);
        claim = queue.poll(now += TimeUnit.SECONDS.toNanos(2));
        queue.retryEmpty(claim, now);
        require(queue.poll(now).recentAttempt() == 0, "an inaccessible save falls back to archive work");
        queue.complete(saves.getLast());
        require(!queue.retryEmpty(claim, now), "a delivered tile is no longer retried");
    }

    private static void selectiveSurfaceNbt() throws Exception {
        var root = new net.minecraft.nbt.CompoundTag();
        root.putString("Status", "minecraft:full"); root.putInt("xPos", -3); root.putInt("zPos", 7);
        var heightmaps = new net.minecraft.nbt.CompoundTag(); heightmaps.putLongArray("WORLD_SURFACE", new long[]{2, 7});
        root.put("Heightmaps", heightmaps);
        var section = new net.minecraft.nbt.CompoundTag(); section.putByte("Y", (byte) -4);
        section.putByteArray("SkyLight", new byte[2048]); section.putByteArray("BlockLight", new byte[2048]);
        var states = new net.minecraft.nbt.CompoundTag(); states.putString("SkyLight", "keep-string-property");
        states.putLongArray("data", new long[]{3, 5}); section.put("block_states", states);
        var sections = new net.minecraft.nbt.ListTag(); sections.add(section); root.put("sections", sections);
        var structures = new net.minecraft.nbt.CompoundTag(); structures.putIntArray("BB", new int[]{1,2,3,4,5,6});
        root.put("structures", structures); root.put("Structures", structures.copy());
        root.putByteArray("unused_entity_payload", new byte[1024 * 1024]);
        var bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(root, new java.io.DataOutputStream(bytes));
        var read = RegionNbtReader.readSurfaceData(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        var expected = root.copy(); expected.remove("unused_entity_payload");
        var expectedSection = MapNbt.compound(MapNbt.list(expected, "sections", net.minecraft.nbt.Tag.TAG_COMPOUND), 0);
        expectedSection.remove("SkyLight"); expectedSection.remove("BlockLight");
        require(read.equals(expected), "selective NBT preserves every terrain and structure field");
        require(MapNbt.string(MapNbt.compound(MapNbt.compound(MapNbt.list(read, "sections", net.minecraft.nbt.Tag.TAG_COMPOUND),0), "block_states"), "SkyLight")
                .equals("keep-string-property"), "only unused byte-array lighting is skipped");
        boolean rejected = false;
        try { RegionNbtReader.readSurfaceData(new java.io.ByteArrayInputStream(new byte[]{7,0,0,0,0,0,0})); }
        catch (java.io.IOException invalid) { rejected = true; }
        require(rejected, "non-compound chunk roots remain invalid");
        rejected = false;
        try { RegionNbtReader.readSurfaceData(new java.io.ByteArrayInputStream(new byte[]{0})); }
        catch (java.io.IOException invalid) { rejected = true; }
        require(rejected, "empty NBT roots remain invalid");
        root.putByteArray("oversized_unused_payload", new byte[17 * 1024 * 1024]);
        bytes.reset(); net.minecraft.nbt.NbtIo.write(root, new java.io.DataOutputStream(bytes));
        rejected = false;
        try { RegionNbtReader.readSurfaceData(new java.io.ByteArrayInputStream(bytes.toByteArray())); }
        catch (java.io.IOException invalid) { rejected = true; }
        require(rejected, "skipped data cannot bypass the decompressed-byte limit");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void atlasBlockReadCompatibility() throws Exception {
        // Both arrays end in a partial block and include negative values.
        int size = 65;
        long[] fingerprints = new long[1031];
        int[] pixels = new int[size * size];
        var legacyBytes = new ByteArrayOutputStream();
        try (var legacy = new DataOutputStream(legacyBytes)) {
            for (int i = 0; i < fingerprints.length; i++) {
                fingerprints[i] = Long.rotateLeft(0xFEDCBA9876543210L, i);
                legacy.writeLong(fingerprints[i]);
            }
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = Integer.rotateLeft(0x89ABCDEF, i);
                legacy.writeInt(pixels[i]);
            }
        }
        byte[] bytes = legacyBytes.toByteArray();
        Method read = PersistentRenderedAtlasStore.class.getDeclaredMethod(
                "readSnapshot", DataInputStream.class, int.class, int.class);
        read.setAccessible(true);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var snapshot = (PersistentRenderedAtlasStore.Snapshot) read.invoke(
                    null, input, size, fingerprints.length);
            require(Arrays.equals(fingerprints, snapshot.fingerprints()), "bulk atlas read keeps CTA1 fingerprints");
            require(Arrays.equals(pixels, snapshot.pixels()), "bulk atlas read keeps CTA1 pixels");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 1))) {
            read.invoke(null, input, size, fingerprints.length);
            throw new AssertionError("truncated atlas payload must fail");
        } catch (InvocationTargetException failure) {
            require(failure.getCause() instanceof EOFException, "bulk atlas read rejects truncated data");
        }
    }

    private static void atlasReleaseWhileWriterBusy() throws Exception {
        var writerField = PersistentRenderedAtlasStore.class.getDeclaredField("WRITER");
        writerField.setAccessible(true);
        var writer = (ExecutorService) writerField.get(null);
        var busy = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var write = writer.submit(() -> {
            busy.countDown();
            release.await();
            return null;
        });
        var close = new FutureTask<>(() -> {
            new InGameMapTiles(null).close();
            return null;
        });
        try {
            require(busy.await(5, TimeUnit.SECONDS), "atlas writer starts");
            new Thread(close, "atlas release check").start();
            close.get(2, TimeUnit.SECONDS);
            require(!write.isDone(), "releasing view resources completes while the atlas writer is busy");
        } finally {
            release.countDown();
            write.get(5, TimeUnit.SECONDS);
        }
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
