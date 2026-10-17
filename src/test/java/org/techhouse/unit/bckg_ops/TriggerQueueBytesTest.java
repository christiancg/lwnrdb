package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerQueueBytesTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueSize", 100);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
    }

    @AfterEach
    void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueSize", ConfigKey.TRIGGER_QUEUE_SIZE.defaultValue());
        TestUtils.setPrivateField(configuration, "triggerQueueMaxBytes",
                ConfigKey.TRIGGER_QUEUE_MAX_BYTES.defaultValue());
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled",
                ConfigKey.TRIGGER_RUN_LOG_ENABLED.defaultValue());
    }

    private static DbEntry entry(String collName, String id, int padding) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        data.add("payload", new JsonString("x".repeat(padding)));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(collName);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static TriggerEvent event(EventType type, String collName, String id, int padding) {
        return new TriggerEvent(type, TestGlobals.DB, collName, "audit", "recalc", false,
                List.of(entry(collName, id, padding)), "alice", 0, null, 1);
    }

    private static TriggerExecutor executorThatNeverDrains() throws Exception {
        final var executor = new TriggerExecutor();
        TestUtils.setPrivateField(executor, "dispatcher", (Consumer<TriggerEvent>) _ -> {
        });
        return executor;
    }

    @SuppressWarnings("unchecked")
    private static LinkedBlockingQueue<TriggerEvent> queueOf(TriggerExecutor executor) throws Exception {
        return TestUtils.getPrivateField(executor, "queue", LinkedBlockingQueue.class);
    }

    @Test
    public void test_a_queued_created_event_holds_only_its_ids() throws Exception {
        final var executor = executorThatNeverDrains();

        executor.submit(event(EventType.CREATED, TestGlobals.COLL, "big", 100_000));

        final var queued = queueOf(executor).peek();
        assertNotNull(queued);
        assertNull(queued.getEntries().getFirst().getData(), "the document is re-read when the trigger runs");
        assertEquals("big", queued.getEntries().getFirst().get_id());
        assertTrue(queued.hasStaleEntries());
        assertTrue(executor.getQueuedBytes() < 1_024L, "queued " + executor.getQueuedBytes() + " bytes for one id");
    }

    @Test
    public void test_the_trigger_queue_is_bounded_by_bytes() throws Exception {
        final var budget = event(EventType.DELETED, TestGlobals.COLL, "sizing", 2_000).queuedBytes() * 5 / 2;
        TestUtils.setPrivateField(configuration, "triggerQueueMaxBytes", budget + "b");
        final var executor = executorThatNeverDrains();

        executor.submit(event(EventType.DELETED, TestGlobals.COLL, "first", 2_000));
        executor.submit(event(EventType.DELETED, TestGlobals.COLL, "second", 2_000));
        executor.submit(event(EventType.DELETED, TestGlobals.COLL, "third", 2_000));

        assertEquals(1, executor.getDropped(), "the queue holds deleted documents only up to its byte budget");
        assertEquals(List.of("second", "third"),
                queueOf(executor).stream().map(queued -> queued.getEntries().getFirst().get_id()).toList());
        assertTrue(executor.getQueuedBytes() <= budget);
    }

    @Test
    public void test_a_lone_event_larger_than_the_budget_still_queues() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueMaxBytes", "1Kb");
        final var executor = executorThatNeverDrains();

        executor.submit(event(EventType.DELETED, TestGlobals.COLL, "huge", 4_000));

        assertEquals(1, executor.getQueued(), "an empty queue never refuses, or such a run could never fire");
        assertEquals(0, executor.getDropped());
    }

    @Test
    public void test_discarding_a_dropped_collection_removes_only_its_events() throws Exception {
        final var executor = executorThatNeverDrains();
        executor.submit(event(EventType.DELETED, TestGlobals.COLL, "gone", 10));
        executor.submit(event(EventType.DELETED, "otherColl", "kept", 10));
        final var before = executor.getQueuedBytes();

        assertEquals(1, executor.discardQueuedFor(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(1, executor.getQueued());
        final var kept = queueOf(executor).peek();
        assertNotNull(kept);
        assertEquals("kept", kept.getEntries().getFirst().get_id());
        assertTrue(executor.getQueuedBytes() < before, "a discarded event gives its bytes back");
        assertEquals(1, executor.discardQueuedFor(TestGlobals.DB, null), "a dropped database takes every collection");
        assertEquals(0L, executor.getQueuedBytes());
    }

    private static TriggerExecutor executorWithRetries() throws Exception {
        final var executor = executorThatNeverDrains();
        TestUtils.setPrivateField(executor, "retryScheduler", Executors.newSingleThreadScheduledExecutor());
        return executor;
    }

    @Test
    public void retryScheduledEventsAreCounted() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueMaxBytes", "3Kb");
        final var executor = executorWithRetries();
        try {
            executor.submit(event(EventType.DELETED, TestGlobals.COLL, "queued", 500));
            executor.submitAfter(event(EventType.DELETED, TestGlobals.COLL, "waiting", 2_000), 60_000L);
            assertTrue(executor.getQueuedBytes() >= 2_500L, "a waiting retry still holds its document");

            executor.submit(event(EventType.DELETED, "otherColl", "newcomer", 1_000));

            assertEquals(1, executor.getDropped(), "the waiting retry counts against triggerQueueMaxBytes");
        } finally {
            executor.stop();
        }
        assertEquals(0L, executor.getQueuedBytes(), "cancelled retries give their bytes back");
    }

    @Test
    public void aLoneQueuedEventStillQueuesWhileRetriesWait() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerQueueMaxBytes", "3Kb");
        final var executor = executorWithRetries();
        try {
            executor.submitAfter(event(EventType.DELETED, TestGlobals.COLL, "waiting", 2_000), 60_000L);

            executor.submit(event(EventType.DELETED, "otherColl", "huge", 4_000));

            assertEquals(1, executor.getQueued(), "an empty queue never refuses, whatever the retries hold");
            assertEquals(0, executor.getDropped());
        } finally {
            executor.stop();
        }
    }

    @Test
    public void aFiredRetryMovesItsBytesIntoTheQueue() throws Exception {
        final var executor = executorWithRetries();
        try {
            executor.submitAfter(event(EventType.DELETED, TestGlobals.COLL, "soon", 2_000), 1L);
            final var deadline = System.currentTimeMillis() + 10_000L;
            while (executor.getQueued() == 0 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, executor.getQueued());
            final var queued = queueOf(executor).peek();
            assertNotNull(queued);
            assertEquals(queued.queuedBytes(), executor.getQueuedBytes(), "the bytes are counted once");
        } finally {
            executor.stop();
        }
    }

    @Test
    public void test_the_run_sweep_repeats_until_the_executor_stops() throws Exception {
        final var executor = executorThatNeverDrains();
        final var sweeps = new AtomicInteger();

        executor.sweepPeriodically(sweeps::incrementAndGet, 10L);
        final var deadline = System.currentTimeMillis() + 10_000L;
        while (sweeps.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        executor.stop();
        final var afterStop = sweeps.get();
        Thread.sleep(50L);

        assertTrue(afterStop >= 2, "the sweep ran " + afterStop + " time(s)");
        assertEquals(afterStop, sweeps.get(), "stopping the executor stops its sweep");
    }

    @Test
    public void test_a_deleted_event_is_charged_its_heap_estimate() {
        final var deleted = event(EventType.DELETED, TestGlobals.COLL, "heap", 2_000);
        final var entry = deleted.getEntries().getFirst();

        assertEquals(256L + CacheSizeEstimator.estimateEntry(entry), deleted.queuedBytes());
        assertTrue(deleted.queuedBytes() > entry.byteSize(), "heap, not on-disk bytes");
    }

    @Test
    public void test_the_charge_is_released_exactly_on_dispatch() throws Exception {
        final var dispatched = new CountDownLatch(1);
        final var executor = new TriggerExecutor();
        executor.start(_ -> dispatched.countDown());
        try {
            executor.submit(event(EventType.DELETED, TestGlobals.COLL, "released", 2_000));
            assertTrue(dispatched.await(10, TimeUnit.SECONDS));
            final var deadline = System.currentTimeMillis() + 10_000L;
            while (executor.getQueuedBytes() != 0L && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0L, executor.getQueuedBytes());
        } finally {
            executor.stop();
        }
    }

    @Test
    public void test_stop_sweep_ends_the_sweep_but_keeps_the_executor_running() throws Exception {
        final var executor = executorThatNeverDrains();
        final var sweeps = new AtomicInteger();
        executor.sweepPeriodically(sweeps::incrementAndGet, 10L);
        final var deadline = System.currentTimeMillis() + 10_000L;
        while (sweeps.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        executor.stopSweep();
        final var afterStop = sweeps.get();
        Thread.sleep(50L);
        executor.submit(event(EventType.CREATED, TestGlobals.COLL, "still-queued", 10));

        assertTrue(afterStop >= 1);
        assertEquals(afterStop, sweeps.get(), "stopSweep ends the sweep");
        assertEquals(1, executor.getQueued(), "the executor still accepts work");
    }
}
