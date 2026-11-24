package com.wanstu.cartographqa;

import com.google.gson.GsonBuilder;
import com.sun.management.OperatingSystemMXBean;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only measurement of mapping behavior; fixtures and movement stay in an isolated copied world. */
@Mod("cartograph_perf_probe")
public final class MeasurementProbe {
    private static final String[] PHASES = {"warmup_control", "control_still", "mapping_still",
            "control_move", "mapping_move", "map_near", "map_near_idle", "map_far", "map_handdrawn",
            "map_closed_idle", "control_tail", "map_live_edit"};
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static volatile Samples current;
    private Object runtime;
    private Object backend;
    private boolean initialized;
    private boolean finished;
    private int phaseIndex;
    private int clientTicks;
    private long previousFrame;
    private long clientTickStarted;
    private long serverTickStarted;
    private double homeX, homeY, homeZ;
    private volatile int editStage;
    private volatile long editStarted;
    private net.minecraft.core.BlockPos editPos;
    private net.minecraft.world.level.block.state.BlockState originalBlock;
    private int originalStateId, originalHeight;
    private final Map<String, Object> functionalChecks = new LinkedHashMap<>();

    public MeasurementProbe() { NeoForge.EVENT_BUS.register(this); }

    private static final class Samples {
        final String name;
        final long started = System.nanoTime();
        final long wallMillis = System.currentTimeMillis();
        final long cpu = OS.getProcessCpuTime();
        final long gcMillis = gcMillis();
        final long gcCount = gcCount();
        final List<Long> frames = new ArrayList<>();
        final List<Long> clients = new ArrayList<>();
        final ConcurrentLinkedQueue<Long> servers = new ConcurrentLinkedQueue<>();
        final long diskStart = counter("SurfaceService", "DISK_HITS");
        final long loadedStart = counter("SurfaceService", "LOADED_HITS");
        final long captureStart = longCounter("LoadedSurfaceReader", "capturesStarted");
        final long columnsStart = longCounter("LoadedSurfaceReader", "columnsSampled");
        final long refreshStart = longCounter("LiveSurfaceUpdates", "refreshStarts");
        volatile int serverPendingPeak, serverInFlightPeak, liveWatchersPeak, loadedCapturePeak;
        long heapPeak;
        int cachedPeak, pendingPeak;
        Samples(String name) { this.name = name; }
    }

