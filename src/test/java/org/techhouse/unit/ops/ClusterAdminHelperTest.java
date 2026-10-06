package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterAdminHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterAdminHelperTest {
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node() {
        return new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L);
    }

    private CreateCollectionRequest adminOp() {
        return new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL);
    }

    @BeforeEach
    public void setUp() {
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(adminAntiEntropyService, "started", false);
        TestUtils.setPrivateField(adminAntiEntropyService, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
    }

    private void armAdminSync(boolean completed) throws Exception {
        TestUtils.setPrivateField(adminAntiEntropyService, "started", true);
        TestUtils.setPrivateField(adminAntiEntropyService, "adminSyncCompleted", new AtomicBoolean(completed));
    }

    private void enable(int expectedSize) throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", expectedSize);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node())));
    }

    private void afterAdminOpWith(ReplicationOutcome outcome) throws Exception {
        final var replicator = org.mockito.Mockito.mock(org.techhouse.cluster.Replicator.class);
        final var coordinator = IocContainer.get(org.techhouse.cluster.ClusterCoordinator.class);
        final var original = TestUtils.getPrivateField(coordinator, "replicator",
                org.techhouse.cluster.Replicator.class);
        org.mockito.Mockito
                .when(replicator.broadcastAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(outcome);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        try {
            ClusterAdminHelper.afterAdminOp(adminOp(), "alice",
                    new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok"));
        } finally {
            TestUtils.setPrivateField(coordinator, "replicator", original);
        }
    }

    @Test
    public void test_a_replication_timeout_leaves_the_epoch_unconfirmed() throws Exception {
        enable(1);
        armAdminSync(true);

        afterAdminOpWith(ReplicationOutcome.TIMEOUT);

        assertEquals(1L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed(),
                "an epoch no peer acknowledged must not win an equal-epoch tie by node id against one a"
                        + " majority really did commit");
    }

    @Test
    public void test_a_quorum_met_replication_confirms_the_epoch() throws Exception {
        enable(1);
        armAdminSync(true);

        afterAdminOpWith(ReplicationOutcome.QUORUM_MET);

        assertEquals(1L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_standalone_node_neither_bumps_nor_unconfirms() throws Exception {
        afterAdminOpWith(ReplicationOutcome.NOT_CLUSTERED);

        assertEquals(0L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed(), "an unclustered node has nothing to reach and nothing to lose");
    }

    @Test
    public void test_guard_null_for_non_admin_op() {
        assertNull(ClusterAdminHelper.guard(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_guard_null_when_disabled() {
        assertNull(ClusterAdminHelper.guard(adminOp()));
    }

    @Test
    public void test_guard_allows_admin_with_quorum() throws Exception {
        enable(1);
        assertNull(ClusterAdminHelper.guard(adminOp()));
    }

    @Test
    public void test_a_coordinator_whose_conform_was_incomplete_answers_admin_syncing() throws Exception {
        enable(1);
        armAdminSync(false);

        assertEquals("503-5", Objects.requireNonNull(ClusterAdminHelper.guard(adminOp())).getErrorCode());
    }

    private void becomeNonCoordinator(int expectedSize) throws Exception {
        enable(expectedSize);
        armAdminSync(true);
        ownership.setSelfNodeId("not-the-coordinator");
        ownership.onMembershipChanged(
                new MembershipView(List.of(node(), new NodeInfo("other", "127.0.0.1", 9991, NodeState.ALIVE, 1L, 1L))));
        org.junit.jupiter.api.Assumptions.assumeFalse(ownership.isAdminCoordinator(),
                "this node must not be the coordinator");
    }

    @Test
    public void test_guard_refuses_a_coordinated_op_on_a_non_coordinator() throws Exception {
        becomeNonCoordinator(2);

        assertEquals("421-1", Objects.requireNonNull(ClusterAdminHelper.guard(adminOp())).getErrorCode(),
                "a DDL that runs where it cannot be replicated diverges this node at an unchanged epoch");
    }

    @Test
    public void test_guard_lets_a_replicated_op_through_on_a_non_coordinator() throws Exception {
        becomeNonCoordinator(2);
        final var request = adminOp();
        request.setReplicated(true);

        assertNull(ClusterAdminHelper.guard(request));
    }

    @Test
    public void test_guard_ignores_non_admin_ops_on_a_non_coordinator() throws Exception {
        becomeNonCoordinator(2);

        assertNull(ClusterAdminHelper.guard(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_guard_answers_not_owner_before_no_quorum_on_a_non_coordinator() throws Exception {
        becomeNonCoordinator(5);

        assertEquals("421-1", Objects.requireNonNull(ClusterAdminHelper.guard(adminOp())).getErrorCode());
    }

    @Test
    public void test_guard_rejects_admin_without_quorum() throws Exception {
        enable(3);
        assertEquals("503-2", Objects.requireNonNull(ClusterAdminHelper.guard(adminOp())).getErrorCode());
    }

    @Test
    public void test_a_replicated_admin_op_does_not_rebroadcast() throws Exception {
        enable(1);
        armAdminSync(true);
        final var replicator = org.mockito.Mockito.mock(org.techhouse.cluster.Replicator.class);
        final var coordinator = IocContainer.get(org.techhouse.cluster.ClusterCoordinator.class);
        final var original = TestUtils.getPrivateField(coordinator, "replicator",
                org.techhouse.cluster.Replicator.class);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        try {
            final var request = adminOp();
            request.setReplicated(true);
            final var response = new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok");
            final var epochBefore = adminEpoch.current();

            assertSame(response, ClusterAdminHelper.afterAdminOp(request, "alice", response));

            org.mockito.Mockito.verifyNoInteractions(replicator);
            assertEquals(epochBefore, adminEpoch.current(),
                    "a replica applying someone else's admin op must not bump its own epoch");
        } finally {
            TestUtils.setPrivateField(coordinator, "replicator", original);
        }
    }

    @Test
    public void test_a_client_admin_op_does_replicate() throws Exception {
        enable(1);
        armAdminSync(true);
        final var replicator = org.mockito.Mockito.mock(org.techhouse.cluster.Replicator.class);
        final var coordinator = IocContainer.get(org.techhouse.cluster.ClusterCoordinator.class);
        final var original = TestUtils.getPrivateField(coordinator, "replicator",
                org.techhouse.cluster.Replicator.class);
        org.mockito.Mockito
                .when(replicator.broadcastAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.techhouse.cluster.ReplicationOutcome.QUORUM_MET);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        try {
            final var response = new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok");
            final var epochBefore = adminEpoch.current();

            ClusterAdminHelper.afterAdminOp(adminOp(), "alice", response);

            assertEquals(epochBefore + 1, adminEpoch.current(),
                    "a client's own admin op is the one that bumps the epoch and replicates");
        } finally {
            TestUtils.setPrivateField(coordinator, "replicator", original);
        }
    }

    @Test
    public void test_losing_coordinatorship_mid_op_is_reported_retryably() throws Exception {
        enable(1);
        armAdminSync(true);
        ownership.setSelfNodeId("not-the-coordinator");
        ownership.onMembershipChanged(
                new MembershipView(List.of(node(), new NodeInfo("other", "127.0.0.1", 9991, NodeState.ALIVE, 1L, 1L))));
        final var wasCoordinator = ownership.isAdminCoordinator();
        org.junit.jupiter.api.Assumptions.assumeFalse(wasCoordinator, "this node must not be the coordinator");
        final var response = new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok");

        final var answered = ClusterAdminHelper.afterAdminOp(adminOp(), "alice", response);

        assertEquals("421-1", answered.getErrorCode(),
                "DDL that could not be replicated because coordinatorship moved must be retryable, not OK");
    }

    @Test
    public void test_a_missing_user_entry_is_not_reported_as_success() throws Exception {
        enable(1);
        armAdminSync(true);
        final var coordinator = IocContainer.get(org.techhouse.cluster.ClusterCoordinator.class);

        final var outcome = coordinator.replicateUserOp("nobody-at-all", false);

        assertEquals(org.techhouse.cluster.ReplicationOutcome.NOT_COORDINATOR, outcome,
                "a user record that is not there is a failure to replicate, not a non-clustered no-op");
    }

    @Test
    public void test_after_admin_op_passes_through_non_admin() {
        final var response = new OperationResponse(OperationType.FIND_BY_ID, OperationStatus.OK, "ok");
        assertSame(response, ClusterAdminHelper.afterAdminOp(new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL),
                "alice", response));
    }

    @Test
    public void test_after_admin_op_passes_through_failed_response() {
        final var error = new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ENTRY_NOT_FOUND);
        assertSame(error, ClusterAdminHelper.afterAdminOp(adminOp(), "alice", error));
    }

    @Test
    public void test_after_admin_op_passes_through_when_not_applicable() {
        final var response = new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok");
        assertSame(response, ClusterAdminHelper.afterAdminOp(adminOp(), "alice", response));
    }

    @Test
    public void test_procedure_and_trigger_ops_are_coordinated_admin_ops() {
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.SAVE_PROCEDURE));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.DELETE_PROCEDURE));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.SAVE_TRIGGER));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.DELETE_TRIGGER));
    }

    @Test
    public void test_call_procedure_and_lists_are_not_coordinated_admin_ops() {
        assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.CALL_PROCEDURE));
        assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.LIST_PROCEDURES));
        assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.LIST_TRIGGERS));
        assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.RUN_SCRIPT));
    }

    @Test
    public void test_guard_rejects_procedure_op_without_quorum() throws Exception {
        enable(3);
        final var request = new org.techhouse.ops.req.SaveProcedureRequest(TestGlobals.DB, "p", "return 1;");
        assertEquals("503-2", Objects.requireNonNull(ClusterAdminHelper.guard(request)).getErrorCode());
    }

    @Test
    public void test_guard_rejects_trigger_op_without_quorum() throws Exception {
        enable(3);
        final var request = new org.techhouse.ops.req.SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t",
                java.util.List.of("CREATED"), "p");
        assertEquals("503-2", Objects.requireNonNull(ClusterAdminHelper.guard(request)).getErrorCode());
    }

    @Test
    public void test_guard_rejects_user_op_without_quorum() throws Exception {
        enable(3);
        final var request = new CreateUserRequest();
        request.setUsername("bob");
        assertEquals("503-2", Objects.requireNonNull(ClusterAdminHelper.guard(request)).getErrorCode());
    }

    @Test
    public void test_guard_rejects_coordinator_still_syncing() throws Exception {
        enable(1);
        armAdminSync(false);
        assertEquals("503-5", Objects.requireNonNull(ClusterAdminHelper.guard(adminOp())).getErrorCode());
    }

    @Test
    public void test_guard_allows_coordinator_after_sync_completed() throws Exception {
        enable(1);
        armAdminSync(true);
        assertNull(ClusterAdminHelper.guard(adminOp()));
    }

    @Test
    public void test_after_admin_op_bumps_epoch_on_coordinator() throws Exception {
        enable(1);
        final var before = adminEpoch.current();
        final var response = new OperationResponse(OperationType.CREATE_COLLECTION, OperationStatus.OK, "ok");
        ClusterAdminHelper.afterAdminOp(adminOp(), "alice", response);
        assertEquals(before + 1, adminEpoch.current());
    }

    @Test
    public void test_after_admin_op_passes_through_user_ops_when_not_applicable() {
        for (final var request : userOps()) {
            final var response = new OperationResponse(request.getType(), OperationStatus.OK, "ok");
            assertSame(response, ClusterAdminHelper.afterAdminOp(request, "alice", response),
                    "expected passthrough for " + request.getType());
        }
    }

    private static List<OperationRequest> userOps() {
        final var create = new CreateUserRequest();
        create.setUsername("u");
        final var delete = new DeleteUserRequest();
        delete.setUsername("u");
        final var setPassword = new SetPasswordRequest();
        setPassword.setUsername("u");
        final var changePermissions = new ChangePermissionsRequest();
        changePermissions.setUsername("u");
        return List.of(create, delete, setPassword, changePermissions);
    }
}
