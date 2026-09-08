package org.techhouse.bckg_ops;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

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
}
