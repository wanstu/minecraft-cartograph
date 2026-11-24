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
            "control_move", "mapping_move", "map_near", "map_far", "map_handdrawn",
            "map_closed_idle", "control_tail"};
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
            if (!name.equals("map_near")) {
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

    private static void finishPhase(Samples samples) {
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
    private static void writeResults(boolean complete) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>(); root.put("schema_version", 2); root.put("complete", complete);
        root.put("control", "CartographBackendRuntime closed; worldActive latched true to keep collection paused");
        root.put("jvm_available_processors", OS.getAvailableProcessors()); root.put("os", System.getProperty("os.name"));
        root.put("java", System.getProperty("java.version")); root.put("phases", RESULTS);
        Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("performance-results.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root));
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
