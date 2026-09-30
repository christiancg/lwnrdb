package org.techhouse.cluster;

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
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class ConformDatabaseBarrierTest {
    private static final String DB = "barrierdb";
    private static final String COLL = "barriercoll";
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private Thread holder;
    private CountDownLatch release;

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

    private void holdTheDatabaseExclusivelyElsewhere() throws Exception {
        final var taken = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = new Thread(() -> {
            try {
                assertTrue(locks.tryLockDatabaseExclusive(DB, 1000));
                taken.countDown();
                release.await();
                locks.releaseDatabaseExclusive(DB);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "drop-database-holder");
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
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

        conformer.conform(snapshot());

        assertNull(cache.getAdminCollectionEntry(DB, COLL),
                "a collection registered under a running drop would be deleted with no lock held on it");

        releaseHolder();
        conformer.conform(snapshot());

        assertNotNull(cache.getAdminCollectionEntry(DB, COLL), "the next round must register it");
    }
}
