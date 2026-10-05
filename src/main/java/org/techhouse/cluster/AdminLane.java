package org.techhouse.cluster;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class AdminLane {
    private final ReentrantLock lane = new ReentrantLock(true);

    public boolean tryEnter(long budgetMillis) {
        try {
            return lane.tryLock(budgetMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void leave() {
        lane.unlock();
    }

    public <T> T within(long budgetMillis, Supplier<T> op, Supplier<T> onBusy) {
        if (!tryEnter(budgetMillis)) {
            return onBusy.get();
        }
        try {
            return op.get();
        } finally {
            leave();
        }
    }

    public boolean isHeldByCurrentThread() {
        return lane.isHeldByCurrentThread();
    }
}
