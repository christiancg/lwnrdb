package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.Tx2pcCoordinator;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class Tx2pcCoordinatorTest {
    private static final String REMOTE = "127.0.0.1:59999";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcCoordinator coordinator = IocContainer.get(Tx2pcCoordinator.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private boolean origEnabled;
    private int origExpected;
    private PeerConnectionPool origPool;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 5000, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        origPool = TestUtils.getPrivateField(coordinator, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        final var self = node();
        TestUtils.setPrivateField(membershipService, "members",
                new ConcurrentHashMap<>(java.util.Map.of("self", self)));
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(coordinator, "pool", origPool);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID clientWithLocalAndRemoteSlice(String id) {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        final var clientId = clientTracker.addClient(socket);
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var req = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        req.setObject(obj);
        req.set_id(id);
        processor.processMessage(req, clientId);
        clientTracker.markLocalSlice(clientId);
        clientTracker.addTransactionParticipant(clientId, REMOTE);
        return clientId;
    }

    private void poolReplies(ClusterMessageType prepareReply) {
        final var pool = mock(PeerConnectionPool.class);
        try {
            when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
                final ClusterMessage message = invocation.getArgument(1);
                final var reply = new ClusterMessage();
                reply.setType(switch (message.getType()) {
                    case PREPARE_TX -> prepareReply;
                    case COMMIT_TX -> ClusterMessageType.COMMIT_TX_ACK;
                    default -> ClusterMessageType.ABORT_TX_ACK;
                });
                return reply;
            });
            TestUtils.setPrivateField(coordinator, "pool", pool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_commit_with_unanimous_yes_commits_all() {
        poolReplies(ClusterMessageType.PREPARE_TX_ACK);
        final var clientId = clientWithLocalAndRemoteSlice("tpc-commit");
        final var response = coordinator.commit(clientId);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationStatus.OK, findStatus("tpc-commit"));
    }

    @Test
    public void test_commit_with_a_no_vote_aborts_all() {
        poolReplies(ClusterMessageType.ERROR);
        final var clientId = clientWithLocalAndRemoteSlice("tpc-abort");
        final var response = coordinator.commit(clientId);
        assertEquals("409-7", response.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("tpc-abort"));
    }

    @Test
    public void test_rollback_aborts_all_participants() {
        poolReplies(ClusterMessageType.ABORT_TX_ACK);
        final var clientId = clientWithLocalAndRemoteSlice("tpc-rollback");
        final var response = coordinator.rollback(clientId);
        assertEquals(OperationType.ROLLBACK_TRANSACTION, response.getType());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("tpc-rollback"));
        org.junit.jupiter.api.Assertions.assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_rollback_without_active_transaction() {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        final var clientId = clientTracker.addClient(socket);
        assertEquals("409-4", coordinator.rollback(clientId).getErrorCode());
    }

    @Test
    public void test_commit_without_active_transaction() {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        final var clientId = clientTracker.addClient(socket);
        assertEquals(OperationType.COMMIT_TRANSACTION, coordinator.commit(clientId).getType());
        assertEquals("409-4", coordinator.commit(clientId).getErrorCode());
    }
}
