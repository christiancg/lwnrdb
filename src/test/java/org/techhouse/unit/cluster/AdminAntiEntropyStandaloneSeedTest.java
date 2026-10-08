package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyStandaloneSeedTest {
    private static final String POPULATED_ID = "00000000-0000-4000-8000-000000000000";
    private static final String FRESH_ID = "ffffffff-ffff-4fff-bfff-ffffffffffff";
    private static final String STANDALONE_DB = "standalone_db";

    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration config = Configuration.getInstance();
    private final PeerConnectionPool mockPool = mock(PeerConnectionPool.class);
    private PeerConnectionPool realPool;
    private boolean origEnabled;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private void joinAs(String selfId, String peerId) throws Exception {
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        final var self = node(selfId, 19990);
        members.put(selfId, self);
        members.put(peerId, node(peerId, 19991));
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        origEnabled = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(service, "pool", mockPool);
        TestUtils.setPrivateField(service, "started", true);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.setPrivateField(service, "started", false);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void peerAnswers(long epoch, boolean confirmed, String nodeId, List<JsonObject> dbs, List<JsonObject> users)
            throws Exception {
        final var snapshot = new AdminSnapshotPayload(epoch, dbs, List.of(), users, new JsonObject());
        snapshot.setEpochConfirmed(confirmed);
        snapshot.setNodeId(nodeId);
        final var ack = new ClusterMessage();
        ack.setType(ClusterMessageType.ADMIN_SNAPSHOT_ACK);
        ack.setAdminSnapshot(snapshot);
        when(mockPool.request(any(), any(), anyLong())).thenReturn(ack);
    }

    private static AdminUserEntry analyst() {
        return new AdminUserEntry("analyst", PasswordHasher.hash("analyst-password"), false, new HashSet<>(),
                new HashMap<>(), new HashMap<>());
    }

    private static AdminDbEntry standaloneDb() {
        return new AdminDbEntry(STANDALONE_DB, new ArrayList<>(), new ArrayList<>());
    }

    @Test
    public void test_a_fresh_node_with_the_higher_id_conforms_to_a_seeded_populated_peer() throws Exception {
        joinAs(FRESH_ID, POPULATED_ID);
        peerAnswers(1L, false, POPULATED_ID, List.of(standaloneDb().getData()), List.of(analyst().getData()));

        service.reconcile();

        assertNotNull(cache.getAdminDbEntry(STANDALONE_DB),
                "at epoch 0 the higher node id won, and the fresh node's first CREATE_DATABASE then erased this one");
        assertNotNull(cache.getAdminUserEntry("analyst"));
        assertEquals(1L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_seeded_populated_node_keeps_its_state_against_a_fresh_peer_at_zero() throws Exception {
        joinAs(POPULATED_ID, FRESH_ID);
        AdminOperationHelper.saveDatabaseEntry(standaloneDb());
        AdminOperationHelper.saveUserEntry(analyst());
        TestUtils.setPrivateField(adminEpoch, "epoch", 1L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", false);
        peerAnswers(0L, true, FRESH_ID, List.of(), List.of());

        service.reconcile();

        assertNotNull(cache.getAdminDbEntry(STANDALONE_DB));
        assertNotNull(cache.getAdminUserEntry("analyst"));
        assertEquals(1L, adminEpoch.current());
    }
}
