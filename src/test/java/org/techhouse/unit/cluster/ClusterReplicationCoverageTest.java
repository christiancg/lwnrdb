package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * Deterministic coverage of the coordinator/replicator paths using a single-node cluster: with no peers the
 * required-ack count is zero, so replication completes immediately without spawning any network threads —
 * covering the happy paths reliably regardless of CI scheduling.
 */
public class ClusterReplicationCoverageTest {
    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private boolean origEnabled;
    private int origExpected;

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private static AdminUserEntry user(String username) {
        return new AdminUserEntry(username, "hash-" + username, false, Set.of(), new HashMap<>(), new HashMap<>());
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        // Single-node cluster: self owns every collection and is the admin coordinator.
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        final var self = new NodeInfo("self", "127.0.0.1", 19990, NodeState.ALIVE, 1L, 1L);
        members.put("self", self);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_replicate_upsert_reads_committed_doc_and_meets_quorum() {
        ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("u1")), null));
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.replicateUpsert(TestGlobals.DB, TestGlobals.COLL, List.of("u1")));
    }

    @Test
    public void test_replicate_delete_meets_quorum() {
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.replicateDelete(TestGlobals.DB, TestGlobals.COLL, List.of("gone")));
    }

    @Test
    public void test_replicate_admin_op_meets_quorum() {
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.replicateAdminOp(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL), "alice"));
    }

    @Test
    public void test_replicate_user_op_upsert_and_delete_meet_quorum() throws Exception {
        AdminOperationHelper.saveUserEntry(user("bob"));
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator.replicateUserOp("bob", false));
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator.replicateUserOp("bob", true));
    }

    @Test
    public void test_replicate_user_op_not_applicable_for_unknown_user() {
        assertEquals(ReplicationOutcome.NOT_APPLICABLE, coordinator.replicateUserOp("nobody", false));
    }

    @Test
    public void test_replicator_broadcasts_meet_quorum_with_no_peers() {
        final var docPayload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("b1")), null);
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcast(docPayload));
        final var userPayload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                ReplicationOp.UPSERT, List.of(user("carol").getData()), null);
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastUser(userPayload));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastAdmin("{\"type\":\"REINDEX\"}", "alice"));
    }
}
