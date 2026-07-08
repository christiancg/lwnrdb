package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterAdminHelperTest {
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
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
}
