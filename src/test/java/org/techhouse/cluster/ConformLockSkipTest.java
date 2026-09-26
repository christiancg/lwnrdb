package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ConformLockSkipTest {
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
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
        }, "conform-lock-holder");
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_a_replicated_apply_with_a_budget_gives_up_on_a_held_lock() throws Exception {
        holdTheWriteLockElsewhere();

        final var payload = new org.techhouse.cluster.msg.ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                org.techhouse.cluster.msg.ReplicationOp.DELETE, null, java.util.List.of("a"), java.util.List.of("1"));
        final var start = System.currentTimeMillis();
        final var applied = ReplicatedApplyHelper.apply(payload, 150L);
        final var elapsed = System.currentTimeMillis() - start;

        assertFalse(applied, "a sweep must skip the collection for this round rather than park on it");
        assertTrue(elapsed < 5000,
                "an unbounded wait stops the whole anti-entropy round and the node goes silent while still ALIVE");
    }

    @Test
    public void test_a_replicated_apply_without_a_budget_still_waits() {
        final var payload = new org.techhouse.cluster.msg.ReplicationPayload(TestGlobals.DB, TestGlobals.COLL,
                org.techhouse.cluster.msg.ReplicationOp.DELETE, null, java.util.List.of("missing"),
                java.util.List.of("1"));

        assertTrue(ReplicatedApplyHelper.apply(payload),
                "a peer handler owns its virtual thread and must still apply what it was sent");
        assertNotNull(cache);
    }
}
