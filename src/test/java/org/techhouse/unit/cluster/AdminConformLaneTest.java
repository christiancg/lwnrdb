package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.AdminLane;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestUtils;

public class AdminConformLaneTest {
    private static final long SNAPSHOT_EPOCH = 5L;
    private static final long SHORT_MS = 150L;
    private static final String SNAPSHOT_DB = "snapshotdb";
    private static final String LATE_USER = "lateuser";

    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final AdminLane adminLane = IocContainer.get(AdminLane.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration config = Configuration.getInstance();
    private PeerConnectionPool realPool;
    private PeerConnectionPool realDocPool;
    private PeerConnectionPool mockPool;
    private boolean origEnabled;
    private long origLaneTimeout;
    private long origAckTimeout;
    private Thread laneHolder;
    private CountDownLatch releaseLane;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        origEnabled = config.isClusterEnabled();
        origLaneTimeout = config.getAdminLaneTimeoutMs();
        origAckTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "adminLaneTimeoutMs", SHORT_MS);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_MS);
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
        realDocPool = TestUtils.getPrivateField(antiEntropyService, "pool", PeerConnectionPool.class);
        mockPool = mock(PeerConnectionPool.class);
        TestUtils.setPrivateField(service, "pool", mockPool);
        TestUtils.setPrivateField(antiEntropyService, "pool", mockPool);
        TestUtils.setPrivateField(service, "started", true);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        final var self = node("self", 19990);
        final var peer = node("peer", 19991);
        members.put(self.getNodeId(), self);
        members.put(peer.getNodeId(), peer);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (releaseLane != null) {
            releaseLane.countDown();
            laneHolder.join(5000);
        }
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.setPrivateField(antiEntropyService, "pool", realDocPool);
        TestUtils.setPrivateField(service, "started", false);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "adminLaneTimeoutMs", origLaneTimeout);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origAckTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static ClusterMessage snapshotAck() {
        final var ack = new ClusterMessage();
        ack.setType(ClusterMessageType.ADMIN_SNAPSHOT_ACK);
        final var db = new AdminDbEntry(SNAPSHOT_DB, new ArrayList<>(), new ArrayList<>()).getData();
        ack.setAdminSnapshot(
                new AdminSnapshotPayload(SNAPSHOT_EPOCH, List.of(db), List.of(), List.of(), new JsonObject()));
        return ack;
    }

    private void answerSnapshot(Answer<ClusterMessage> answer) throws Exception {
        when(mockPool.request(any(), any(), anyLong())).thenAnswer(answer);
    }

    private void holdTheLaneElsewhere() throws Exception {
        final var entered = new CountDownLatch(1);
        releaseLane = new CountDownLatch(1);
        laneHolder = new Thread(() -> adminLane.within(10_000L, () -> {
            entered.countDown();
            try {
                releaseLane.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, () -> null), "admin-lane-holder");
        laneHolder.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private void holdTheSnapshotProceduresElsewhere() throws Exception {
        final var entered = new CountDownLatch(1);
        releaseLane = new CountDownLatch(1);
        laneHolder = new Thread(() -> {
            try {
                assertTrue(locks.tryLockWrite(SNAPSHOT_DB, Globals.PROCEDURES_FOLDER, 5_000L));
                entered.countDown();
                releaseLane.await();
                locks.releaseWrite(SNAPSHOT_DB, Globals.PROCEDURES_FOLDER);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "procedures-holder");
        laneHolder.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private void createLateUser() throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(LATE_USER, "hash", false, Set.of(), Map.of(), Map.of()));
    }

    private void assertTheRoundWasSkipped() {
        assertNull(cache.getAdminDbEntry(SNAPSHOT_DB), "a skipped round must not conform");
        assertNotEquals(SNAPSHOT_EPOCH, adminEpoch.current(), "a skipped round must not adopt the peer's epoch");
        assertFalse(service.hasCompletedAdminSync(), "a skipped round must not mark the node synced");
    }

    @Test
    public void anUnchangedEpochConformsAndAdopts() throws Exception {
        answerSnapshot(_ -> snapshotAck());

        service.reconcile();

        assertNotNull(cache.getAdminDbEntry(SNAPSHOT_DB));
        assertEquals(SNAPSHOT_EPOCH, adminEpoch.current());
        assertTrue(service.hasCompletedAdminSync());
    }

    @Test
    public void aConformDoesNotRunWhileACoordinatedOpHoldsTheLane() throws Exception {
        answerSnapshot(_ -> snapshotAck());
        holdTheLaneElsewhere();

        service.reconcile();

        assertTheRoundWasSkipped();
    }

    @Test
    public void aRoundIsSkippedWhenTheLocalEpochMovedDuringTheFetch() throws Exception {
        answerSnapshot(_ -> {
            adminEpoch.bump();
            return snapshotAck();
        });

        service.reconcile();

        assertTheRoundWasSkipped();
    }

    @Test
    public void aRoundIsSkippedWhenOnlyTheConfirmedFlagMoved() throws Exception {
        answerSnapshot(_ -> {
            adminEpoch.markUnconfirmed();
            return snapshotAck();
        });

        service.reconcile();

        assertTheRoundWasSkipped();
    }

    @Test
    public void theUserCreatedBeforeTheLaneWasTakenSurvives() throws Exception {
        answerSnapshot(_ -> {
            createLateUser();
            adminEpoch.bump();
            adminEpoch.confirm();
            return snapshotAck();
        });

        service.reconcile();

        assertNotNull(cache.getAdminUserEntry(LATE_USER),
                "a user acknowledged after the peer built its snapshot must not be deleted by conforming to it");
    }

    @Test
    public void anIncompleteConformDoesNotMarkTheNodeSynced() throws Exception {
        answerSnapshot(_ -> snapshotAck());
        holdTheSnapshotProceduresElsewhere();

        service.reconcile();

        assertNotEquals(SNAPSHOT_EPOCH, adminEpoch.current(), "an incomplete round must not adopt the epoch");
        assertFalse(service.hasCompletedAdminSync(),
                "a coordinator still behind its peers must keep refusing admin ops with ADMIN_SYNCING");
    }

    @Test
    public void aLaterCompleteRoundMarksTheNodeSynced() throws Exception {
        answerSnapshot(_ -> snapshotAck());
        holdTheSnapshotProceduresElsewhere();
        service.reconcile();
        releaseLane.countDown();
        laneHolder.join(5000);
        releaseLane = null;

        service.reconcile();

        assertEquals(SNAPSHOT_EPOCH, adminEpoch.current());
        assertTrue(service.hasCompletedAdminSync());
    }
}
