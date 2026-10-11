package org.techhouse.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LockHolder implements AutoCloseable {
    private static final long PARK_BUDGET_MS = 10_000L;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Deque<Runnable> releases = new ArrayDeque<>();

    public <T> T run(Callable<T> action) throws Exception {
        return executor.submit(action).get(30, TimeUnit.SECONDS);
    }

    public void hold(Callable<Boolean> acquire, Runnable release) throws Exception {
        assertTrue(run(acquire), "the holder never took the lock");
        releases.push(release);
    }

    public void release() throws Exception {
        final var release = releases.pop();
        run(() -> {
            release.run();
            return null;
        });
    }

    @SuppressWarnings("BusyWait")
    public static <T> Thread startParked(Callable<T> action, AtomicReference<T> result) throws InterruptedException {
        final var thread = new Thread(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        final var deadline = System.currentTimeMillis() + PARK_BUDGET_MS;
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the request never parked on the held lock");
            Thread.sleep(5);
        }
        return thread;
    }

    @Override
    public void close() throws Exception {
        while (!releases.isEmpty()) {
            release();
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
