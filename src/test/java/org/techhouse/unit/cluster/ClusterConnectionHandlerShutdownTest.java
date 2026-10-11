package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.conn.InFlightRequests;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.test.TestGlobals;

public class ClusterConnectionHandlerShutdownTest extends ClusterConnectionHandlerTestBase {
    private static final String SHUTTING_DOWN = ErrorCode.SERVER_SHUTTING_DOWN.getCode();
    private static final String REPLICATED_ID = "replicated";

    private final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);

    private ClusterMessage forwardedSave(String id) {
        final var raw = "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
        final var message = envelope(ClusterMessageType.FORWARD_REQUEST);
        message.setForwardBody(ForwardBody.encode(raw));
        return message;
    }

    private ClusterMessage replicatedUpsert() {
        final var document = new JsonObject();
        document.addProperty("_id", REPLICATED_ID);
        document.addProperty("v", 1);
        final var message = envelope(ClusterMessageType.REPLICATE);
        message.setReplication(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(document), null, List.of("7")));
        return message;
    }

    private ClusterMessage forwardedTransactionRollback() {
        final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
        message.setTxSessionId(UUID.randomUUID().toString());
        message.setTxId(UUID.randomUUID().toString());
        message.setForwardBody(ForwardBody.encode("{\"type\":\"ROLLBACK_TRANSACTION\"}"));
        return message;
    }

    private ClusterMessage send(ClusterMessage message) throws Exception {
        return pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS);
    }

    private static String bodyOf(ClusterMessage response) {
        return ForwardBody.decode(response.getForwardBody());
    }

    @Test
    public void test_writes_are_accepted_before_shutdown() throws Exception {
        final var response = send(forwardedSave("before"));

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, response.getType());
        assertEquals(OperationStatus.OK, findById("before").getStatus());
    }

    @Test
    public void test_a_forwarded_save_answers_503_13_once_writes_are_refused() throws Exception {
        cluster.server().refuseWrites();

        final var response = send(forwardedSave("refused"));

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, response.getType(),
                "the edge relays the body to its client, so the refusal must arrive in the forwarded shape");
        assertTrue(bodyOf(response).contains(SHUTTING_DOWN), bodyOf(response));
        assertNotEquals(OperationStatus.OK, findById("refused").getStatus(),
                "a write applied now would lose its index events to the draining background queue");
    }

    @Test
    public void test_a_replicate_is_refused_once_writes_are_refused() throws Exception {
        cluster.server().refuseWrites();

        final var response = send(replicatedUpsert());

        assertEquals(ClusterMessageType.ERROR, response.getType(),
                "the coordinator counts anything but an ack as unacknowledged, as if this node had left");
        assertNotEquals(OperationStatus.OK, findById(REPLICATED_ID).getStatus());
    }

    @Test
    public void test_a_commit_tx_is_refused_once_writes_are_refused() throws Exception {
        cluster.server().refuseWrites();
        final var commit = envelope(ClusterMessageType.COMMIT_TX);
        commit.setTxSessionId(UUID.randomUUID().toString());
        commit.setTxId(UUID.randomUUID().toString());

        final var response = send(commit);

        assertEquals(ClusterMessageType.ERROR, response.getType(),
                "a prepared slice is left for recovery to finish after the restart");
        assertTrue(response.getErrorMessage().contains("shutting down"), response.getErrorMessage());
    }

    @Test
    public void test_a_forwarded_transaction_rollback_is_still_answered() throws Exception {
        cluster.server().refuseWrites();

        final var response = send(forwardedTransactionRollback());

        final var refused = response.getType() == ClusterMessageType.FORWARD_RESPONSE
                && bodyOf(response).contains(SHUTTING_DOWN);
        assertFalse(refused, "a rollback releases locks and writes nothing, so shutdown must let it through");
    }

    @Test
    public void test_reads_are_still_answered_while_writes_are_refused() throws Exception {
        cluster.server().refuseWrites();

        final var response = send(envelope(ClusterMessageType.ADMIN_SNAPSHOT));

        assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, response.getType());
    }

    @Test
    public void test_an_admitted_peer_write_is_counted_in_flight() throws Exception {
        final Thread writer;
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            writer = Thread.ofVirtual().start(() -> {
                try {
                    send(forwardedSave("counted"));
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(awaitOneInFlight(), "a peer write blocked on its lock must hold the shutdown drains back");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        writer.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(inFlightRequests.awaitIdle(TimeUnit.SECONDS.toMillis(10)),
                "the count must drop once the write is answered");
    }

    @SuppressWarnings("BusyWait")
    private boolean awaitOneInFlight() throws InterruptedException {
        final var deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            if (inFlightRequests.current() == 1) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
