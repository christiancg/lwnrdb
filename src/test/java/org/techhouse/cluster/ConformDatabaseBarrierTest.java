package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestUtils;

public class ConformDatabaseBarrierTest {
    private static final String DB = "barrierdb";
    private static final String COLL = "barriercoll";
    private static final String OTHER_DB = "otherbarrierdb";
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private Thread holder;
    private CountDownLatch release;

    private boolean conform(AdminSnapshotPayload snapshot) throws Exception {
        return conformer.conform(snapshot, adminEpoch.current());
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var config = mock(ClusterConfig.class);
        when(config.replicationAckTimeoutMs()).thenReturn(150L);
        TestUtils.setPrivateField(conformer, "clusterConfig", config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        releaseHolder();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void releaseHolder() throws InterruptedException {
        if (release != null) {
            release.countDown();
            holder.join(5000);
            release = null;
        }
    }

    private interface BarrierAcquire {
        boolean acquire() throws InterruptedException;
    }

    private void holdTheDatabaseElsewhere(BarrierAcquire acquire, Runnable releaseBarrier) throws Exception {
        final var taken = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = new Thread(() -> {
            try {
                assertTrue(acquire.acquire());
                taken.countDown();
                release.await();
                releaseBarrier.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "database-barrier-holder");
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
    }

    private void holdTheDatabaseExclusivelyElsewhere() throws Exception {
        holdTheDatabaseElsewhere(() -> locks.tryLockDatabaseExclusive(DB, 1000),
                () -> locks.releaseDatabaseExclusive(DB));
    }

    private void holdTheDatabaseSharedElsewhere() throws Exception {
        holdTheDatabaseElsewhere(() -> locks.tryLockDatabaseShared(DB, 1000), () -> locks.releaseDatabaseShared(DB));
    }

    private static void registerTheDatabaseLocally() throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(DB));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(DB, COLL));
        final var fs = IocContainer.get(FileSystem.class);
        fs.createDatabaseFolder(DB);
        fs.createCollectionFile(DB, COLL);
    }

    private static AdminSnapshotPayload snapshotWithoutTheDatabase() {
        final var other = new AdminDbEntry(OTHER_DB, new ArrayList<>(), new ArrayList<>()).getData();
        return new AdminSnapshotPayload(5L, List.of(other), List.of(), List.of(), new JsonObject());
    }

    private void assertStillRegistered(String reason) {
        assertNotNull(cache.getAdminDbEntry(DB), reason);
        assertNotNull(cache.getAdminCollectionEntry(DB, COLL), reason);
    }

    private void assertQuarantined() {
        assertNull(cache.getAdminDbEntry(DB), "the next round must quarantine the database");
        assertNull(cache.getAdminCollectionEntry(DB, COLL), "the next round must quarantine its collections");
    }

    private static AdminSnapshotPayload snapshot() {
        final var db = new AdminDbEntry(DB, new ArrayList<>(), new ArrayList<>()).getData();
        final var coll = new AdminCollEntry(DB, COLL, new HashSet<>()).getData().deepCopy();
        coll.addProperty(Globals.PK_FIELD, Cache.getCollectionIdentifier(DB, COLL));
        return new AdminSnapshotPayload(5L, List.of(db), List.of(coll), List.of(), new JsonObject());
    }

    @Test
    public void test_conform_skips_a_collection_while_its_database_is_being_dropped() throws Exception {
        holdTheDatabaseExclusivelyElsewhere();

        assertFalse(conform(snapshot()), "a round that skipped a collection is not a complete conform");

        assertNull(cache.getAdminCollectionEntry(DB, COLL),
                "a collection registered under a running drop would be deleted with no lock held on it");

        releaseHolder();
        assertTrue(conform(snapshot()));

        assertNotNull(cache.getAdminCollectionEntry(DB, COLL), "the next round must register it");
    }

    @Test
    public void test_quarantine_skips_a_database_while_a_collection_registration_holds_it() throws Exception {
        registerTheDatabaseLocally();
        holdTheDatabaseSharedElsewhere();

        conform(snapshotWithoutTheDatabase());

        assertStillRegistered("a collection registered during the quarantine would be deleted with no lock held on it");

        releaseHolder();
        conform(snapshotWithoutTheDatabase());

        assertQuarantined();
    }

    @Test
    public void test_quarantine_skips_a_database_another_drop_holds() throws Exception {
        registerTheDatabaseLocally();
        holdTheDatabaseExclusivelyElsewhere();

        assertFalse(conform(snapshotWithoutTheDatabase()));

        assertStillRegistered("the quarantine must wait for the drop holding the database barrier");

        releaseHolder();
        assertTrue(conform(snapshotWithoutTheDatabase()));

        assertQuarantined();
    }

    @Test
    public void test_quarantine_releases_the_database_barrier() throws Exception {
        registerTheDatabaseLocally();

        conform(snapshotWithoutTheDatabase());

        assertQuarantined();
        final var acquired = new boolean[1];
        final var probe = new Thread(() -> {
            try {
                acquired[0] = locks.tryLockDatabaseExclusive(DB, 0);
                if (acquired[0]) {
                    locks.releaseDatabaseExclusive(DB);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "database-barrier-probe");
        probe.start();
        probe.join(5000);
        assertTrue(acquired[0], "a stranded barrier would block every later collection registration");
    }
}
