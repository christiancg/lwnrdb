package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CollectionLockSkipTest {
    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private ClusterConfig realConfig;
    private Thread holder;
    private CountDownLatch release;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        realConfig = TestUtils.getPrivateField(service, "clusterConfig", ClusterConfig.class);
        final var config = mock(ClusterConfig.class);
        when(config.replicationAckTimeoutMs()).thenReturn(150L);
        TestUtils.setPrivateField(service, "clusterConfig", config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (release != null) {
            release.countDown();
            holder.join(5000);
        }
        TestUtils.setPrivateField(service, "clusterConfig", realConfig);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdTheWriteLockElsewhere() throws Exception {
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

    @Test
    public void test_build_digest_gives_up_instead_of_parking_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        assertThrows(CollectionBusyException.class, () -> service.buildDigest(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_build_pull_gives_up_instead_of_parking_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        assertThrows(CollectionBusyException.class,
                () -> service.buildPull(TestGlobals.DB, TestGlobals.COLL, java.util.List.of("a")));
    }

    @Test
    public void test_reconcile_gives_up_instead_of_parking_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        assertThrows(CollectionBusyException.class, () -> service.reconcile(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_free_collection_is_still_read_normally() throws Exception {
        assertTrue(service.buildDigest(TestGlobals.DB, TestGlobals.COLL).getDigest().isEmpty());
    }
}
