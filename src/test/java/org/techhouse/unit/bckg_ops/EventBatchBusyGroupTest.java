package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EventBatchBusyGroupTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ExecutorService lockHolder = Executors.newSingleThreadExecutor();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        indexField(TestGlobals.COLL);
        indexField(TestGlobals.JOIN_COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        release.countDown();
        lockHolder.shutdown();
        assertTrue(lockHolder.awaitTermination(10, TimeUnit.SECONDS));
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs",
                ConfigKey.TRANSACTION_LOCK_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void indexField(String collName) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, collName, "f");
        cache.getAdminCollectionEntry(TestGlobals.DB, collName).setIndexes(Set.of("f"));
    }

    private List<Event> eventsFor(String collName, String... ids) {
        final var events = new ArrayList<Event>();
        var value = 1;
        for (final var id : ids) {
            final var data = new JsonObject();
            data.add(Globals.PK_FIELD, new JsonString(id));
            data.addProperty("f", value++);
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, collName, data);
            entry.set_id(id);
            cache.addEntryToCache(TestGlobals.DB, collName, entry);
            events.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, collName, entry));
        }
        return events;
    }

    private void holdCollectionWriteLock() throws Exception {
        final var held = new CountDownLatch(1);
        lockHolder.execute(() -> {
            try {
                if (locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL, 5_000L)) {
                    held.countDown();
                    release.await();
                    locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS), "the other thread must take the write lock");
    }

    private Set<String> indexedIds(String collName) throws Exception {
        locks.lockRead(TestGlobals.DB, collName);
        try {
            final var index = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, collName, "f", Number.class);
            return index == null
                    ? Set.of()
                    : index.stream().flatMap(entry -> entry.getIds().stream()).collect(Collectors.toSet());
        } finally {
            locks.releaseRead(TestGlobals.DB, collName);
        }
    }

    @Test
    public void busyCollectionGroupIsDeferredWithoutBlockingOthers() throws Exception {
        final var busy = eventsFor(TestGlobals.COLL, "x1", "x2");
        final var free = eventsFor(TestGlobals.JOIN_COLL, "y1", "y2");
        final var batch = new ArrayList<>(busy);
        batch.addAll(free);
        holdCollectionWriteLock();

        final var started = System.nanoTime();
        final var deferred = EventProcessorHelper.processBatch(batch);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(busy, deferred, "only the locked collection's events are handed back");
        assertTrue(elapsedMs < 2_000L, "the batch waited " + elapsedMs + " ms behind a locked collection");
        assertEquals(Set.of("y1", "y2"), indexedIds(TestGlobals.JOIN_COLL));

        release.countDown();
        assertTrue(lockHolderReleased(), "the other thread must let the lock go");
        assertEquals(List.of(), EventProcessorHelper.processBatch(deferred));
        assertEquals(Set.of("x1", "x2"), indexedIds(TestGlobals.COLL));
    }

    private boolean lockHolderReleased() throws Exception {
        final var deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 100L)) {
                locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
                return true;
            }
        }
        return false;
    }

    @Test
    public void aBatchWithOnlyBusyGroupsWaitsTheBudgetBeforeDeferring() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs", 300L);
        final var busy = eventsFor(TestGlobals.COLL, "x1", "x2");
        holdCollectionWriteLock();

        final var started = System.nanoTime();
        final var deferred = EventProcessorHelper.processBatch(busy);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(busy, deferred);
        assertTrue(elapsedMs >= 250L, "with nothing else to do the worker waits its budget, waited " + elapsedMs);
    }

    @Test
    public void anInterruptedBatchHandsBackEveryUnfinishedGroup() throws Exception {
        final var batch = new ArrayList<>(eventsFor(TestGlobals.COLL, "x1", "x2"));
        batch.addAll(eventsFor(TestGlobals.JOIN_COLL, "y1"));

        Thread.currentThread().interrupt();
        final List<Event> deferred;
        try {
            deferred = EventProcessorHelper.processBatch(batch);
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt is restored for the worker loop");
        }

        assertEquals(batch, deferred, "the interrupted group and every group after it are handed back");
    }

    @Test
    public void aLoneBusyEventWaitsTheBudgetBeforeDeferring() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs", 300L);
        final var busy = eventsFor(TestGlobals.COLL, "x1");
        holdCollectionWriteLock();

        final var started = System.nanoTime();
        final var deferred = EventProcessorHelper.processBatch(busy);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(busy, deferred);
        assertTrue(elapsedMs >= 250L, "waited " + elapsedMs);
    }
}
