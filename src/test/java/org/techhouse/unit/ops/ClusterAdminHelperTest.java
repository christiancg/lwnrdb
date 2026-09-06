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
    }

    // Arms the sync gate (as start() would in production) with the given completion state.
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
    public void test_guard_rejects_admin_without_quorum() throws Exception {
        enable(3);
        assertEquals("503-2", Objects.requireNonNull(ClusterAdminHelper.guard(adminOp())).getErrorCode());
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

    // Procedure and trigger DDL are coordinator-serialized exactly as SAVE_SCHEMA/DELETE_SCHEMA are
    @Test
    public void test_procedure_and_trigger_ops_are_coordinated_admin_ops() {
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.SAVE_PROCEDURE));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.DELETE_PROCEDURE));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.SAVE_TRIGGER));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.DELETE_TRIGGER));
    }

    // Calling one is not: it runs where it lands and its own operations route themselves, like RUN_SCRIPT
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
