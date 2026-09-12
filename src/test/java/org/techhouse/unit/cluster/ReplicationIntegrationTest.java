package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.techhouse.test.ClusterTestHarness.SECRET;
import static org.techhouse.test.ClusterTestHarness.node;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicationIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_replicate_message_applies_over_socket() throws Exception {
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE, SECRET, node("peer", 1), null);
        message.setReplication(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("s1")), null));
        final var ack = pool.request(cluster.serverAddress(), message, 3000);
        assertEquals(ClusterMessageType.REPLICATE_ACK, ack.getType());
        assertEquals(OperationStatus.OK, findStatus("s1"));
    }

    @Test
    public void test_broadcast_single_node_is_immediately_met() throws Exception {
        cluster.configureMembership(1, node("self", 19990));
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("b1")), null);
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcast(payload));
    }

    @Test
    public void test_broadcast_reaches_quorum_with_a_reachable_peer() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("peer", cluster.serverPort()));
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("b2")), null);
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcast(payload));
        assertEquals(OperationStatus.OK, findStatus("b2"));
    }

    @Test
    public void test_broadcast_times_out_when_peer_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("peer", 1));
        final var payload = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null,
                List.of("nope"));
        assertEquals(ReplicationOutcome.TIMEOUT, replicator.broadcast(payload));
    }
}
