package org.techhouse.bckg_ops;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class IdleSignal {
    private static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();

    public void signal() {
        lock.lock();
        try {
            idle.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitIdle(BooleanSupplier isIdle, long timeoutMillis) throws InterruptedException {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (!isIdle.getAsBoolean()) {
                if (remaining <= 0) {
                    return false;
                }
                final var slice = Math.min(remaining, RECHECK_NANOS);
                remaining -= slice - idle.awaitNanos(slice);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public static final class Workers<E> {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger parked = new AtomicInteger();
        private final AtomicInteger workerCount = new AtomicInteger();
        private final IdleSignal idleSignal = new IdleSignal();

        public void start(ExecutorService pool, int count, BlockingQueue<E> queue, Consumer<E> work) {
            workerCount.set(count);
            parked.set(0);
            for (var i = 0; i < count; i++) {
                pool.execute(() -> runWorker(queue, work));
            }
        }

        private void runWorker(BlockingQueue<E> queue, Consumer<E> work) {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    final E item;
                    parked.incrementAndGet();
                    try {
                        item = queue.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        parked.decrementAndGet();
                    }
                    inFlight.incrementAndGet();
                    try {
                        work.accept(item);
                    } finally {
                        inFlight.decrementAndGet();
                        idleSignal.signal();
                    }
                }
            } finally {
                workerCount.updateAndGet(live -> Math.max(0, live - 1));
                idleSignal.signal();
            }
        }

        public boolean awaitIdle(BooleanSupplier restIsIdle, long timeoutMillis) throws InterruptedException {
            return idleSignal.awaitIdle(
                    () -> restIsIdle.getAsBoolean() && inFlight.get() == 0 && parked.get() >= workerCount.get(),
                    workerCount.get() > 0 ? timeoutMillis : 0L);
        }

        public int inFlight() {
            return inFlight.get();
        }

        public void stopped() {
            workerCount.set(0);
        }
    }
}
