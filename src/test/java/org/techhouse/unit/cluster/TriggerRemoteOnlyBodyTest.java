package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.Tx2pcCoordinator;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerRemoteOnlyBodyTest {
    private static final String REMOTE = "127.0.0.1:59998";
    private static final String SECOND_REMOTE = "127.0.0.1:59997";
    private static final String RUN_ID = "run-whose-body-writes-remotely";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcCoordinator coordinator = IocContainer.get(Tx2pcCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private PeerConnectionPool coordinatorPool;
    private PeerConnectionPool routerPool;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        coordinatorPool = TestUtils.getPrivateField(coordinator, "pool", PeerConnectionPool.class);
        routerPool = TestUtils.getPrivateField(router, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        final var self = new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>(Map.of("self", self)));
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
        final var acknowledgingPeer = acknowledgingPeer();
        TestUtils.setPrivateField(coordinator, "pool", acknowledgingPeer);
        TestUtils.setPrivateField(router, "pool", acknowledgingPeer);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(coordinator, "pool", coordinatorPool);
        TestUtils.setPrivateField(router, "pool", routerPool);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", false);
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private PeerConnectionPool acknowledgingPeer() throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var reply = new ClusterMessage();
            switch (message.getType()) {
                case PREPARE_TX -> reply.setType(ClusterMessageType.PREPARE_TX_ACK);
                case COMMIT_TX -> reply.setType(ClusterMessageType.COMMIT_TX_ACK);
                case FORWARD_TX_REQUEST -> {
                    reply.setType(ClusterMessageType.FORWARD_RESPONSE);
                    reply.setForwardBody(ForwardBody.encode(eJson
                            .toJson(OperationResponse.ok(OperationType.COMMIT_TRANSACTION, "Transaction committed"))));
                }
                default -> reply.setType(ClusterMessageType.ABORT_TX_ACK);
            }
            return reply;
        });
        return pool;
    }

    private static void recordPendingRun() throws Exception {
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(RUN_ID, 0L, TriggerRunLog.currentNodeId(),
                TestGlobals.DB, TestGlobals.COLL, "audit", "mark", EventType.CREATED, false, "admin", 0,
                System.currentTimeMillis(), List.of("watched"), List.of()));
    }

    private EnforcingDatabaseAccess bodyThatWroteOnlyToAnotherOwner() {
        final var database = new EnforcingDatabaseAccess("admin", null);
        database.beginTransaction();
        clientTracker.addTransactionParticipant(sessionClientIdOf(database), REMOTE);
        return database;
    }

    private static UUID sessionClientIdOf(EnforcingDatabaseAccess database) {
        try {
            return TestUtils.getPrivateField(database, "sessionClientId", UUID.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void a_trigger_whose_body_wrote_only_to_another_owner_consumes_its_run() throws Exception {
        recordPendingRun();
        final var database = bodyThatWroteOnlyToAnotherOwner();
        database.bufferTriggerRunConsume(RUN_ID);

        assertDoesNotThrow(database::commitTransaction);

        assertTrue(TriggerRunLog.recordIdsFor(RUN_ID).isEmpty(),
                "a run whose effects committed must not stay pending, or the next restart replays its body");
    }

    @Test
    public void a_trigger_whose_body_wrote_to_two_other_owners_consumes_its_run() throws Exception {
        recordPendingRun();
        final var database = bodyThatWroteOnlyToAnotherOwner();
        clientTracker.addTransactionParticipant(sessionClientIdOf(database), SECOND_REMOTE);
        database.bufferTriggerRunConsume(RUN_ID);

        assertDoesNotThrow(database::commitTransaction);

        assertTrue(TriggerRunLog.recordIdsFor(RUN_ID).isEmpty(),
                "a 2PC commit must prepare and apply the slice holding the consume op too");
    }

    @Test
    public void buffering_the_consume_marks_the_local_slice() {
        final var database = bodyThatWroteOnlyToAnotherOwner();
        final var sessionClientId = sessionClientIdOf(database);

        database.bufferTriggerRunConsume(RUN_ID);

        assertTrue(clientTracker.hasLocalSlice(sessionClientId),
                "the consume op lives in this node's slice, so the commit must include that slice");
        database.rollbackTransaction();
    }
}
