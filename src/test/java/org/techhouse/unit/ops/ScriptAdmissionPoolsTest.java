package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.test.Await;

public class ScriptAdmissionPoolsTest {
    private static final long DEADLINE_MS = 10_000L;

    private static ScriptAdmission admission(int perUser, int perDatabase) {
        final var admission = new ScriptAdmission(0, 0L);
        admission.reconfigure(0, 0L, perUser, perDatabase);
        return admission;
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        assertTrue(Await.parked(thread, Thread.State.TIMED_WAITING, DEADLINE_MS),
                "the waiter never parked on the pool");
    }

    @Test
    public void test_a_released_permit_leaves_no_idle_pool_behind() {
        final var admission = admission(2, 3);
        final var first = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("gone", "dropped"));
        final var second = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquire("gone", "dropped", true));
        assertEquals(2, admission.tenantPools());

        first.close();
        assertEquals(2, admission.tenantPools(), "a pool still holding a permit is kept");
        second.close();

        assertEquals(0, admission.tenantPools(), "a deleted user or dropped database must leave nothing");
    }

    @Test
    public void test_a_refused_attempt_leaves_no_pool_for_a_tenant_it_never_held() {
        final var admission = admission(1, 1);
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("alice", "shared"));

        assertInstanceOf(ScriptAdmission.Refusal.class, admission.acquireSlices("bob", "shared"));

        assertEquals(2, admission.tenantPools(), "bob's user slice was given back and its pool pruned");
        held.close();
        assertEquals(0, admission.tenantPools());
    }

    @Test
    public void test_a_waiting_trigger_gets_the_slice_when_a_permit_closes() throws Exception {
        final var admission = admission(1, 0);
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("definer", "db"));
        final var admitted = new CountDownLatch(1);
        final var waiter = Thread.ofPlatform().start(() -> {
            if (admission.acquireSlicesWithin("definer", "db", DEADLINE_MS) instanceof ScriptAdmission.Permit permit) {
                admitted.countDown();
                permit.close();
            }
        });
        awaitWaiting(waiter);

        held.close();

        assertTrue(admitted.await(DEADLINE_MS, TimeUnit.MILLISECONDS), "the waiter never got the released slice");
        waiter.join(DEADLINE_MS);
        assertEquals(0, admission.tenantPools());
        assertEquals(0L, admission.getRejectedPerUser());
    }

    @Test
    public void test_a_pool_with_a_waiter_is_never_pruned() throws Exception {
        final var admission = admission(1, 0);
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("definer", "db"));
        final var waiting = new AtomicReference<ScriptAdmission.Admission>();
        final var waiter = Thread.ofPlatform()
                .start(() -> waiting.set(admission.acquireSlicesWithin("definer", "db", DEADLINE_MS)));
        awaitWaiting(waiter);

        held.close();
        waiter.join(DEADLINE_MS);

        final var permit = assertInstanceOf(ScriptAdmission.Permit.class, waiting.get());
        assertEquals(1, admission.tenantPools(), "the pool the waiter now holds must still be the mapped one");
        assertInstanceOf(ScriptAdmission.Refusal.class, admission.acquireSlices("definer", "db"),
                "a pruned pool would hand out a second slice past the limit of one");
        permit.close();
        assertEquals(0, admission.tenantPools());
    }

    @Test
    public void test_a_timed_out_wait_is_refused_and_prunes_what_it_entered() {
        final var admission = admission(1, 0);
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("definer", "db"));

        final var start = System.nanoTime();
        final var refused = admission.acquireSlicesWithin("definer", "db", 60L);
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(new ScriptAdmission.Refusal(ScriptAdmission.SCOPE_USER), refused);
        assertTrue(elapsedMs >= 50L, "returned after " + elapsedMs + "ms without waiting");
        assertEquals(1L, admission.getRejectedPerUser());
        held.close();
        assertEquals(0, admission.tenantPools());
    }

    @Test
    public void test_a_database_wait_that_times_out_gives_the_user_slice_back() {
        final var admission = admission(2, 1);
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("bob", "busy"));

        assertEquals(new ScriptAdmission.Refusal(ScriptAdmission.SCOPE_DATABASE),
                admission.acquireSlicesWithin("alice", "busy", 30L));

        assertEquals(2, admission.tenantPools(), "alice's user pool must be released and pruned");
        assertEquals(1L, admission.getRejectedPerDatabase());
        held.close();
        assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlicesWithin("alice", "busy", 0L)).close();
        assertEquals(0, admission.tenantPools());
    }

    @Test
    public void test_a_permit_held_across_a_reconfigure_never_inflates_the_new_pool() {
        final var admission = admission(1, 0);
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("alice", "db"));
        admission.reconfigure(0, 0L, 1, 0);
        final var fresh = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("alice", "db"));

        held.close();

        assertInstanceOf(ScriptAdmission.Refusal.class, admission.acquireSlices("alice", "db"),
                "closing the old permit must not add a slice to the pool created after the reconfigure");
        fresh.close();
    }

    @Test
    public void test_concurrent_acquire_wait_and_release_never_exceed_the_limit_and_leave_no_pool() throws Exception {
        final var admission = admission(2, 3);
        final var inside = new AtomicInteger();
        final var peak = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final var tasks = new ArrayList<Future<?>>();
            for (var thread = 0; thread < 8; thread++) {
                final var waits = thread % 2 == 0;
                tasks.add(pool.submit(() -> {
                    for (var round = 0; round < 2_000; round++) {
                        final var admitted = waits
                                ? admission.acquireSlicesWithin("racer", "racedb", 5L)
                                : admission.acquireSlices("racer", "racedb");
                        if (admitted instanceof ScriptAdmission.Permit permit) {
                            peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            inside.decrementAndGet();
                            permit.close();
                        }
                    }
                }));
            }
            for (final var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(peak.get() <= 2, "the per-user limit of 2 was exceeded: " + peak.get());
        assertEquals(0, admission.tenantPools(), "every pool must be pruned once the last permit closes");
        final var first = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("racer", "racedb"));
        final var second = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("racer", "racedb"));
        assertInstanceOf(ScriptAdmission.Refusal.class, admission.acquireSlices("racer", "racedb"),
                "no release may have landed in a pool other than the one it was taken from");
        first.close();
        second.close();
    }
}
