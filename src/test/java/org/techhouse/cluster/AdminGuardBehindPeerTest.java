package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterAdminHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminGuardBehindPeerTest {
    private static final long LOCAL_EPOCH = 4L;
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Configuration config = Configuration.getInstance();
    private final CoalescingSweep sweep = mock(CoalescingSweep.class);
    private CoalescingSweep realSweep;
    private Map<String, NodeInfo> members;
    private boolean origEnabled;
    private boolean origConfirmed;
    private int origExpected;

    private static NodeInfo node(String id, int port, NodeState state, long adminEpoch) {
        final var node = new NodeInfo(id, "127.0.0.1", port, state, 1L, 1L);
        node.setAdminEpoch(adminEpoch);
        return node;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        final var self = node("self", 9990, NodeState.ALIVE, LOCAL_EPOCH);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(self)));
        TestUtils.setPrivateField(membershipService, "self", self);
        members = TestUtils.getPrivateField(membershipService, "members", Map.class);
        members.put("self", self);
        TestUtils.setPrivateField(adminAntiEntropyService, "started", true);
        TestUtils.setPrivateField(adminAntiEntropyService, "adminSyncCompleted", new AtomicBoolean(true));
        realSweep = TestUtils.getPrivateField(adminAntiEntropyService, "sweep", CoalescingSweep.class);
        TestUtils.setPrivateField(adminAntiEntropyService, "sweep", sweep);
        TestUtils.setPrivateField(adminEpoch, "epoch", LOCAL_EPOCH);
        origConfirmed = adminEpoch.isConfirmed();
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(adminAntiEntropyService, "sweep", realSweep);
        TestUtils.setPrivateField(adminAntiEntropyService, "started", false);
        TestUtils.setPrivateField(adminAntiEntropyService, "adminSyncCompleted", new AtomicBoolean(false));
        members.clear();
        TestUtils.setPrivateField(membershipService, "self", null);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", origConfirmed);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
    }

    private static CreateCollectionRequest adminOp() {
        return new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL);
    }

    private static OperationResponse admittedGuard(OperationRequest request) {
        return ClusterAdminHelper.inAdminLane(request, () -> ClusterAdminHelper.guard(request));
    }

    private void peer(NodeState state, long epoch) {
        members.put("peer", node("peer", 9991, state, epoch));
    }

    private void unconfirmedPeerAtLocalEpoch() {
        final var peer = node("peer", 9991, NodeState.ALIVE, LOCAL_EPOCH);
        peer.setAdminEpochUnconfirmed(true);
        members.put("peer", peer);
    }

    private void unconfirmLocal() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "confirmed", false);
    }

    @Test
    public void test_a_coordinator_behind_an_alive_peer_answers_admin_syncing() {
        peer(NodeState.ALIVE, LOCAL_EPOCH + 1);

        final var response = admittedGuard(adminOp());

        assertEquals(ErrorCode.ADMIN_SYNCING.getCode(), response.getErrorCode(),
                "committing here would land at the epoch the peer already holds, without the op it holds there");
        verify(sweep).schedule();
    }

    @Test
    public void test_a_dead_peer_with_a_higher_epoch_does_not_block() {
        peer(NodeState.DEAD, LOCAL_EPOCH + 3);

        assertNull(admittedGuard(adminOp()));
        verify(sweep, never()).schedule();
    }

    @Test
    public void test_an_equal_or_lower_peer_epoch_passes() {
        peer(NodeState.ALIVE, LOCAL_EPOCH);
        assertNull(admittedGuard(adminOp()));

        peer(NodeState.ALIVE, LOCAL_EPOCH - 1);
        assertNull(admittedGuard(adminOp()));
    }

    @Test
    public void test_a_replicated_request_is_never_refused_for_being_behind() {
        peer(NodeState.ALIVE, LOCAL_EPOCH + 1);
        final var request = adminOp();
        request.setReplicated(true);

        assertNull(ClusterAdminHelper.guard(request));
        verify(sweep, never()).schedule();
    }

    @Test
    public void test_an_equal_epoch_confirmed_peer_refuses_an_unconfirmed_coordinator() throws Exception {
        unconfirmLocal();
        peer(NodeState.ALIVE, LOCAL_EPOCH);

        final var response = admittedGuard(adminOp());

        assertEquals(ErrorCode.ADMIN_SYNCING.getCode(), response.getErrorCode(),
                "a confirmed peer at the same epoch holds the op a majority acknowledged, which this node may lack");
        verify(sweep).schedule();
    }

    @Test
    public void test_an_equal_epoch_unconfirmed_peer_passes_an_unconfirmed_coordinator() throws Exception {
        unconfirmLocal();
        unconfirmedPeerAtLocalEpoch();

        assertNull(admittedGuard(adminOp()));
        verify(sweep, never()).schedule();
    }

    @Test
    public void test_an_equal_epoch_peer_passes_a_confirmed_coordinator() {
        unconfirmedPeerAtLocalEpoch();
        assertNull(admittedGuard(adminOp()));

        peer(NodeState.ALIVE, LOCAL_EPOCH);
        assertNull(admittedGuard(adminOp()));
    }

    @Test
    public void test_a_lower_confirmed_peer_passes_an_unconfirmed_coordinator() throws Exception {
        unconfirmLocal();
        peer(NodeState.ALIVE, LOCAL_EPOCH - 1);

        assertNull(admittedGuard(adminOp()));
    }
}
