package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterCoordinatorTest {
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
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
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var coll = "coll-" + i;
            if (!ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    @Test
    public void test_disabled_allows_everything() {
        assertEquals(WriteGuard.Kind.ALLOW, coordinator.guardWrite(TestGlobals.DB, TestGlobals.COLL).kind());
    }

    @Test
    public void test_admin_db_is_allowed() throws Exception {
        enable(1);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        assertEquals(WriteGuard.Kind.ALLOW, coordinator.guardWrite(Globals.ADMIN_DB_NAME, "databases").kind());
    }

    @Test
    public void test_no_quorum_rejected() throws Exception {
        enable(3);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        assertEquals(WriteGuard.Kind.NO_QUORUM, coordinator.guardWrite(TestGlobals.DB, TestGlobals.COLL).kind());
    }

    @Test
    public void test_owner_with_quorum_allowed() throws Exception {
        enable(1);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        assertEquals(WriteGuard.Kind.ALLOW, coordinator.guardWrite(TestGlobals.DB, TestGlobals.COLL).kind());
    }

    @Test
    public void test_non_owner_rejected_with_owner_address() throws Exception {
        enable(2);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990), node("other", 9991))));
        final var guard = coordinator.guardWrite(TestGlobals.DB, collectionOwnedByOther());
        assertEquals(WriteGuard.Kind.NOT_OWNER, guard.kind());
        assertNotNull(guard.ownerAddress());
    }

    @Test
    public void test_replicate_not_applicable_when_disabled() {
        assertEquals(ReplicationOutcome.NOT_APPLICABLE,
                coordinator.replicateUpsert(TestGlobals.DB, TestGlobals.COLL, List.of("a")));
        assertEquals(ReplicationOutcome.NOT_APPLICABLE,
                coordinator.replicateDelete(TestGlobals.DB, TestGlobals.COLL, List.of("a")));
    }

    @Test
    public void test_replicate_not_applicable_when_not_owner() throws Exception {
        enable(2);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990), node("other", 9991))));
        assertEquals(ReplicationOutcome.NOT_APPLICABLE,
                coordinator.replicateDelete(TestGlobals.DB, collectionOwnedByOther(), List.of("a")));
    }

    @Test
    public void test_guard_admin_allows_when_disabled() {
        assertEquals(WriteGuard.Kind.ALLOW, coordinator.guardAdmin().kind());
    }

    @Test
    public void test_guard_admin_allows_with_quorum() throws Exception {
        enable(1);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        assertEquals(WriteGuard.Kind.ALLOW, coordinator.guardAdmin().kind());
    }

    @Test
    public void test_guard_admin_no_quorum() throws Exception {
        enable(3);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        assertEquals(WriteGuard.Kind.NO_QUORUM, coordinator.guardAdmin().kind());
    }

    @Test
    public void test_replicate_admin_op_not_applicable_when_disabled() {
        assertEquals(ReplicationOutcome.NOT_APPLICABLE,
                coordinator.replicateAdminOp(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL), "alice"));
    }
}
