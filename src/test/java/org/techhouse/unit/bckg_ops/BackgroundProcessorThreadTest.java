package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundLanes;
import org.techhouse.bckg_ops.BackgroundProcessorThread;
import org.techhouse.bckg_ops.IdleSignal;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BackgroundProcessorThreadTest {
    private static final long BUSY_WINDOW_MS = 400L;
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();

    @Test
    public void testRunWhenQueueHasEvent() throws InterruptedException {
        final var queue = new BackgroundLanes(1);
        Event mockEvent = new Event(EventType.CREATED) {
        };
        queue.add(mockEvent);

        Thread thread = new Thread(new BackgroundProcessorThread(queue, 0, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new IdleSignal()));
        thread.start();

        Thread.sleep(1000);
        thread.interrupt();
    }

    @Test
    public void test_a_failed_batch_still_settles_the_count_the_producer_took() throws InterruptedException {
        final var queue = new BackgroundLanes(1);
        final var inFlight = new AtomicInteger(1);
        queue.add(new Event(EventType.CREATED) {
        });

        final var idleSignal = new IdleSignal();
        final var thread = new Thread(new BackgroundProcessorThread(queue, 0, inFlight, new AtomicInteger(),
                new AtomicInteger(1), idleSignal));
        thread.start();
        try {
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() <= 0, 5000L));
            assertFalse(idleSignal.awaitIdle(() -> inFlight.get() != 0, 100L));
            assertEquals(0, inFlight.get(),
                    "an event counted at submit is settled exactly once, even when its batch throws");
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }

    @Test
    public void testRunWhenQueueIsEmpty() {
        final var queue = new BackgroundLanes(1);

        Thread thread = new Thread(new BackgroundProcessorThread(queue, 0, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new IdleSignal()));
        thread.start();
        assertTrue(thread.isAlive());

        thread.interrupt();
    }

    private static DbEntry deferredEntry() {
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("deferred-doc");
        return entry;
    }

    @Test
    public void test_a_deferred_event_stays_counted_until_it_is_processed() throws Exception {
        final var originalTimeout = configuration.getTransactionLockTimeoutMs();
        try {
            TestUtils.standardInitialSetup();
            TestUtils.createTestDatabaseAndCollection();
            IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL)
                    .setIndexes(Set.of("tag"));
            TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 20L);
            assertDeferredEventStaysCounted();
        } finally {
            TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
            TestUtils.releaseAllLocks();
            TestUtils.standardTearDown();
        }
    }

    private void assertDeferredEventStaysCounted() throws Exception {
        final var queue = new BackgroundLanes(1);
        final var inFlight = new AtomicInteger(1);
        queue.add(new EntityEvent(EventType.UPDATED, TestGlobals.DB, TestGlobals.COLL, deferredEntry()));
        final var idleSignal = new IdleSignal();
        final var worker = new Thread(new BackgroundProcessorThread(queue, 0, inFlight, new AtomicInteger(),
                new AtomicInteger(1), idleSignal));

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            worker.start();
            assertFalse(idleSignal.awaitIdle(() -> inFlight.get() != 1, BUSY_WINDOW_MS),
                    "an event deferred for a busy collection is still pending after every deferral");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        try {
            assertTrue(idleSignal.awaitIdle(() -> inFlight.get() == 0, 5000L),
                    "the deferred event is settled once the collection frees up");
            assertTrue(queue.isEmpty());
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }
}
