package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminEpochReplicationTest {
    private static final long ACK_TIMEOUT_MS = 30000L;
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final PeerConnectionPool pool = new PeerConnectionPool();
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start(true, ACK_TIMEOUT_MS);
        cluster.configureMembership(1,
                new NodeInfo("self", "127.0.0.1", cluster.serverPort(), NodeState.ALIVE, 1L, 1L));
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(adminEpoch, "unreadable", false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        cluster.stop();
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(adminEpoch, "unreadable", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private ClusterMessage envelope(ClusterMessageType type) {
        final var message = new ClusterMessage();
        message.setType(type);
        message.setSecret(ClusterTestHarness.SECRET);
        return message;
    }

    @Test
    public void test_a_replicated_admin_op_adopts_the_epoch_unconfirmed() throws Exception {
        final var raw = "{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"replicated_coll\"}";
        final var message = envelope(ClusterMessageType.REPLICATE_ADMIN);
        message.setForwardBody(ForwardBody.encode(raw));
        message.setAdminEpoch(7L);

        final var ack = pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType(), ack.getErrorMessage());
        assertEquals(7L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed(),
                "a node that merely received the op has no quorum evidence; only the coordinator counted acks");
    }

    @Test
    public void test_a_replicated_user_op_adopts_the_epoch_unconfirmed() throws Exception {
        final var user = new JsonObject();
        user.add("_id", new JsonString("replicated-user"));
        user.add("passwordHash", new JsonString("hashed"));
        user.addProperty("admin", false);
        user.add("globalPermissions", new JsonArray());
        user.add("databasePermissions", new JsonObject());
        user.add("collectionPermissions", new JsonObject());
        user.add("scriptPermissions", new JsonObject());
        final var message = envelope(ClusterMessageType.REPLICATE_USER);
        message.setReplication(new ReplicationPayload(null, null, ReplicationOp.UPSERT, List.of(user), null));
        message.setAdminEpoch(9L);

        final var ack = pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType(), ack.getErrorMessage());
        assertEquals(9L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed());
    }

    private static boolean outranks(long epoch, boolean confirmed, String nodeId, long bestEpoch, boolean bestConfirmed,
            String bestNodeId) throws Exception {
        final Method method = AdminAntiEntropyService.class.getDeclaredMethod("outranks", long.class, boolean.class,
                String.class, long.class, boolean.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, epoch, confirmed, nodeId, bestEpoch, bestConfirmed, bestNodeId);
    }

    @Test
    public void test_a_coordinator_that_lost_quorum_loses_an_equal_epoch_tie_to_the_majority() throws Exception {
        assertTrue(outranks(5L, true, "aaaa-majority", 5L, false, "zzzz-minority"),
                "a confirmed epoch wins even when its node id sorts lower than the unconfirmed one's");
        assertFalse(outranks(5L, false, "zzzz-minority", 5L, true, "aaaa-majority"),
                "the unconfirmed side must not win the tie back on node id");
    }

    @Test
    public void test_node_id_still_breaks_a_tie_when_both_sides_agree_on_confirmation() throws Exception {
        assertTrue(outranks(5L, true, "b", 5L, true, "a"));
        assertFalse(outranks(5L, true, "a", 5L, true, "b"));
        assertTrue(outranks(5L, false, "b", 5L, false, "a"));
    }

    @Test
    public void test_a_higher_epoch_still_wins_regardless_of_confirmation() throws Exception {
        assertTrue(outranks(6L, false, "a", 5L, true, "z"));
        assertFalse(outranks(4L, true, "z", 5L, false, "a"));
        assertTrue(outranks(10L, false, "a", 2L, true, "z"));
        assertFalse(outranks(1L, true, "z", 9L, false, "a"));
    }
}
