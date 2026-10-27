package org.techhouse.concurrency;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SettleWindow implements AutoCloseable {
    private final ResourceLocking locking;
    private final String lockName;
    private final SettleGates.Gate gate;
    private volatile ReentrantReadWriteLock held;
    private volatile boolean writeHeld;
    private volatile boolean closed;

    SettleWindow(ResourceLocking locking, String lockName, SettleGates.Gate gate, ReentrantReadWriteLock held) {
        this.locking = locking;
        this.lockName = lockName;
        this.gate = gate;
        this.held = held;
    }

    public static SettleWindow exclusive() {
        return new SettleWindow(null, null, null, null);
    }

    public boolean shared() {
        return gate != null && !closed && !gate.failing();
    }

    public void fail() {
        if (notOwnedByCaller() || gate.failing()) {
            return;
        }
        gate.markFailing();
        held.readLock().unlock();
        held = locking.relockExclusive(lockName);
        writeHeld = true;
    }

    @Override
    public void close() {
        if (notOwnedByCaller()) {
            return;
        }
        closed = true;
        locking.closeGate(lockName, gate);
        if (writeHeld) {
            held.writeLock().unlock();
        } else {
            held.readLock().unlock();
        }
    }

    private boolean notOwnedByCaller() {
        return gate == null || closed || !gate.ownedByCurrentThread();
    }
}
