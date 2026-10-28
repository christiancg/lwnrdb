package org.techhouse.concurrency;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class SettleGates {
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();

    static final class Gate {
        private final Thread owner = Thread.currentThread();
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean failing;

        boolean ownedByCurrentThread() {
            return owner == Thread.currentThread();
        }

        boolean failing() {
            return failing;
        }

        void markFailing() {
            failing = true;
        }
    }

    Gate open(String lockName) {
        final var gate = new Gate();
        gates.put(lockName, gate);
        open.incrementAndGet();
        return gate;
    }

    void close(String lockName, Gate gate) {
        if (gates.remove(lockName, gate)) {
            open.decrementAndGet();
        }
        gate.released.countDown();
    }

    Gate blocking(String lockName, boolean pointRead) {
        if (open.get() == 0) {
            return null;
        }
        final var gate = gates.get(lockName);
        if (gate == null || gate.ownedByCurrentThread() || pointRead && !gate.failing) {
            return null;
        }
        return gate;
    }

    static boolean await(Gate gate, long deadlineMillis, long noDeadline) throws InterruptedException {
        if (deadlineMillis == noDeadline) {
            gate.released.await();
            return true;
        }
        return gate.released.await(Math.max(deadlineMillis - System.currentTimeMillis(), 0), TimeUnit.MILLISECONDS);
    }
}
