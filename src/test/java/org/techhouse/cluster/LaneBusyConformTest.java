package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class LaneBusyConformTest {
    private static final long ACK_TIMEOUT_MS = 300L;
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final PeerConnectionPool pool = new PeerConnectionPool();
    private final AdminLane adminLane = IocContainer.get(AdminLane.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final CoalescingSweep sweep = mock(CoalescingSweep.class);
    private CoalescingSweep realSweep;
    private final CountDownLatch releaseLane = new CountDownLatch(1);
    private Thread laneHolder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start(true, ACK_TIMEOUT_MS);
        cluster.configureMembership(1,
                new NodeInfo("self", "127.0.0.1", cluster.serverPort(), NodeState.ALIVE, 1L, 1L));
        realSweep = TestUtils.getPrivateField(adminAntiEntropyService, "sweep", CoalescingSweep.class);
        TestUtils.setPrivateField(adminAntiEntropyService, "sweep", sweep);
    }

    @AfterEach
    public void tearDown() throws Exception {
        releaseLane.countDown();
        if (laneHolder != null) {
            laneHolder.join(5000);
        }
        TestUtils.setPrivateField(adminAntiEntropyService, "sweep", realSweep);
        pool.closeAll();
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdTheLaneElsewhere() throws InterruptedException {
        final var entered = new CountDownLatch(1);
        laneHolder = new Thread(() -> adminLane.within(REQUEST_TIMEOUT_MS, () -> {
            entered.countDown();
            try {
                assertTrue(releaseLane.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, () -> null), "lane-holder");
        laneHolder.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_a_lane_busy_refusal_schedules_an_admin_conform() throws Exception {
        holdTheLaneElsewhere();
        final var message = new ClusterMessage();
        message.setType(ClusterMessageType.REPLICATE_ADMIN);
        message.setSecret(ClusterTestHarness.SECRET);
        message.setAdminEpoch(1L);
        message.setForwardBody(ForwardBody.encode("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"lanebusy\"}"));

        final var reply = pool.request(cluster.serverAddress(), message, REQUEST_TIMEOUT_MS);

        assertEquals(ClusterMessageType.ERROR, reply.getType());
        verify(sweep).schedule();
    }
}
