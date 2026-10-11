package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminRecordNamesLockTest extends AdminRecordTestBase {
    private static final String COLLECTION_NAMES = Cache.getCollectionIdentifier(TestGlobals.DB,
            Globals.COLLECTION_NAMES_LOCK);
    private static final String DATABASE_NAMES = Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME,
            Globals.DATABASE_NAMES_LOCK);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private long origTimeout;

    private interface Step {
        void run() throws Exception;
    }

    private final class Holder implements AutoCloseable {
        private final CountDownLatch taken = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private Holder(String lockName, Step beforeRelease) throws InterruptedException {
            thread = Thread.ofVirtual().start(() -> {
                try {
                    locks.lockWrite(lockName);
                    try {
                        taken.countDown();
                        release.await();
                        beforeRelease.run();
                    } finally {
                        locks.releaseWrite(lockName);
                    }
                } catch (Exception | AssertionError e) {
                    failure.set(e);
                }
            });
            assertTrue(taken.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            thread.join();
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        }
    }

    @BeforeEach
    public void lowerTheLockBudget() throws Exception {
        origTimeout = config.getReplicationAckTimeoutMs();
        lockBudget(50L);
    }

    @AfterEach
    public void restoreTheLockBudget() throws Exception {
        lockBudget(origTimeout);
    }

    private void lockBudget(long millis) throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", millis);
    }

    private Holder holding(String lockName) throws InterruptedException {
        return new Holder(lockName, () -> {
        });
    }

    @SuppressWarnings("BusyWait")
    private static void awaitQueuedOn(String lockName) throws Exception {
        final Map<?, ?> all = TestUtils.getPrivateStaticField(ResourceLocking.class, "locks", Map.class);
        final var deadline = System.currentTimeMillis() + 5000;
        while (!(all.get(lockName) instanceof ReentrantReadWriteLock lock) || !lock.hasQueuedThreads()) {
            assertTrue(System.currentTimeMillis() < deadline, "nobody queued on " + lockName);
            Thread.sleep(5);
        }
    }

    private static CompletableFuture<AdminRecordMerge.Result> mergeInBackground(AdminRecord record) {
        return CompletableFuture.supplyAsync(() -> AdminRecordMerge.apply(List.of(record), true));
    }

    @Test
    public void test_an_anti_entropy_collection_drop_waits_for_the_collection_names_lock() throws Exception {
        final var dropped = tombstone(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL), clock.next());
        try (var _ = holding(COLLECTION_NAMES)) {
            assertFalse(AdminRecordMerge.apply(List.of(dropped), false).complete());
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        }

        assertTrue(AdminRecordMerge.apply(List.of(dropped), false).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_collection_install_waits_for_the_collection_names_lock() throws Exception {
        final var record = collection(TestGlobals.DB, "waiting", clock.next());
        try (var _ = holding(COLLECTION_NAMES)) {
            assertFalse(AdminRecordMerge.apply(List.of(record), true).complete());
            assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "waiting"));
        }

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "waiting"));
    }

    @Test
    public void test_a_collection_case_variant_registered_under_the_lock_defers_the_install() throws Exception {
        lockBudget(5000L);
        final var record = collection(TestGlobals.DB, "racecoll", clock.next());
        final CompletableFuture<AdminRecordMerge.Result> merge;
        try (var _ = new Holder(COLLECTION_NAMES,
                () -> assertEquals(OperationStatus.OK, CollectionOperationHelper
                        .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.DB, "RACECOLL"))
                        .getStatus()))) {
            merge = mergeInBackground(record);
            awaitQueuedOn(COLLECTION_NAMES);
        }

        assertFalse(merge.get(5, TimeUnit.SECONDS).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "racecoll"));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "RACECOLL"));

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "racecoll"));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "RACECOLL"));
        assertTrue(tombstones().containsKey(record.key().id()));
    }

    @Test
    public void test_a_database_install_waits_for_the_database_names_lock() throws Exception {
        final var record = database("waitingdb", clock.next());
        try (var _ = holding(DATABASE_NAMES)) {
            assertFalse(AdminRecordMerge.apply(List.of(record), true).complete());
            assertNull(cache.getAdminDbEntry("waitingdb"));
        }

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNotNull(cache.getAdminDbEntry("waitingdb"));
    }

    @Test
    public void test_a_database_case_variant_registered_under_the_lock_defers_the_install() throws Exception {
        lockBudget(5000L);
        final var record = database("racedb", clock.next());
        final CompletableFuture<AdminRecordMerge.Result> merge;
        try (var _ = new Holder(DATABASE_NAMES, () -> assertEquals(OperationStatus.OK, DatabaseOperationHelper
                .processCreateDatabaseOperation(new CreateDatabaseRequest("RACEDB"), null).getStatus()))) {
            merge = mergeInBackground(record);
            awaitQueuedOn(DATABASE_NAMES);
        }

        assertFalse(merge.get(5, TimeUnit.SECONDS).complete());
        assertNull(cache.getAdminDbEntry("racedb"));
        assertNotNull(cache.getAdminDbEntry("RACEDB"));

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNull(cache.getAdminDbEntry("racedb"));
        assertNotNull(cache.getAdminDbEntry("RACEDB"));
        assertTrue(tombstones().containsKey(record.key().id()));
    }

    @Test
    public void test_a_database_install_holds_its_barrier_through_registration() throws Exception {
        final var record = database(TestGlobals.DB, clock.next(), "alice");
        try (var _ = holding(TestGlobals.DB)) {
            assertFalse(AdminRecordMerge.apply(List.of(record), true).complete());
            assertFalse(cache.getAdminDbEntry(TestGlobals.DB).isOwner("alice"));
        }

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertTrue(cache.getAdminDbEntry(TestGlobals.DB).isOwner("alice"));
    }
}
