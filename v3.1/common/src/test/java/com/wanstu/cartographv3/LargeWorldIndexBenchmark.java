package com.wanstu.cartographv3;

import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Optional benchmark of real MCA location headers; it never reads or invents terrain. */
public final class LargeWorldIndexBenchmark {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        int expected = Integer.parseInt(args[2]);
        var executor = Executors.newSingleThreadExecutor(task ->
                new Thread(task, "cartograph-real-header-benchmark"));
        Map<String, Object> stats;
        try {
            stats = executor.submit(() -> scan(input, expected)).get();
        } finally {
            executor.shutdown();
        }
        StringBuilder json = new StringBuilder("{\n");
        for (var entry : stats.entrySet()) {
            if (json.length() > 2) json.append(",\n");
            json.append("  \"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String) json.append('"').append(value).append('"');
            else json.append(value);
        }
        json.append("\n}\n");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, json);
        System.out.println(json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scan(Path input, int expected) throws Exception {
        Method discover = GeneratedChunkBackfill.class.getDeclaredMethod(
                "discoverRegion", Path.class, String.class, int.class, int.class, List.class);
        discover.setAccessible(true);
        List<ChunkKey> keys = new ArrayList<>();
        Pattern pattern = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
        long started = System.nanoTime();
        int regions = 0;
        try (var files = Files.newDirectoryStream(input, "*.mca")) {
            for (Path file : files) {
                Matcher matcher = pattern.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                discover.invoke(null, file, "minecraft:overworld",
                        Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), keys);
                regions++;
            }
        }
        long scanned = System.nanoTime();
        keys.sort(Comparator.comparingLong(key ->
                Math.max(Math.abs((long) key.x()), Math.abs((long) key.z()))));
        long sorted = System.nanoTime();
        Class<?> stateType = Class.forName(GeneratedChunkBackfill.class.getName() + "$State");
        Constructor<?> constructor = stateType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        Field initial = stateType.getDeclaredField("initialPending");
        initial.setAccessible(true);
        ((Set<ChunkKey>) initial.get(state)).addAll(keys);
        Method add = GeneratedChunkBackfill.class.getDeclaredMethod("add", stateType, ChunkKey.class);
        add.setAccessible(true);
        for (ChunkKey key : keys) add.invoke(null, state, key);
        long populated = System.nanoTime();
        Field ready = stateType.getDeclaredField("ready");
        ready.setAccessible(true);
        Map<String, Queue<ChunkKey>> queues = (Map<String, Queue<ChunkKey>>) ready.get(state);
        int queued = queues.get("minecraft:overworld").size();
        if (keys.size() != expected || queued != expected
                || ((Set<?>) initial.get(state)).size() != expected)
            throw new AssertionError("Real header index lost or duplicated chunks");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("regions", regions);
        result.put("discovered_chunks", keys.size());
        result.put("queued_chunks", queued);
        result.put("header_scan_ms", (scanned - started) / 1e6);
        result.put("priority_sort_ms", (sorted - scanned) / 1e6);
        result.put("queue_population_ms", (populated - sorted) / 1e6);
        result.put("total_ms", (populated - started) / 1e6);
        result.put("heap_used_bytes_observed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("worker", Thread.currentThread().getName());
        return result;
    }
}
