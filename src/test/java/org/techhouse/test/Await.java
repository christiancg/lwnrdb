package org.techhouse.test;

import java.util.function.BooleanSupplier;

public final class Await {
    private static final long POLL_MS = 5L;

    private Await() {
    }

    @SuppressWarnings("BusyWait")
    public static boolean until(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(POLL_MS);
        }
        return true;
    }

    public static boolean parked(Thread thread, Thread.State state, long timeoutMs) throws InterruptedException {
        return until(() -> thread.getState() == state, timeoutMs);
    }
}
