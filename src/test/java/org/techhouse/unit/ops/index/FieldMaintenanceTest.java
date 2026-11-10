package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
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
import org.techhouse.bckg_ops.BackgroundLanes;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.IndexRepairEvent;
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
import org.techhouse.ops.index.IndexRepairPass;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldMaintenanceTest {
    private static final String FIELD = "f";
    private static final long BUDGET_MS = 300L;

    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final BackgroundTaskManager realTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ExecutorService lockHolder = Executors.newSingleThreadExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(IndexRepairPass.class, "taskManager", stubTaskManager);
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs", BUDGET_MS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        release.countDown();
        lockHolder.shutdown();
        assertTrue(lockHolder.awaitTermination(10, TimeUnit.SECONDS));
        TestUtils.setPrivateStaticField(IndexRepairPass.class, "taskManager", realTaskManager);
        TestUtils.setPrivateField(Configuration.getInstance(), "transactionLockTimeoutMs",
                ConfigKey.TRANSACTION_LOCK_TIMEOUT_MS.defaultValue());
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private List<Event> eventsFor(String... ids) {
        final var events = new ArrayList<Event>();
        var value = 1;
        for (final var id : ids) {
            final var data = new JsonObject();
            data.add(Globals.PK_FIELD, new JsonString(id));
            data.addProperty(FIELD, value++);
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
            entry.set_id(id);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
            events.add(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry));
        }
        return events;
    }

    private void holdIndexWriteLock() throws Exception {
        final var held = new CountDownLatch(1);
        lockHolder.execute(() -> {
            try {
                if (locks.tryLockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD, 5_000L)) {
                    held.countDown();
                    release.await();
                    locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS), "the other thread must take the index write lock");
    }

    private void releaseIndexWriteLock() throws Exception {
        release.countDown();
        final var deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (locks.tryLockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD, 100L)) {
                locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                return;
            }
        }
        throw new AssertionError("the other thread never let the index lock go");
    }

    private Set<String> indexedIds() throws Exception {
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var index = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                    Number.class);
            return index == null
                    ? Set.of()
                    : index.stream().flatMap(entry -> entry.getIds().stream()).collect(Collectors.toSet());
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private List<IndexRepairEvent> resubmitted() throws Exception {
        final var lanes = TestUtils.getPrivateField(stubTaskManager, "lanes", BackgroundLanes.class);
        final Collection<?> queue = TestUtils.getPrivateField(lanes, "maintenance", Collection.class);
        return queue.stream().map(IndexRepairEvent.class::cast).toList();
    }

    @Test
    public void aBusyIndexLockTimesOutAndRequeues() throws Exception {
        final var events = eventsFor("x1", "x2");
        holdIndexWriteLock();

        final var started = System.nanoTime();
        final var deferred = EventProcessorHelper.processBatch(events);
        final var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(events, deferred, "a batch whose index lock stayed busy is handed back to be re-queued");
        assertTrue(elapsedMs >= BUDGET_MS - 50L, "the worker waits the budget on the index lock, waited " + elapsedMs);
        assertTrue(elapsedMs < 10_000L, "the worker must not wait on the index lock untimed, waited " + elapsedMs);

        releaseIndexWriteLock();
        assertEquals(List.of(), EventProcessorHelper.processBatch(deferred));
        assertEquals(Set.of("x1", "x2"), indexedIds());
    }

    @Test
    public void theRepairPassResubmitsOnIndexLockBusy() throws Exception {
        eventsFor("r1", "r2");
        final var ids = List.of("r1", "r2");
        final var pass = new IndexRepairEvent(TestGlobals.DB, TestGlobals.COLL, ids, 0L,
                pendingIndexWrites.markRecovered(TestGlobals.DB, TestGlobals.COLL, ids));
        holdIndexWriteLock();

        IndexRepairPass.run(pass);

        final var rest = resubmitted();
        assertEquals(1, rest.size(), "a busy index lock resubmits the rest of the pass");
        assertEquals(FIELD, rest.getFirst().getResumeField());
        assertEquals(0, rest.getFirst().getResumeAt());
        assertEquals(Set.copyOf(ids), Set.copyOf(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL)),
                "the marks stay until the resubmitted pass finishes");

        releaseIndexWriteLock();
        IndexRepairPass.run(rest.getFirst());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        assertEquals(Set.copyOf(ids), indexedIds());
    }
}
