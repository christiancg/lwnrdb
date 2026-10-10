package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.cluster.admin.AdminRecords;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * Single-node cluster: with no peers the required-ack count is zero, so replication completes without
 * spawning network threads - covering the happy paths reliably regardless of CI scheduling.
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

    private static AdminUserEntry bob() {
        return new AdminUserEntry("bob", "hash-bob", false, Set.of(), new HashMap<>(), new HashMap<>());
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
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
                coordinator.replicateDelete(TestGlobals.DB, TestGlobals.COLL, List.of("gone"), null));
    }

    @Test
    public void test_replicate_admin_records_meets_quorum() throws Exception {
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator.replicateAdminRecords(
                AdminRecords.of(List.of(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL)))));
    }

    @Test
    public void test_replicate_user_record_and_its_tombstone_meet_quorum() throws Exception {
        AdminOperationHelper.saveUserEntry(bob());
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.replicateAdminRecords(AdminRecords.of(List.of(AdminRecordKey.user("bob")))));
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator
                .replicateAdminRecords(List.of(AdminRecord.tombstone(AdminRecordKey.user("bob"), Long.MAX_VALUE))));
    }

    @Test
    public void test_broadcast_reindex_meets_quorum() {
        assertEquals(ReplicationOutcome.QUORUM_MET,
                coordinator.broadcastReindex(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null)));
    }

    @Test
    public void test_replicator_broadcasts_meet_quorum_with_no_peers() {
        final var docPayload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("b1")), null);
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcast(docPayload));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastAdmin(new AdminSnapshotPayload(List.of())));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastReindex("{\"type\":\"REINDEX\"}"));
    }

    @Test
    public void test_two_node_ids_at_one_address_count_as_one_ack() throws Exception {
        final var self = new NodeInfo("self", "127.0.0.1", 19990, NodeState.ALIVE, 1L, 1L);
        final var stale = new NodeInfo("stale-id", "127.0.0.1", 19991, NodeState.ALIVE, 1L, 1L);
        final var fresh = new NodeInfo("fresh-id", "127.0.0.1", 19991, NodeState.ALIVE, 1L, 1L);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        members.put(stale.getNodeId(), stale);
        members.put(fresh.getNodeId(), fresh);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 3);
        ownership.onMembershipChanged(membershipService.membershipView());
        final var originalTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 200L);
        final var realPool = TestUtils.getPrivateField(replicator, "pool", PeerConnectionPool.class);
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenThrow(new IllegalStateException("unreachable"));
        TestUtils.setPrivateField(replicator, "pool", pool);
        try {
            replicator.broadcast(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                    List.of(doc("dedup")), null));

            verify(pool, after(500).times(1)).request(eq(stale.address()), any(), anyLong());
        } finally {
            TestUtils.setPrivateField(replicator, "pool", realPool);
            TestUtils.setPrivateField(config, "replicationAckTimeoutMs", originalTimeout);
        }
    }
}