    @SubscribeEvent
    public void clientPre(ClientTickEvent.Pre event) { clientTickStarted = System.nanoTime(); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void clientPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (finished || mc.level == null || mc.player == null) return;
        try {
            clientTicks++;
            if (!initialized) {
                if (clientTicks < 60) return;
                initialized = true;
                runtime = read(type("CartographClient"), null, "RUNTIME");
                backend = read(runtime.getClass(), runtime, "backend");
                homeX = mc.player.getX(); homeY = mc.player.getY() + 6; homeZ = mc.player.getZ();
                mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().getPlayerList().getPlayers()
                        .forEach(player -> player.setGameMode(GameType.SPECTATOR)));
                switchPhase(0);
            }
            Samples samples = current;
            samples.clients.add(System.nanoTime() - clientTickStarted);
            if (clientTicks % 20 == 0) {
                samples.heapPeak = Math.max(samples.heapPeak, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                samples.cachedPeak = Math.max(samples.cachedPeak, (int) callStatic("MapClientCache", "cachedCount"));
                samples.pendingPeak = Math.max(samples.pendingPeak, (int) callStatic("MapClientCache", "pendingCount"));
            }
            double elapsed = (System.nanoTime() - samples.started) / 1e9;
            if (samples.name.endsWith("_move") || samples.name.equals("warmup_control")) {
                if (clientTicks % 5 == 0) {
                    double duration = samples.name.equals("warmup_control") ? 20 : 25;
                    double progress = Math.min(1, elapsed / duration);
                    double offset = 48.0 * (progress < .5 ? progress * 2 : (1 - progress) * 2);
                    mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().getPlayerList().getPlayers()
                            .forEach(player -> player.teleportTo(player.serverLevel(), homeX + offset, homeY, homeZ, 0, 30)));
                }
            }
            if (samples.name.equals("map_live_edit")) checkLiveEdit(mc, elapsed);
            if (samples.name.equals("map_handdrawn") && elapsed > 8
                    && !functionalChecks.containsKey("hand_drawn_cache_reused")) checkDerivedCache(mc);
            if (elapsed < (phaseIndex == 0 ? 20 : 25)) return;
            finishPhase(samples);
            if (++phaseIndex < PHASES.length) switchPhase(phaseIndex);
            else {
                finished = true;
                if (mc.screen != null) mc.setScreen(null);
                call(backend, "close");
                writeResults(true);
                System.out.println("CARTOGRAPH_MEASUREMENT_PASS phases=" + RESULTS.size());
                mc.stop();
            }
        } catch (Throwable failure) {
            finished = true;
            failure.printStackTrace();
            System.err.println("CARTOGRAPH_MEASUREMENT_FAIL phase=" + phaseIndex);
            try { writeResults(false); } catch (Exception ignored) {}
            mc.stop();
        }
    }

    private void switchPhase(int index) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        String name = PHASES[index];
        if (name.equals("map_near_idle")) {
            current = new Samples(name);
            previousFrame = 0;
            System.out.println("CARTOGRAPH_MEASUREMENT_PHASE " + name + " " + Instant.now());
            return;
        }
        if (mc.screen != null) mc.setScreen(null);
        boolean control = name.startsWith("control_") || name.equals("warmup_control");
        if (control) {
            call(backend, "close");
            // Keep the collector closed while CartographRuntime observes this same active world.
            field(backend.getClass(), "worldActive").setBoolean(backend, true);
        } else {
            field(backend.getClass(), "worldActive").setBoolean(backend, false);
            call(backend, "setWorldActive", true);
        }
        mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().getPlayerList().getPlayers()
                .forEach(player -> player.teleportTo(player.serverLevel(), homeX, homeY, homeZ, 0, 30)));
        if (name.startsWith("map_") && !name.equals("map_closed_idle")) {
            Object satellite = Enum.valueOf((Class) type("MapDrawingMode"), name.equals("map_handdrawn") ? "HAND_DRAWN" : "SATELLITE");
            invoke(type("CartographSettings"), null, "setDrawingMode", satellite);
            invoke(type("CartographSettings"), null, "setVisualStyle", name.equals("map_handdrawn") ? "ancient" : "modern");
            Object inGame = Enum.valueOf((Class) type("CartographFrontendTarget"), "IN_GAME");
            call(runtime, "openFrontend", inGame);
            Object camera = read(mc.screen.getClass(), mc.screen, "camera");
            if (name.equals("map_far") || name.equals("map_handdrawn")) {
                Class<?> position = type("InGameMapCamera$Position");
                Constructor<?> constructor = position.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                Object value = constructor.newInstance(mc.level.dimension().location().toString(), homeX, homeZ, 1.0 / 16);
                call(camera, "restore", value);
            }
        }
        current = new Samples(name);
        previousFrame = 0;
        System.out.println("CARTOGRAPH_MEASUREMENT_PHASE " + name + " " + Instant.now());
    }

    @SubscribeEvent
    public void framePre(RenderFrameEvent.Pre event) {
        Samples samples = current;
        if (samples == null || finished) return;
        long now = System.nanoTime();
        if (previousFrame != 0) samples.frames.add(now - previousFrame);
        previousFrame = now;
    }

    @SubscribeEvent
    public void serverPre(ServerTickEvent.Pre event) { serverTickStarted = System.nanoTime(); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void serverPost(ServerTickEvent.Post event) {
        Samples samples = current;
        if (samples == null || finished) return;
        samples.servers.add(System.nanoTime() - serverTickStarted);
        if (event.getServer().getTickCount() % 20 != 0) return;
        try {
            Map<?, ?> queues = (Map<?, ?>) read(type("TileRequestScheduler"), null, "QUEUES");
            int pending = 0, flight = 0;
            for (Object queue : queues.values()) {
                pending += ((Map<?, ?>) read(queue.getClass(), queue, "pending")).size();
                flight += ((Set<?>) read(queue.getClass(), queue, "inFlight")).size();
            }
            samples.serverPendingPeak = Math.max(samples.serverPendingPeak, pending);
            samples.serverInFlightPeak = Math.max(samples.serverInFlightPeak, flight);
            samples.liveWatchersPeak = Math.max(samples.liveWatchersPeak,
                    ((Map<?, ?>) read(type("LiveSurfaceUpdates"), null, "WATCHERS")).size());
            samples.loadedCapturePeak = Math.max(samples.loadedCapturePeak,
                    ((Map<?, ?>) read(type("LoadedSurfaceReader"), null, "PENDING")).size());
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private void finishPhase(Samples samples) {
        long ended = System.nanoTime();
        double seconds = (ended - samples.started) / 1e9;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", samples.name); result.put("start_epoch_ms", samples.wallMillis);
        result.put("end_epoch_ms", System.currentTimeMillis()); result.put("seconds", seconds);
        result.put("frame", distribution(samples.frames)); result.put("client_tick", distribution(samples.clients));
        result.put("server_tick", distribution(samples.servers));
        double cpuCores = (double) (OS.getProcessCpuTime() - samples.cpu) / (ended - samples.started);
        result.put("process_cpu_core_equivalents", cpuCores);
        result.put("process_cpu_per_jvm_processor_percent", 100.0 * cpuCores / OS.getAvailableProcessors());
        result.put("gc_ms", gcMillis() - samples.gcMillis); result.put("gc_count", gcCount() - samples.gcCount);
        result.put("heap_peak_bytes", samples.heapPeak); result.put("cached_peak", samples.cachedPeak);
        result.put("client_pending_peak", samples.pendingPeak); result.put("server_pending_peak", samples.serverPendingPeak);
        result.put("server_in_flight_peak", samples.serverInFlightPeak); result.put("live_watchers_peak", samples.liveWatchersPeak);
        result.put("loaded_capture_peak", samples.loadedCapturePeak);
        result.put("disk_reads", counter("SurfaceService", "DISK_HITS") - samples.diskStart);
        result.put("loaded_reads", counter("SurfaceService", "LOADED_HITS") - samples.loadedStart);
        result.put("loaded_capture_starts", delta("LoadedSurfaceReader", "capturesStarted", samples.captureStart));
        result.put("sampled_columns", delta("LoadedSurfaceReader", "columnsSampled", samples.columnsStart));
        result.put("live_refresh_starts", delta("LiveSurfaceUpdates", "refreshStarts", samples.refreshStart));
        RESULTS.add(result);
        try { writeResults(false); } catch (Exception failure) { throw new RuntimeException(failure); }
        System.out.println("CARTOGRAPH_MEASUREMENT_RESULT " + new GsonBuilder().create().toJson(result));
    }

    private static Map<String, Object> distribution(Collection<Long> input) {
        long[] values = input.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("n", values.length);
        if (values.length == 0) return result;
        result.put("p50_ms", percentile(values, .50)); result.put("p95_ms", percentile(values, .95));
        result.put("p99_ms", percentile(values, .99)); result.put("max_ms", values[values.length - 1] / 1e6);
        result.put("over_50ms", Arrays.stream(values).filter(v -> v > 50_000_000L).count());
        result.put("over_100ms", Arrays.stream(values).filter(v -> v > 100_000_000L).count());
        return result;
    }
    private static double percentile(long[] values, double p) { return values[Math.min(values.length - 1, (int) Math.ceil(p * values.length) - 1)] / 1e6; }
    private void writeResults(boolean complete) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>(); root.put("schema_version", 2); root.put("complete", complete);
        root.put("control", "CartographBackendRuntime closed; worldActive latched true to keep collection paused");
        root.put("jvm_available_processors", OS.getAvailableProcessors()); root.put("os", System.getProperty("os.name"));
        root.put("java", System.getProperty("java.version")); root.put("phases", RESULTS);
        root.put("functional_checks", functionalChecks);
        Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("performance-results.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    private void checkDerivedCache(Minecraft mc) throws Exception {
        long before = longCounter("MapPresentationRenderer", "handDrawnBuilds");
        if (before < 0) { functionalChecks.put("hand_drawn_cache_reused", "unavailable in baseline"); return; }
        Object key = type("ChunkKey").getConstructor(String.class, int.class, int.class).newInstance(
                mc.level.dimension().location().toString(), Math.floorDiv((int) Math.floor(homeX), 16),
                Math.floorDiv((int) Math.floor(homeZ), 16));
        Object tile = invoke(type("MapClientCache"), null, "tile", key);
        if (tile == null) return;
        invoke(type("MapPresentationRenderer"), null, "renderPixels", tile, 1);
        long first = longCounter("MapPresentationRenderer", "handDrawnBuilds");
        int[] actual = (int[]) invoke(type("MapPresentationRenderer"), null, "renderPixels", tile, 4);
        long second = longCounter("MapPresentationRenderer", "handDrawnBuilds");
        Object derived = invoke(type("HandDrawnMapData"), null, "from", tile);
        Object style = callStatic("CartographSettings", "visualStyle");
        Object processor = call(style, "processor");
        int[] reference = (int[]) invoke(type("MapVisualStyleProcessor"), processor,
                "renderHandDrawn", derived, 4);
        if (second != first || first - before > 1 || !Arrays.equals(reference, actual))
            throw new AssertionError("derived cache did not preserve pixels and reuse across strides");
        functionalChecks.put("hand_drawn_cache_reused", true);
        functionalChecks.put("hand_drawn_cached_pixels_match_reference", true);
    }

    private void checkLiveEdit(Minecraft mc, double elapsed) throws Exception {
        if (elapsed < 2) return;
        Object key = type("ChunkKey").getConstructor(String.class, int.class, int.class).newInstance(
                mc.level.dimension().location().toString(), Math.floorDiv((int) Math.floor(homeX), 16),
                Math.floorDiv((int) Math.floor(homeZ), 16));
        Object tile = invoke(type("MapClientCache"), null, "tile", key);
        if (tile == null) {
            if (elapsed > 23) functionalChecks.put("live_edit_error", "center surface never arrived");
            return;
        }
        int column = Math.floorMod((int) Math.floor(homeZ), 16) * 16
                + Math.floorMod((int) Math.floor(homeX), 16);
        if (editStage == 0) {
            originalStateId = (int) call(tile, "stateIdAt", column);
            originalHeight = (int) call(tile, "surfaceHeightAt", column);
            int y = Math.max(originalHeight + 8, mc.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                    (int) Math.floor(homeX), (int) Math.floor(homeZ)) + 8);
            editPos = new net.minecraft.core.BlockPos((int) Math.floor(homeX), y, (int) Math.floor(homeZ));
            editStage = -1;
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().serverLevel();
                originalBlock = level.getBlockState(editPos);
                level.setBlock(editPos, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                editStarted = System.nanoTime(); editStage = 1;
            });
        } else if (editStage == 1
                && (int) call(tile, "surfaceHeightAt", column) == editPos.getY()
                && (int) call(tile, "stateIdAt", column) == net.minecraft.world.level.block.Block.getId(
                        net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState())) {
            functionalChecks.put("place_to_client_ms", (System.nanoTime() - editStarted) / 1e6);
            editStage = -2;
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().serverLevel();
                level.setBlock(editPos, originalBlock, 3);
                editStarted = System.nanoTime(); editStage = 2;
            });
        } else if (editStage == 2
                && (int) call(tile, "surfaceHeightAt", column) == originalHeight
                && (int) call(tile, "stateIdAt", column) == originalStateId) {
            functionalChecks.put("restore_to_client_ms", (System.nanoTime() - editStarted) / 1e6);
            functionalChecks.put("live_edits_passed", true); editStage = 3;
        } else if (editStage == 3) {
            mc.setScreen(null);
            editStage = 4;
        } else if (editStage == 4 && !(boolean) callStatic("LiveSurfaceUpdates", "active")) {
            editStage = -4;
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().serverLevel();
                level.setBlock(editPos, net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                editStarted = System.nanoTime(); editStage = 5;
            });
        } else if (editStage == 5 && System.nanoTime() - editStarted > 500_000_000L) {
            Object inGame = Enum.valueOf((Class) type("CartographFrontendTarget"), "IN_GAME");
            call(runtime, "openFrontend", inGame);
            editStarted = System.nanoTime(); editStage = 6;
        } else if (editStage == 6
                && (int) call(tile, "surfaceHeightAt", column) == editPos.getY()
                && (int) call(tile, "stateIdAt", column) == net.minecraft.world.level.block.Block.getId(
                        net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState())) {
            functionalChecks.put("closed_edit_reopen_to_client_ms", (System.nanoTime() - editStarted) / 1e6);
            editStage = -6;
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().serverLevel();
                level.setBlock(editPos, originalBlock, 3);
                editStarted = System.nanoTime(); editStage = 7;
            });
        } else if (editStage == 7
                && (int) call(tile, "surfaceHeightAt", column) == originalHeight
                && (int) call(tile, "stateIdAt", column) == originalStateId) {
            functionalChecks.put("closed_edit_restore_to_client_ms", (System.nanoTime() - editStarted) / 1e6);
            functionalChecks.put("closed_edits_passed", true); editStage = 8;
        }
        if (elapsed > 23 && editStage != 8) {
            functionalChecks.put("closed_edits_passed", false);
            if (editStage < 3) functionalChecks.put("live_edits_passed", false);
            if (editPos != null && originalBlock != null) mc.getSingleplayerServer().execute(() ->
                mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst().serverLevel()
                    .setBlock(editPos, originalBlock, 3));
        }
    }

    private static long longCounter(String owner, String name) {
        try { return field(type(owner), name).getLong(null); }
        catch (Exception unavailable) { return -1; }
    }
    private static long delta(String owner, String name, long before) {
        return before < 0 ? -1 : longCounter(owner, name) - before;
    }

    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(v -> Math.max(0, v.getCollectionTime())).sum(); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(v -> Math.max(0, v.getCollectionCount())).sum(); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("com.wanstu.cartographv3." + name); }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object read(Class<?> type, Object target, String name) throws Exception { return field(type, name).get(target); }
    private static Object callStatic(String name, String method) throws Exception { return invoke(type(name), null, method); }
    private static Object call(Object target, String method, Object... args) throws Exception { return invoke(target.getClass(), target, method, args); }
    private static Object invoke(Class<?> type, Object target, String name, Object... args) throws Exception {
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            method.setAccessible(true); return method.invoke(target, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static long counter(String type, String field) {
        try { return ((AtomicInteger) read(type(type), null, field)).get(); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
}
