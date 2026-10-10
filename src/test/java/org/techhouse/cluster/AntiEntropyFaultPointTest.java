package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AntiEntropyFaultPointTest extends AntiEntropyTestBase {
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private IntConsumer origHalt;

    @BeforeEach
    public void setUp() throws Exception {
        origHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        TestUtils.setPrivateField(faultInjector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        TestUtils.setPrivateField(faultInjector, "halt", origHalt);
    }

    private boolean collectionIsUnlocked() {
        if (!locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL)) {
            return false;
        }
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        return true;
    }

    @Test
    public void test_the_snapshot_fault_point_fires_after_the_read_lock_is_released() throws Exception {
        injectPeer(mock(PeerConnectionPool.class));
        final var unlockedWhenFired = new CopyOnWriteArrayList<Boolean>();
        TestUtils.setPrivateField(faultInjector, "halt",
                (IntConsumer) _ -> unlockedWhenFired.add(collectionIsUnlocked()));
        faultInjector.arm(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, FaultAction.HALT,
                FaultInjector.collectionSubject(TestGlobals.DB, TestGlobals.COLL), 0);

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(List.of(true), unlockedWhenFired,
                "a write landing in the window must not wait on the sweep's snapshot lock");
    }
}
