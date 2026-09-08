package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRegistry;

public class ScriptRunRegistryTest {

    @Test
    public void test_register_returns_unique_run_ids() {
        final var registry = new ScriptRunRegistry();
        final var first = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "alice", null);
        final var second = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "alice", null);
        assertNotEquals(first.runId(), second.runId());
        assertEquals(first.runId(), UUID.fromString(first.runId()).toString());
    }

    @Test
    public void test_list_reflects_registrations() {
        final var registry = new ScriptRunRegistry();
        final var clientId = UUID.randomUUID();
        final var before = System.currentTimeMillis();
        final var run = registry.register(ScriptRunKind.CALL_PROCEDURE, "shop", "reprice", "alice", clientId);
        assertEquals(1, registry.list().size());
        final var listed = registry.list().getFirst();
        assertSame(run, listed);
        assertEquals(ScriptRunKind.CALL_PROCEDURE, listed.kind());
        assertEquals("shop", listed.database());
        assertEquals("reprice", listed.name());
        assertEquals("alice", listed.username());
        assertEquals(clientId, listed.clientId());
        assertTrue(listed.startedAt() >= before);
        assertSame(Thread.currentThread(), listed.thread());
        assertFalse(listed.isCancelled());
    }

    // An ad-hoc RUN_SCRIPT has no name, which must not stop it from being listed
    @Test
    public void test_registers_a_run_with_no_name() {
        final var registry = new ScriptRunRegistry();
        registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        assertNull(registry.list().getFirst().name());
    }

    @Test
    public void test_unregister_removes() {
        final var registry = new ScriptRunRegistry();
        final var run = registry.register(ScriptRunKind.TRIGGER, "db", "audit", "definer", null);
        registry.unregister(run.runId());
        assertTrue(registry.list().isEmpty());
        assertEquals(0, registry.size());
        registry.unregister(run.runId());
        registry.unregister(null);
        assertEquals(0, registry.size());
    }

    @Test
    public void test_size_matches_registrations() {
        final var registry = new ScriptRunRegistry();
        assertEquals(0, registry.size());
        final var first = registry.register(ScriptRunKind.SCHEDULE, "db", "nightly", "definer", null);
        assertEquals(1, registry.size());
        registry.register(ScriptRunKind.SCHEDULE, "db", "hourly", "definer", null);
        assertEquals(2, registry.size());
        registry.unregister(first.runId());
        assertEquals(1, registry.size());
    }

    @Test
    public void test_cancel_sets_flag_and_returns_true() {
        final var registry = new ScriptRunRegistry();
        final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "alice", null);
        assertTrue(registry.cancel(run.runId()));
        assertTrue(run.isCancelled());
        // The run stays listed until it notices and unregisters itself
        assertEquals(1, registry.size());
    }

    @Test
    public void test_cancel_unknown_run_returns_false() {
        final var registry = new ScriptRunRegistry();
        assertFalse(registry.cancel(UUID.randomUUID().toString()));
        assertFalse(registry.cancel(null));
    }

    @Test
    public void test_cancelled_counter_increments_only_on_hit() {
        final var registry = new ScriptRunRegistry();
        assertEquals(0L, registry.getCancelled());
        registry.cancel(UUID.randomUUID().toString());
        assertEquals(0L, registry.getCancelled());
        final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "alice", null);
        registry.cancel(run.runId());
        assertEquals(1L, registry.getCancelled());
        // Two operators racing on the same id: one true, one false, no exception
        assertTrue(registry.cancel(run.runId()));
        assertEquals(2L, registry.getCancelled());
        registry.unregister(run.runId());
        assertFalse(registry.cancel(run.runId()));
        assertEquals(2L, registry.getCancelled());
    }

    // A run parked in the event loop must wake at once rather than waiting out its poll interval
    @Test
    public void test_cancel_unparks_waiting_thread() throws Exception {
        final var registry = new ScriptRunRegistry();
        final var parked = new CountDownLatch(1);
        final var woke = new AtomicBoolean();
        final var done = new CountDownLatch(1);
        final var runId = new AtomicReference<String>();
        final var worker = Thread.ofVirtual().start(() -> {
            final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "alice", null);
            runId.set(run.runId());
            parked.countDown();
            while (!run.isCancelled()) {
                LockSupport.park();
            }
            woke.set(true);
            done.countDown();
        });
        assertTrue(parked.await(30, TimeUnit.SECONDS));
        // Give the worker a moment to actually be parked, so the unpark is the thing that wakes it
        Thread.sleep(50);
        assertTrue(registry.cancel(runId.get()));
        assertTrue(done.await(30, TimeUnit.SECONDS), "the parked thread was never woken by cancel");
        worker.join();
        assertTrue(woke.get());
    }

    @Test
    public void test_concurrent_register_unregister_is_consistent() throws Exception {
        final var registry = new ScriptRunRegistry();
        final var threads = 200;
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(threads);
        final var workers = new ArrayList<Thread>();
        for (var i = 0; i < threads; i++) {
            workers.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "db", null, "alice", null);
                    registry.unregister(run.runId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        for (final var worker : workers) {
            worker.join();
        }
        assertEquals(0, registry.size());
        assertTrue(registry.list().isEmpty());
    }
}
