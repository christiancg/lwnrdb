package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AntiEntropyLockTest extends AntiEntropyTestBase {
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private IntConsumer origHalt;
    private ClusterConfig realConfig;
    private Thread holder;
    private CountDownLatch release;

    @BeforeEach
    public void setUp() throws Exception {
        origHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        realConfig = TestUtils.getPrivateField(service, "clusterConfig", ClusterConfig.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (release != null) {
            release.countDown();
            holder.join(5000);
        }
        TestUtils.setPrivateField(service, "clusterConfig", realConfig);
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", origHalt);
    }

    private void budgetLockWaits() throws Exception {
        final var config = mock(ClusterConfig.class);
        when(config.replicationAckTimeoutMs()).thenReturn(150L);
        TestUtils.setPrivateField(service, "clusterConfig", config);
    }

    private void holdTheWriteLockElsewhere() throws Exception {
        budgetLockWaits();
        final var taken = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                taken.countDown();
                release.await();
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "lock-holder");
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
    }

    private boolean collectionIsUnlocked() {
        if (!locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL)) {
            return false;
        }
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        return true;
    }

    @Test
    public void test_build_digest_gives_up_instead_of_parking_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        assertThrows(CollectionBusyException.class, () -> service.buildDigest(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_build_pull_gives_up_instead_of_parking_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        assertThrows(CollectionBusyException.class,
                () -> service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a")));
    }

    @Test
    public void test_reconcile_gives_up_instead_of_parking_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        assertThrows(CollectionBusyException.class, () -> service.reconcile(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_free_collection_is_still_read_normally() throws Exception {
        budgetLockWaits();

        assertTrue(service.buildDigest(TestGlobals.DB, TestGlobals.COLL).getDigest().isEmpty());
    }

    @Test
    public void test_the_snapshot_fault_point_fires_after_the_read_lock_is_released() throws Exception {
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        injectPeer(mock(PeerConnectionPool.class));
        final var unlockedWhenFired = new CopyOnWriteArrayList<Boolean>();
        TestUtils.setPrivateField(faultInjector, "halt",
                (IntConsumer) _ -> unlockedWhenFired.add(collectionIsUnlocked()));
        faultInjector.arm(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, FaultAction.HALT,
                Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(List.of(true), unlockedWhenFired,
                "a write landing in the window must not wait on the sweep's snapshot lock");
    }
}
