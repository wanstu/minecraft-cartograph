package com.wanstu.cartograph;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Deterministic bounded-queue, coalescing and actual GZIP cache stress.
 * No Minecraft client/graphics needed; NEVER claims to benchmark live TPS.
 */
public final class CacheStressSelfTest {
    private static void yes(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }

    private static void queueSemantics() throws Exception {
        BoundedCoalescingQueue<String,Integer> queue = new BoundedCoalescingQueue<>(3);
        queue.offer("one", 1);
        queue.offer("one", 2);
        yes(queue.stats().replaced() == 1, "Coalesced duplicate key");
        var first = queue.take();
        yes(first.key().equals("one") && first.value() == 2, "Newest queued page wins");
        queue.offer("one", 3); // first version is still writing
        queue.offer("two", 4);
        var second = queue.take();
        yes(second.key().equals("two"), "Cannot concurrently write the same key");
        queue.done("two"); queue.done("one");
        var last = queue.take();
        yes(last.key().equals("one") && last.value() == 3, "Latest version writes AFTER old");
        queue.done("one");
        queue.offer("a", 1); queue.offer("b", 2); queue.offer("c", 3); queue.offer("d", 4);
        yes(queue.stats().pending() == 3 && queue.stats().dropped() == 1,
                "Queue must evict only old UNSCHEDULED work at capacity");
    }

    private static void queueParallelStress() throws Exception {
        BoundedCoalescingQueue<String,Integer> queue = new BoundedCoalescingQueue<>(128);
        ConcurrentMap<String,Integer> completed = new ConcurrentHashMap<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            Thread t = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        var task = queue.take();
                        try {
                            completed.compute(task.key(), (k,old) -> {
                                if (old != null && old >= task.value())
                                    throw new AssertionError("Stale page committed after newer page");
                                return task.value();
                            });
                            Thread.sleep(1); // emulate two slow disk writers
                        } finally {
                            queue.done(task.key());
                        }
                    }
                } catch (InterruptedException done) {
                    Thread.currentThread().interrupt();
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            }, "cartograph-p1-test-worker-" + w);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        long start = System.nanoTime();
        for (int i = 1; i <= 30_000; i++) {
            // Phase 1: same 32 pages repeatedly edited (coalescing).
            // Phase 2: many new pages from 4 atlas identities (backpressure).
            String key = i <= 15_000
                    ? "repeated/atlas" + (i % 4) + "/page" + (i % 32)
                    : "world" + ((i / 512) % 2) + "/atlas" + ((i / 1024) % 4)
                            + "/page" + (i % 512);
            queue.offer(key, i);
            yes(queue.stats().pending() <= 128 && queue.stats().active() <= 2,
                    "Bounded queue exceeded its limits");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (queue.stats().pending() > 0 || queue.stats().active() > 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("Queue drain timeout");
            Thread.sleep(8);
        }
        for (Thread t : workers) {
            t.interrupt();
            t.join(300);
        }
        if (unexpected.get() != null) throw new AssertionError(unexpected.get());
        var stats = queue.stats();
        yes(stats.replaced() > 1000 && stats.dropped() > 0,
                "Stress must exercise BOTH coalescing and bounded backpressure");
        System.out.println("PASS synthetic 30,000 writes across 512 page keys: max queue=128, "
                + "coalesced=" + stats.replaced() + " dropped best-effort=" + stats.dropped()
                + " elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
    }

    private static MapNetwork.Page page(String atlas, int px, int version) {
        byte[] pixels = new byte[MapNetwork.Page.PIXELS],
                features = new byte[MapNetwork.Page.PIXELS],
                water = new byte[MapNetwork.Page.PIXELS];
        short[] heights = new short[MapNetwork.Page.PIXELS];
        pixels[0] = (byte) version;
        pixels[1] = (byte) (version >>> 8);
        pixels[2] = (byte) (version >>> 16);
        Arrays.fill(heights, (short) 96);
        return new MapNetwork.Page(atlas, "minecraft:overworld", px, 0,
                pixels, features, heights, water,
                -1L, 0L, 200, 100, 10, false, true, true, false,
                64, 64, 64, "minecraft:overworld", 0, 0,
                4, 512, 64, 0, List.of(), List.of());
    }

    private static void realDiskStress() throws Exception {
        Path dir = Files.createTempDirectory("cartograph-p1-stress-");
        try {
            HashMap<ClientPageStore.Key, Integer> latest = new HashMap<>();
            long start = System.nanoTime();
            for (int i = 0; i < 4_096; i++) {
                // Eight distinct world/atlas groups, 128 pages each:
                // 1024 genuinely different compressed files, each updated
                // four times rather than just 64 files in a tight loop.
                String world = "w" + ((i / 128) % 2);
                String atlas = "atlas" + ((i / 256) % 4);
                int px = i % 128;
                var key = new ClientPageStore.Key(world, atlas, "minecraft:overworld", px, 0);
                int version = i + 1;
                latest.put(key, version);
                ClientPageStore.save(dir, key, page(atlas, px, version));
                var stats = ClientPageStore.writeStats();
                yes(stats.pending() <= 1400 && stats.active() <= 2,
                        "Real async disk saver exceeded bounded work");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (ClientPageStore.writeStats().pending() != 0
                    || ClientPageStore.writeStats().active() != 0) {
                if (System.nanoTime() > deadline) throw new AssertionError("GZIP write drain timeout");
                Thread.sleep(10);
            }
            yes(latest.size() == 1024, "Must pressure 1024 distinct world/atlas/page files");
            for (var e : latest.entrySet()) {
                MapNetwork.Page restored =
                        ClientPageStore.read(ClientPageStore.file(dir, e.getKey()), e.getKey());
                yes(restored != null, "Last disk snapshot missing: " + e.getKey());
                int version = (restored.pixels()[0] & 255)
                        | ((restored.pixels()[1] & 255) << 8)
                        | ((restored.pixels()[2] & 255) << 16);
                yes(version == e.getValue(), "Older edit overwrote newest page: " + e.getKey());
                yes(restored.surveyedChunks() == -1L && restored.coloredChunks() == 0L
                        && restored.inkUnits() == 200 && restored.pigmentUnits() == 100,
                        "I/O altered paid survey, color or consumable masks");
            }
            var stats = ClientPageStore.writeStats();
            System.out.println("PASS 4096 REAL asynchronous full-page writes to "
                    + latest.size() + " world/atlas/page keys: latest bytes+balances restored, "
                    + "pending=0, active=0, coalesced=" + stats.replaced()
                    + " elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        } finally {
            try(var walk=Files.walk(dir)) {
                for(Path f:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        queueSemantics();
        queueParallelStress();
        realDiskStress();
    }
}
