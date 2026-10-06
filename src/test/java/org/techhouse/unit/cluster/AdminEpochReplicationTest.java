package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
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
    private final Cache cache = IocContainer.get(Cache.class);

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

    private ClusterMessage createCollectionAt(String collName, long epoch) {
        final var raw = "{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + collName + "\"}";
        final var message = envelope(ClusterMessageType.REPLICATE_ADMIN);
        message.setForwardBody(ForwardBody.encode(raw));
        message.setAdminEpoch(epoch);
        return message;
    }

    private ClusterMessage userUpsertAt(String username, long epoch) {
        final var user = new JsonObject();
        user.add("_id", new JsonString(username));
        user.add("passwordHash", new JsonString("hashed"));
        user.addProperty("admin", false);
        user.add("globalPermissions", new JsonArray());
        user.add("databasePermissions", new JsonObject());
        user.add("collectionPermissions", new JsonObject());
        user.add("scriptPermissions", new JsonObject());
        final var message = envelope(ClusterMessageType.REPLICATE_USER);
        message.setReplication(new ReplicationPayload(null, null, ReplicationOp.UPSERT, List.of(user), null));
        message.setAdminEpoch(epoch);
        return message;
    }

    @Test
    public void test_a_replicated_admin_op_adopts_the_epoch_unconfirmed() throws Exception {
        final var ack = pool.request(cluster.serverAddress(), createCollectionAt("replicated_coll", 1L),
                ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType(), ack.getErrorMessage());
        assertEquals(1L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed(),
                "a node that merely received the op has no quorum evidence; only the coordinator counted acks");
    }

    @Test
    public void test_a_replicated_user_op_adopts_the_epoch_unconfirmed() throws Exception {
        final var ack = pool.request(cluster.serverAddress(), userUpsertAt("replicated-user", 1L), ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType(), ack.getErrorMessage());
        assertEquals(1L, adminEpoch.current());
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

    @Test
    public void test_a_replicated_admin_op_past_a_gap_is_refused_without_applying() throws Exception {
        final var ack = pool.request(cluster.serverAddress(), createCollectionAt("gap_coll", 3L), ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.ERROR, ack.getType(),
                "an ack must mean this node's epoch moved to the op, or a conform can later erase what it acked");
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "gap_coll"),
                "a node that skipped epochs 1 and 2 must not hold data its epoch does not account for");
        assertEquals(0L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_replicated_user_op_past_a_gap_is_refused_without_applying() throws Exception {
        final var ack = pool.request(cluster.serverAddress(), userUpsertAt("gap-user", 2L), ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.ERROR, ack.getType());
        assertNull(cache.getAdminUserEntry("gap-user"));
        assertEquals(0L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_replicated_op_at_or_below_the_current_epoch_still_applies_without_adopting() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 2L);

        final var ack = pool.request(cluster.serverAddress(), createCollectionAt("old_epoch_coll", 2L), ACK_TIMEOUT_MS);

        assertNotNull(ack);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType(), ack.getErrorMessage());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "old_epoch_coll"));
        assertEquals(2L, adminEpoch.current());
    }

    @Test
    public void test_skips_ahead_only_past_the_next_epoch() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 4L);

        assertFalse(adminEpoch.skipsAhead(0L));
        assertFalse(adminEpoch.skipsAhead(4L));
        assertFalse(adminEpoch.skipsAhead(5L));
        assertTrue(adminEpoch.skipsAhead(6L));
        assertTrue(adminEpoch.skipsAhead(9L));
    }

    @Test
    public void test_a_replica_that_skipped_an_op_loses_the_tie_to_one_that_holds_it() throws Exception {
        assertTrue(outranks(2L, false, "a", 0L, false, "z"),
                "the complete replica must win on epoch instead of a node-id coin flip");
    }
}
