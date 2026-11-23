package com.wanstu.cartographv3;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Cooperative work on the owning tick thread; one step must stay small. */
final class TickWorkQueue {
    interface Work {
        /** Returns true when this work is complete. */
        boolean step();
    }

    private final ArrayDeque<Work> ready = new ArrayDeque<>();
    private final LongSupplier clock;

    TickWorkQueue() { this(System::nanoTime); }
    TickWorkQueue(LongSupplier clock) { this.clock = clock; }
    void add(Work work) { ready.addLast(work); }

    int drain(long budgetNanos, int maxSteps) {
        long started = clock.getAsLong();
        int steps = 0;
        while (!ready.isEmpty() && steps < maxSteps
                && clock.getAsLong() - started < budgetNanos) {
            Work work = ready.removeFirst();
            if (!work.step()) ready.addLast(work);
            steps++;
        }
        return steps;
    }

    void clear() { ready.clear(); }
}
