package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.auth.PasswordCheckGate;

public class PasswordCheckGateTest {

    @Test
    public void never_runs_more_than_permits_concurrently() throws Exception {
        final var permits = 3;
        final var gate = new PasswordCheckGate(permits);
        final var running = new AtomicInteger();
        final var peak = new AtomicInteger();
        final var threads = new ArrayList<Thread>();
        for (var i = 0; i < 24; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    gate.run(() -> {
                        final var current = running.incrementAndGet();
                        peak.accumulateAndGet(current, Math::max);
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        running.decrementAndGet();
                        return null;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (final var thread : threads) {
            thread.join();
        }
        assertTrue(peak.get() <= permits, "peak concurrency was " + peak.get());
        assertTrue(peak.get() >= 1);
    }

    @Test
    public void permit_released_when_work_throws() throws Exception {
        final var gate = new PasswordCheckGate(1);
        assertThrows(IllegalStateException.class, () -> gate.run(() -> {
            throw new IllegalStateException("boom");
        }));
        final var done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                gate.run(() -> {
                    done.countDown();
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "the permit must be back after a throwing run");
    }

    @Test
    public void zero_config_sizes_to_available_processors() {
        assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors()), new PasswordCheckGate(0).size());
        assertEquals(7, new PasswordCheckGate(7).size());
    }

    @Test
    public void returns_the_work_result() throws Exception {
        assertEquals("done", new PasswordCheckGate(2).run(() -> "done"));
    }
}
