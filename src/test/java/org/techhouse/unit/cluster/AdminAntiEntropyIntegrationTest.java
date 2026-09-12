package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.ClusterTestHarness.SECRET;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, SECRET, null, null);
    }

    @Test
    public void test_admin_snapshot_over_the_wire_returns_state_and_epoch() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 3L);
        final var ack = pool.request(cluster.serverAddress(), message(ClusterMessageType.ADMIN_SNAPSHOT), 3000);
        assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, ack.getType());
        final var snapshot = ack.getAdminSnapshot();
        assertNotNull(snapshot);
        assertEquals(3L, snapshot.getEpoch());
        assertTrue(snapshot.getDatabases().stream()
                .anyMatch(db -> TestGlobals.DB.equals(db.get(Globals.PK_FIELD).asJsonString().getValue())));
        assertTrue(snapshot.getCollections().stream()
                .anyMatch(coll -> Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)
                        .equals(coll.get(Globals.PK_FIELD).asJsonString().getValue())));
    }

    @Test
    public void test_replicate_admin_adopts_shipped_epoch() throws Exception {
        cluster.configureRemoteCoordinator();
        final var raw = eJson.toJson(new CreateCollectionRequest(TestGlobals.DB, "epoch-coll"));
        final var message = message(ClusterMessageType.REPLICATE_ADMIN);
        message.setForwardBody(ForwardBody.encode(raw));
        message.setAdminEpoch(7L);
        final var ack = pool.request(cluster.serverAddress(), message, 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertEquals(7L, adminEpoch.current());
    }

    @Test
    public void test_replicate_user_adopts_shipped_epoch() throws Exception {
        final var user = new AdminUserEntry("wireuser", "hash", false, Set.of(), Map.of(), Map.of());
        final var payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                ReplicationOp.UPSERT, List.of(user.getData()), null);
        final var message = message(ClusterMessageType.REPLICATE_USER);
        message.setReplication(payload);
        message.setAdminEpoch(9L);
        final var ack = pool.request(cluster.serverAddress(), message, 3000);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType());
        assertEquals(9L, adminEpoch.current());
        assertNotNull(cache.getAdminUserEntry("wireuser"));
    }
}
