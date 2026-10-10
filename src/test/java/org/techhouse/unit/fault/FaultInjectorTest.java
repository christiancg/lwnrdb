package org.techhouse.unit.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class FaultInjectorTest {
    private static final String SUBJECT = "db|coll";
    private static final long WAIT_MS = 5000;

    private final FaultInjector injector = IocContainer.get(FaultInjector.class);
    private final List<Integer> halts = new CopyOnWriteArrayList<>();
    private IntConsumer origHalt;
    private Long origPauseLimitMs;

    @BeforeEach
    public void setUp() throws Exception {
        origHalt = TestUtils.getPrivateField(injector, "halt", IntConsumer.class);
        origPauseLimitMs = TestUtils.getPrivateField(injector, "pauseLimitMs", Long.class);
        TestUtils.setPrivateField(injector, "halt", (IntConsumer) halts::add);
        TestUtils.setPrivateField(injector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        TestUtils.setPrivateField(injector, "halt", origHalt);
        TestUtils.setPrivateField(injector, "pauseLimitMs", origPauseLimitMs);
    }

    private static Thread hitInBackground(Runnable hit) {
        final var thread = new Thread(hit);
        thread.start();
        return thread;
    }

    @Test
    public void test_a_disabled_injector_never_fires_an_armed_point() throws Exception {
        TestUtils.setPrivateField(injector, "enabled", false);
        injector.arm(FaultPoint.DELETE_TOMBSTONE_RESERVED, FaultAction.HALT, null, 0);
        injector.arm(FaultPoint.PEER_REPLY, FaultAction.DROP, null, 0);
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, SUBJECT);
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
        assertTrue(halts.isEmpty());
        assertFalse(injector.isEnabled());
    }

    @Test
    public void test_an_unarmed_point_passes_through() {
        injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT);
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
        assertTrue(halts.isEmpty());
    }

    @Test
    public void test_a_match_fires_only_for_its_subject() {
        injector.arm(FaultPoint.DELETE_TOMBSTONE_RESERVED, FaultAction.HALT, SUBJECT, 0);
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, "db|other");
        assertTrue(halts.isEmpty());
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, SUBJECT);
        assertEquals(List.of(137), halts);
    }

    @Test
    public void test_no_match_fires_for_any_subject() {
        injector.arm(FaultPoint.DELETE_TOMBSTONE_RESERVED, FaultAction.HALT, null, 0);
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, "anything|else");
        assertEquals(List.of(137), halts);
    }

    @Test
    public void test_an_arm_fires_once() {
        injector.arm(FaultPoint.DELETE_TOMBSTONE_RESERVED, FaultAction.HALT, null, 0);
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, SUBJECT);
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, SUBJECT);
        assertEquals(1, halts.size());
    }

    @Test
    public void test_pause_parks_until_released_and_reports_reached() throws Exception {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, SUBJECT, 0);
        final var thread = hitInBackground(() -> injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT));
        assertTrue(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, WAIT_MS));
        thread.join(200);
        assertTrue(thread.isAlive(), "a paused thread must stay parked until released");
        injector.release(FaultPoint.WRITE_OWNERSHIP_RECHECKED);
        thread.join(WAIT_MS);
        assertFalse(thread.isAlive());
    }

    @Test
    public void test_pause_gives_up_after_its_limit() throws Exception {
        TestUtils.setPrivateField(injector, "pauseLimitMs", 50L);
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        final var started = System.nanoTime();
        injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMs >= 50 && elapsedMs < WAIT_MS, "elapsed=" + elapsedMs);
    }

    @Test
    public void test_delay_sleeps_at_least_its_delay() {
        injector.arm(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, FaultAction.DELAY, null, 50);
        final var started = System.nanoTime();
        injector.at(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, SUBJECT);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMs >= 50 && elapsedMs < WAIT_MS, "elapsed=" + elapsedMs);
    }

    @Test
    public void test_a_delay_ends_early_when_released() throws Exception {
        injector.arm(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, FaultAction.DELAY, null, 60_000);
        final var thread = hitInBackground(() -> injector.at(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, SUBJECT));
        assertTrue(injector.awaitReached(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, WAIT_MS));
        injector.release(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED);
        thread.join(WAIT_MS);
        assertFalse(thread.isAlive());
    }

    @Test
    public void test_halt_calls_the_halt_function_with_137() {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.HALT, null, 0);
        injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT);
        assertEquals(List.of(137), halts);
    }

    @Test
    public void test_drop_answers_true_once() {
        injector.arm(FaultPoint.PEER_REPLY, FaultAction.DROP, "COMMIT_TX", 0);
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "PREPARE_TX"));
        assertTrue(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
    }

    @Test
    public void test_at_ignores_a_drop() {
        injector.arm(FaultPoint.PEER_REPLY, FaultAction.DROP, null, 0);
        injector.at(FaultPoint.PEER_REPLY, "COMMIT_TX");
        assertTrue(injector.awaitReached(FaultPoint.PEER_REPLY, 0));
        assertTrue(halts.isEmpty());
    }

    @Test
    public void test_a_message_point_can_also_halt() {
        injector.arm(FaultPoint.PEER_REPLY, FaultAction.HALT, null, 0);
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
        assertEquals(List.of(137), halts);
    }

    @Test
    public void test_rearming_releases_the_previous_pause() throws Exception {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        final var thread = hitInBackground(() -> injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT));
        assertTrue(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, WAIT_MS));
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        thread.join(WAIT_MS);
        assertFalse(thread.isAlive());
        assertFalse(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, 0), "the new arm has not fired");
    }

    @Test
    public void test_release_all_frees_every_paused_thread() throws Exception {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        injector.arm(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, FaultAction.PAUSE, null, 0);
        final var first = hitInBackground(() -> injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT));
        final var second = hitInBackground(() -> injector.at(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, SUBJECT));
        assertTrue(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, WAIT_MS));
        assertTrue(injector.awaitReached(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, WAIT_MS));
        injector.releaseAll();
        first.join(WAIT_MS);
        second.join(WAIT_MS);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
    }

    @Test
    public void test_await_times_out_when_never_reached() {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        assertFalse(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, 50));
    }

    @Test
    public void test_await_on_an_unarmed_point_times_out() {
        assertFalse(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, 50));
    }

    @Test
    public void test_concurrent_hits_fire_the_arm_exactly_once() throws Exception {
        injector.arm(FaultPoint.PEER_REPLY, FaultAction.DROP, null, 0);
        final var start = new CountDownLatch(1);
        final var drops = new AtomicInteger();
        final var threads = new Thread[8];
        for (var i = 0; i < threads.length; i++) {
            threads[i] = hitInBackground(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX")) {
                    drops.incrementAndGet();
                }
            });
        }
        start.countDown();
        for (final var thread : threads) {
            thread.join(WAIT_MS);
        }
        assertEquals(1, drops.get());
    }

    @Test
    public void test_an_interrupted_pause_returns_with_its_interrupt_flag_set() throws Exception {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        final var interrupted = new AtomicBoolean();
        final var thread = hitInBackground(() -> {
            injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, SUBJECT);
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        assertTrue(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, WAIT_MS));
        thread.interrupt();
        thread.join(WAIT_MS);
        assertFalse(thread.isAlive());
        assertTrue(interrupted.get());
    }

    @Test
    public void test_an_interrupted_await_answers_not_reached() throws Exception {
        injector.arm(FaultPoint.WRITE_OWNERSHIP_RECHECKED, FaultAction.PAUSE, null, 0);
        final var reached = new AtomicBoolean(true);
        final var thread = hitInBackground(
                () -> reached.set(injector.awaitReached(FaultPoint.WRITE_OWNERSHIP_RECHECKED, WAIT_MS)));
        thread.interrupt();
        thread.join(WAIT_MS);
        assertFalse(reached.get());
    }

    @Test
    public void test_the_collection_subject_joins_database_and_collection() {
        assertEquals(SUBJECT, FaultInjector.collectionSubject("db", "coll"));
    }

    @Test
    public void test_names_parse_case_insensitively_and_unknown_names_parse_to_null() {
        assertEquals(FaultPoint.PEER_REPLY, FaultPoint.parse("peer_reply"));
        assertEquals(FaultAction.DROP, FaultAction.parse("Drop"));
        assertNull(FaultPoint.parse("NOPE"));
        assertNull(FaultAction.parse(null));
        assertTrue(FaultPoint.PEER_REPLY.isDroppable());
        assertFalse(FaultPoint.WRITE_OWNERSHIP_RECHECKED.isDroppable());
    }
}
