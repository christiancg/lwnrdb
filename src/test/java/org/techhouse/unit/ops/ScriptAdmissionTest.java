package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.ScriptAdmission;

public class ScriptAdmissionTest {

    @Test
    public void test_unlimited_when_capacity_zero() {
        final var admission = new ScriptAdmission(0, 1_000L);
        assertEquals(0, admission.capacity());
        for (var i = 0; i < 50; i++) {
            assertTrue(admission.tryAcquire());
        }
        assertEquals(0, admission.available());
        admission.release();
        assertEquals(0, admission.getRejected());
        assertEquals(0, admission.getWaited());
    }

    @Test
    public void test_acquires_up_to_capacity() {
        final var admission = new ScriptAdmission(2, 0L);
        assertEquals(2, admission.capacity());
        assertEquals(2, admission.available());
        assertTrue(admission.tryAcquire());
        assertTrue(admission.tryAcquire());
        assertEquals(0, admission.available());
    }

    @Test
    public void test_rejects_after_wait_elapses() throws Exception {
        final var admission = new ScriptAdmission(1, 50L);
        final var held = new CountDownLatch(1);
        final var releaseNow = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            assertTrue(admission.tryAcquire());
            held.countDown();
            try {
                assertTrue(releaseNow.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                admission.release();
            }
        });
        assertTrue(held.await(30, TimeUnit.SECONDS));
        final var start = System.nanoTime();
        assertFalse(admission.tryAcquire());
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        releaseNow.countDown();
        assertTrue(elapsedMs >= 50L, "returned after only " + elapsedMs + "ms, so it did not wait");
        assertEquals(1, admission.getRejected());
        assertEquals(0, admission.getWaited());
    }

    @Test
    public void test_acquires_after_waiting_for_release() throws Exception {
        final var admission = new ScriptAdmission(1, 2_000L);
        final var held = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            assertTrue(admission.tryAcquire());
            held.countDown();
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                admission.release();
            }
        });
        assertTrue(held.await(30, TimeUnit.SECONDS));
        assertTrue(admission.tryAcquire());
        assertEquals(1, admission.getWaited());
        assertEquals(0, admission.getRejected());
    }

    @Test
    public void test_release_restores_the_permit() {
        final var admission = new ScriptAdmission(1, 0L);
        assertTrue(admission.tryAcquire());
        assertFalse(admission.tryAcquire());
        admission.release();
        assertEquals(1, admission.available());
        assertTrue(admission.tryAcquire());
    }

    // Fairness is what keeps the bounded wait bounded: an unfair semaphore would let the later arrival
    // barge ahead of a thread that has already been parked.
    @Test
    public void test_permits_are_fair() throws Exception {
        final var admission = new ScriptAdmission(1, 5_000L);
        assertTrue(admission.tryAcquire());
        final var order = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        final var firstParked = new CountDownLatch(1);
        final var done = new CountDownLatch(2);
        final var early = Thread.ofVirtual().start(() -> {
            firstParked.countDown();
            if (admission.tryAcquire()) {
                order.add("early");
                admission.release();
            }
            done.countDown();
        });
        assertTrue(firstParked.await(30, TimeUnit.SECONDS));
        // Long enough for the early waiter to be parked in the queue rather than still starting up.
        Thread.sleep(200L);
        Thread.ofVirtual().start(() -> {
            if (admission.tryAcquire()) {
                order.add("late");
                admission.release();
            }
            done.countDown();
        });
        Thread.sleep(200L);
        admission.release();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        early.join();
        assertEquals("early", order.poll());
        assertEquals("late", order.poll());
    }

    @Test
    public void test_interrupted_wait_returns_false_and_restores_the_flag() throws Exception {
        final var admission = new ScriptAdmission(1, 30_000L);
        assertTrue(admission.tryAcquire());
        final var refused = new AtomicBoolean(true);
        final var flagRestored = new AtomicBoolean();
        final var waiting = new CountDownLatch(1);
        final var done = new CountDownLatch(1);
        final var waiter = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            refused.set(!admission.tryAcquire());
            flagRestored.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        assertTrue(waiting.await(30, TimeUnit.SECONDS));
        Thread.sleep(200L);
        waiter.interrupt();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(refused.get());
        assertTrue(flagRestored.get(), "the interrupt flag was swallowed");
        // An interrupted wait never held a permit, so it is not counted as a capacity rejection.
        assertEquals(0, admission.getRejected());
    }

    private static ScriptAdmission tenantAdmission(int nodeWide, int perUser, int perDatabase) {
        final var admission = new ScriptAdmission(nodeWide, 0L);
        admission.reconfigure(nodeWide, 0L, perUser, perDatabase);
        return admission;
    }

    @Test
    public void test_per_user_cap_refuses_the_same_user_but_not_another() {
        final var admission = tenantAdmission(10, 1, 0);
        assertNotNull(admission.acquire("alice", "db1"));
        assertNull(admission.acquire("alice", "db1"), "alice is already at her ceiling");
        assertEquals(ScriptAdmission.SCOPE_USER, admission.lastRefusalScope());
        assertNotNull(admission.acquire("bob", "db1"), "bob has his own slice");
        assertEquals(1L, admission.getRejectedPerUser());
    }

    @Test
    public void test_per_database_cap_refuses_the_same_database_but_not_another() {
        final var admission = tenantAdmission(10, 0, 1);
        assertNotNull(admission.acquire("alice", "db1"));
        assertNull(admission.acquire("bob", "db1"), "db1 is already at its ceiling");
        assertEquals(ScriptAdmission.SCOPE_DATABASE, admission.lastRefusalScope());
        assertNotNull(admission.acquire("bob", "db2"));
        assertEquals(1L, admission.getRejectedPerDatabase());
    }

    // The failure mode the Permit exists to prevent: a refusal at an inner level must give the node-wide
    // permit back, or a saturated tenant would drain the node's capacity by being refused.
    @Test
    public void test_an_inner_refusal_returns_the_node_wide_permit() {
        final var admission = tenantAdmission(2, 1, 0);
        final var held = admission.acquire("alice", "db1");
        assertNotNull(held);
        assertEquals(1, admission.available());

        assertNull(admission.acquire("alice", "db1"));
        assertEquals(1, admission.available(), "the refused run must not still hold a node-wide permit");

        held.close();
        assertEquals(2, admission.available());
    }

    @Test
    public void test_closing_a_permit_frees_every_slice_it_took() {
        final var admission = tenantAdmission(1, 1, 1);
        final var permit = admission.acquire("alice", "db1");
        assertNotNull(permit);
        assertNull(admission.acquire("alice", "db1"));

        permit.close();
        assertNotNull(admission.acquire("alice", "db1"), "every slice must be released together");
    }

    @Test
    public void test_closing_twice_is_a_no_op() {
        final var admission = tenantAdmission(1, 1, 1);
        final var permit = admission.acquire("alice", "db1");
        assertNotNull(permit);
        permit.close();
        permit.close();
        assertEquals(1, admission.available(), "a double close must not inflate the pool");
    }

    @Test
    public void test_zero_means_unlimited_at_both_tenant_levels() {
        final var admission = tenantAdmission(0, 0, 0);
        for (var i = 0; i < 20; i++) {
            assertNotNull(admission.acquire("alice", "db1"));
        }
        assertEquals(0L, admission.getRejectedPerUser());
        assertEquals(0L, admission.getRejectedPerDatabase());
    }

    @Test
    public void test_a_null_username_or_database_skips_that_slice() {
        final var admission = tenantAdmission(5, 1, 1);
        final var first = admission.acquire(null, null);
        assertNotNull(first);
        assertNotNull(admission.acquire(null, null), "a run with no principal cannot be sliced by one");
        first.close();
        assertNotNull(admission.acquire(null, null));
    }

    // Idle pools are dropped so a node that sees many one-off users does not accumulate an entry per name.
    @Test
    public void test_idle_pools_are_released() {
        final var admission = tenantAdmission(5, 1, 1);
        final var permit = admission.acquire("transient-user", "transient-db");
        assertNotNull(permit);
        permit.close();
        assertNotNull(admission.acquire("transient-user", "transient-db"));
    }

    @Test
    public void test_node_wide_refusal_reports_the_node_scope() {
        final var admission = tenantAdmission(1, 0, 0);
        assertNotNull(admission.acquire("alice", "db1"));
        assertNull(admission.acquire("bob", "db2"));
        assertEquals(ScriptAdmission.SCOPE_NODE, admission.lastRefusalScope());
    }

    // Read before anything was ever refused on this thread, the scope answers for the outermost cap
    // rather than for nothing at all.
    @Test
    public void test_the_refusal_scope_defaults_to_the_node() {
        assertEquals(ScriptAdmission.SCOPE_NODE, new ScriptAdmission(1, 0L).lastRefusalScope());
    }
}
