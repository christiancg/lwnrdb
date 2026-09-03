package org.techhouse.ops;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Configuration;

/**
 * Bounds how many client-initiated script runs - RUN_SCRIPT and CALL_PROCEDURE - hold an interpreter on this
 * node at once. The per-run limits bound one run; this bounds their sum, so the node's worst-case script heap
 * is the capacity times {@code scriptMaxMemoryBytes} rather than the number of connected clients times it.
 *
 * <p>Triggers and scheduled procedures are deliberately exempt: they are already bounded by their own worker
 * pools ({@code triggerThreads}/{@code scheduleThreads}), and a trigger refused for want of a permit would be
 * a <em>dropped</em> trigger rather than a retried one - its pending-run record is consumed by the transaction
 * that applies its effects, so nothing would replay it.
 *
 * <p>The capacity is read once, in the constructor: {@code Configuration} is loaded at startup and never
 * reloaded, so there is no live-reload path to honour here. {@link #reconfigure(int, long)} is the single
 * exception and the only thing that makes these fields mutable.
 */
public class ScriptAdmission {
    private volatile int capacity;
    private volatile long waitMs;
    // Fair, so a burst of arrivals cannot indefinitely overtake a thread already waiting and turn the
    // bounded wait into an unbounded one.
    private volatile Semaphore permits;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();

    public ScriptAdmission() {
        this(Configuration.getInstance().getMaxConcurrentScripts(), Configuration.getInstance().getScriptQueueWaitMs());
    }

    public ScriptAdmission(int capacity, long waitMs) {
        this.capacity = capacity;
        this.waitMs = waitMs;
        this.permits = capacity > 0 ? new Semaphore(capacity, true) : null;
    }

    /**
     * @return {@code true} when the caller holds a permit and must {@link #release()} it in a
     *         {@code finally}; {@code false} when the caller should answer {@code 503-6} without releasing.
     */
    public boolean tryAcquire() {
        final var pool = permits;
        if (pool == null) {
            return true;
        }
        // The uncontended fast path first, so an idle node never pays for a timed park.
        if (pool.tryAcquire()) {
            return true;
        }
        try {
            if (pool.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                waited.incrementAndGet();
                return true;
            }
            rejected.incrementAndGet();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Resizes the pool and zeroes its counters. Nothing in production calls this - the capacity is fixed at
     * startup, as above - but a test needs a small, known cap, and the operation helpers hold this singleton
     * in {@code static final} fields, so the instance cannot be swapped for a differently-sized one. Having
     * it here rather than letting tests reflect into the fields keeps them off private names and off
     * reflective {@code final}-field writes, which a future JDK will refuse.
     */
    public void reconfigure(int newCapacity, long newWaitMs) {
        capacity = newCapacity;
        waitMs = newWaitMs;
        permits = newCapacity > 0 ? new Semaphore(newCapacity, true) : null;
        rejected.set(0L);
        waited.set(0L);
    }

    public void release() {
        final var pool = permits;
        if (pool != null) {
            pool.release();
        }
    }

    public int available() {
        final var pool = permits;
        return pool == null ? 0 : pool.availablePermits();
    }

    public int capacity() {
        return capacity;
    }

    public long getRejected() {
        return rejected.get();
    }

    public long getWaited() {
        return waited.get();
    }
}
