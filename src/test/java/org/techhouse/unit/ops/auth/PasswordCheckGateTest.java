package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.auth.PasswordCheckGate;
import org.techhouse.test.Await;
import org.techhouse.test.TestUtils;

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

    @Test
    public void work_runs_on_a_named_daemon_platform_thread() throws Exception {
        final var gate = new PasswordCheckGate(2);
        final var worker = new AtomicReference<Thread>();
        final var caller = Thread.ofVirtual().start(() -> {
            try {
                gate.run(() -> {
                    worker.set(Thread.currentThread());
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        caller.join(10_000);

        assertFalse(worker.get().isVirtual());
        assertTrue(worker.get().isDaemon());
        assertTrue(worker.get().getName().startsWith(PasswordCheckGate.THREAD_NAME_PREFIX), worker.get().getName());
    }

    @Test
    public void interrupted_waiter_cancels_the_work_and_frees_its_permit() throws Exception {
        final var gate = new PasswordCheckGate(1);
        final var started = new CountDownLatch(1);
        final var workerInterrupted = new CountDownLatch(1);
        final var thrown = new AtomicReference<Throwable>();
        final var caller = Thread.ofVirtual().start(() -> {
            try {
                gate.run(() -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        workerInterrupted.countDown();
                    }
                    return null;
                });
            } catch (InterruptedException e) {
                thrown.set(e);
            }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(10_000);

        assertInstanceOf(InterruptedException.class, thrown.get());
        assertTrue(workerInterrupted.await(10, TimeUnit.SECONDS), "the cancelled work must be interrupted");
        assertEquals("next", gate.run(() -> "next"));
    }

    @Test
    public void interrupted_while_waiting_for_a_permit_never_submits() throws Exception {
        final var gate = new PasswordCheckGate(1);
        final var permits = TestUtils.getPrivateField(gate, "permits", Semaphore.class);
        permits.acquire();
        final var ran = new AtomicBoolean();
        final var thrown = new AtomicReference<Throwable>();
        final var caller = Thread.ofVirtual().start(() -> {
            try {
                gate.run(() -> {
                    ran.set(true);
                    return null;
                });
            } catch (InterruptedException e) {
                thrown.set(e);
            }
        });
        assertTrue(Await.parked(caller, Thread.State.WAITING, 10_000), "the caller never waited for the hash");
        caller.interrupt();
        caller.join(10_000);
        permits.release();

        assertInstanceOf(InterruptedException.class, thrown.get());
        assertFalse(ran.get());
    }

    @Test
    public void an_error_from_the_work_reaches_the_caller_unchanged() {
        final var gate = new PasswordCheckGate(1);
        final var error = new AssertionError("hash failed");

        assertSame(error, assertThrows(AssertionError.class, () -> gate.run(() -> {
            throw error;
        })));
    }

    @Test
    public void a_checked_exception_from_the_work_is_wrapped() {
        final var gate = new PasswordCheckGate(1);
        final var cause = new IOException("disk");

        final var thrown = assertThrows(IllegalStateException.class, () -> gate.run(() -> sneakyThrow(cause)));

        assertSame(cause, thrown.getCause());
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
