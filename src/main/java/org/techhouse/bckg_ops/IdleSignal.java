package org.techhouse.bckg_ops;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Lets a shutdown drain wait for the workers to go idle instead of polling them. A worker signals after every
 * unit of work; the predicate is evaluated under the same lock the signal takes, so a worker finishing between
 * two checks cannot be missed. The wait is still sliced, because the queue can also be emptied by something
 * that never signals (a concurrent stop clears it), and that must not cost the whole budget.
 */
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
