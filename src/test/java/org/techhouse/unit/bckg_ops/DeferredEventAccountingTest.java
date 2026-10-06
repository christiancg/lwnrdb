package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

public class DeferredEventAccountingTest {
    private static final long BUSY_WINDOW_MS = 400L;
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private volatile long originalTimeout;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL)
                .setIndexes(Set.of("tag"));
        originalTimeout = configuration.getTransactionLockTimeoutMs();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 20L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static DbEntry entry() {
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("deferred-doc");
        return entry;
    }

    @Test
    public void test_a_deferred_event_stays_counted_until_it_is_processed() throws Exception {
        final var queue = new LinkedBlockingQueue<Event>();
        final var inFlight = new AtomicInteger(1);
        queue.add(new EntityEvent(EventType.UPDATED, TestGlobals.DB, TestGlobals.COLL, entry()));
        final var idleSignal = new IdleSignal();
        final var worker = new Thread(
                new BackgroundProcessorThread(queue, inFlight, new AtomicInteger(), new AtomicInteger(1), idleSignal));

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
