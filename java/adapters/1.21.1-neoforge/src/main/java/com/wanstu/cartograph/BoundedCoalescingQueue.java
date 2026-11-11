package com.wanstu.cartograph;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bounded non-blocking producers, coalesced writes by disk identity.
 *
 * Repeated edits to the same world/atlas/page overwrite its waiting snapshot
 * instead of allocating unbounded Runnable instances holding large page arrays.
 * Workers serialize a given key: the older snapshot cannot write AFTER a
 * newer one. When an extraordinary backlog exceeds capacity, only the
 * oldest UNSCHEDULED, non-authoritative local image is discarded. The server
 * remains the permanent source of survey credits and can rebuild that image.
 */
final class BoundedCoalescingQueue<K, V> {
    record Work<K,V>(K key, V value) {}
    record Stats(int pending, int active, long dropped, long replaced) {}

    private final int limit;
    private final LinkedHashMap<K,V> pending = new LinkedHashMap<>();
    private final Set<K> active = new HashSet<>();
    private long dropped, replaced;

    BoundedCoalescingQueue(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit");
        this.limit = limit;
    }

    synchronized void offer(K key, V value) {
        if (pending.containsKey(key)) {
            pending.put(key, value);
            replaced++;
        } else {
            if (pending.size() >= limit) {
                Iterator<K> it = pending.keySet().iterator();
                it.next();
                it.remove();
                dropped++;
            }
            pending.put(key, value);
        }
        notifyAll();
    }

    synchronized Work<K,V> take() throws InterruptedException {
        while (true) {
            Iterator<Map.Entry<K,V>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<K,V> e = it.next();
                if (active.contains(e.getKey())) continue;
                Work<K,V> work = new Work<>(e.getKey(), e.getValue());
                active.add(e.getKey());
                it.remove();
                return work;
            }
            wait();
        }
    }

    synchronized void done(K key) {
        if (!active.remove(key)) throw new IllegalStateException("Missing active write");
        notifyAll();
    }

    synchronized Stats stats() {
        return new Stats(pending.size(), active.size(), dropped, replaced);
    }
}
