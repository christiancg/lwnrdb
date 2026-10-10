package org.techhouse.fault;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ArmedFault {
    private final FaultAction action;
    private final String match;
    private final long delayMs;
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicBoolean fired = new AtomicBoolean();

    ArmedFault(FaultAction action, String match, long delayMs) {
        this.action = action;
        this.match = match;
        this.delayMs = delayMs;
    }

    FaultAction action() {
        return action;
    }

    boolean tryFire(String subject) {
        if ((match != null && !match.equals(subject)) || !fired.compareAndSet(false, true)) {
            return false;
        }
        reached.countDown();
        return true;
    }

    void hold(long pauseLimitMs) {
        awaitQuietly(released, action == FaultAction.DELAY ? delayMs : pauseLimitMs);
    }

    boolean awaitReached(long timeoutMs) {
        return awaitQuietly(reached, timeoutMs);
    }

    void release() {
        released.countDown();
    }

    private static boolean awaitQuietly(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
