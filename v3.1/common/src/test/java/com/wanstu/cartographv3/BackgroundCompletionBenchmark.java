package com.wanstu.cartographv3;

import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import net.minecraft.server.level.ServerPlayer;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/** Runs the actual completion path against saved-world location keys; never reads or creates terrain. */
public final class BackgroundCompletionBenchmark {
    public static void main(String[] args) throws Exception {
        List<ChunkKey> keys = new ArrayList<>();
        Method discover = GeneratedChunkBackfill.class.getDeclaredMethod(
                "discoverRegion", Path.class, String.class, int.class, int.class, List.class);
        discover.setAccessible(true);
        Pattern pattern = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
        try (var files = Files.newDirectoryStream(Path.of(args[0]), "*.mca")) {
            for (Path p : files) {
                Matcher m = pattern.matcher(p.getFileName().toString());
                if (m.matches()) discover.invoke(null, p, "minecraft:overworld",
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), keys);
            }
        }
        if (keys.size() != 312391) throw new AssertionError("Expected actual saved-world header keys");
        Class<?> stateType = Class.forName(GeneratedChunkBackfill.class.getName() + "$State");
        Constructor<?> constructor = stateType.getDeclaredConstructor(); constructor.setAccessible(true);
        Field pending = stateType.getDeclaredField("initialPending"); pending.setAccessible(true);
        Method complete = GeneratedChunkBackfill.class.getDeclaredMethod(
                "completeAttempt", ServerPlayer.class, stateType, ChunkKey.class);
        complete.setAccessible(true);
        List<Map<String,Object>> cases = new ArrayList<>();
        for (int count : new int[]{0, 4096, keys.size()}) {
            cases.add(measure(constructor, pending, complete, keys.subList(0,count),
                    new ChunkKey("minecraft:the_nether", Integer.MAX_VALUE, Integer.MAX_VALUE),
                    "completion_with_foreign_dimension_backlog"));
        }
        cases.add(measure(constructor, pending, complete, keys,
                new ChunkKey("minecraft:overworld", Integer.MAX_VALUE, Integer.MAX_VALUE),
                "completion_with_same_dimension_pending"));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("actual_header_keys",keys.size()); result.put("cases",cases);
        result.put("scope","Standalone timings of actual completeAttempt; not Minecraft frame times");
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Path output=Path.of(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output,json+"\n");System.out.println(json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> measure(Constructor<?> constructor, Field field, Method complete,
            List<ChunkKey> keys, ChunkKey completed, String name) throws Exception {
        Object state=constructor.newInstance();Object pending=field.get(state);
        if(pending instanceof Set<?> flat) ((Set<ChunkKey>)flat).addAll(keys);
        else {
            Map<String,Set<ChunkKey>> groups=(Map<String,Set<ChunkKey>>)pending;
            for(ChunkKey key:keys) groups.computeIfAbsent(key.dimension(), d->ConcurrentHashMap.newKeySet()).add(key);
        }
        // scanned stays empty: exercise the exact completion cost without publishing or requiring a game.
        for(int i=0;i<32;i++)complete.invoke(null,null,state,completed);
        ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(), allocated=bean.getThreadAllocatedBytes(thread);
        long[] timings=new long[256];
        for(int i=0;i<timings.length;i++){
            long start=System.nanoTime();complete.invoke(null,null,state,completed);
            timings[i]=System.nanoTime()-start;
        }
        allocated=bean.getThreadAllocatedBytes(thread)-allocated;
        Arrays.sort(timings);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("case",name); result.put("pending_keys",keys.size());
        result.put("pending_layout",pending instanceof Set<?> ? "flat":"by_dimension");
        result.put("operations",timings.length);
        result.put("p50_ms",timings[127]/1e6);result.put("p95_ms",timings[243]/1e6);
        result.put("max_ms",timings[255]/1e6);result.put("java_allocated_bytes",allocated);
        return result;
    }
}
