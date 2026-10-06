package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;

public class ForwardedTxSliceLostTest extends ClusterConnectionHandlerTestBase {
    private static final String COORDINATOR = "127.0.0.1:5000";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final String sessionId = UUID.randomUUID().toString();
    private final String txId = UUID.randomUUID().toString();
    private final String otherTxId = UUID.randomUUID().toString();

    @AfterEach
    public void removeSession() {
        clientTracker.removeTxSession(sessionId);
    }

    private ClusterMessage forwarded(String id, String body, boolean continuation) {
        final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
        message.setActingUser("admin");
        message.setTxSessionId(sessionId);
        message.setTxId(id);
        message.setTxContinuation(continuation);
        message.setForwardBody(ForwardBody.encode(body));
        return message;
    }

    private static String save(String id) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
    }

    private static String findFirst() {
        return "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"first\"}";
    }

    private static String commit() {
        return "{\"type\":\"COMMIT_TRANSACTION\"}";
    }

    private OperationResponse send(String id, String body, boolean continuation) throws Exception {
        final var reply = pool.request(cluster.serverAddress(), forwarded(id, body, continuation), ACK_TIMEOUT_MS);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, reply.getType(), reply.getErrorMessage());
        return eJson.fromJson(ForwardBody.decode(reply.getForwardBody()), OperationResponse.class);
    }

    private ClusterMessage prepare() throws Exception {
        final var message = envelope(ClusterMessageType.PREPARE_TX);
        message.setTxSessionId(sessionId);
        message.setTxId(txId);
        message.setTxParticipants(List.of(COORDINATOR));
        return pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS);
    }

    @Test
    public void test_a_continuation_with_no_slice_is_refused_and_starts_nothing() throws Exception {
        final var refused = send(txId, save("after-loss"), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), refused.getErrorCode());
        assertNull(clientTracker.txSession(sessionId), "the session registered for the refused op holds nothing");
        assertEquals(OperationStatus.NOT_FOUND, findById("after-loss").getStatus());
    }

    @Test
    public void test_a_first_forward_still_starts_the_transaction() throws Exception {
        assertEquals(OperationStatus.OK, send(txId, save("first"), false).getStatus());

        assertEquals(OperationStatus.OK, send(txId, commit(), true).getStatus());
        assertEquals(OperationStatus.OK, findById("first").getStatus());
    }

    @Test
    public void test_a_continuation_of_the_live_slice_is_served() throws Exception {
        send(txId, save("first"), false);

        assertEquals(OperationStatus.OK, send(txId, save("second"), true).getStatus());
        assertEquals(OperationStatus.OK, send(txId, findFirst(), true).getStatus());
        assertEquals(OperationStatus.OK, send(txId, commit(), true).getStatus());

        assertEquals(OperationStatus.OK, findById("first").getStatus());
        assertEquals(OperationStatus.OK, findById("second").getStatus());
    }

    @Test
    public void test_a_slice_reaped_for_a_departed_edge_refuses_the_edges_next_op() throws Exception {
        send(txId, save("before-partition"), false);

        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));
        final var refused = send(txId, save("after-partition"), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), refused.getErrorCode(),
                "committing only the writes sent after the reap would report half a transaction as committed");
        assertEquals(ClusterMessageType.ERROR, prepare().getType(), "the participant must vote no");
        assertFalse(Tx2pcLog.isPrepared(txId));
        assertEquals(OperationStatus.NOT_FOUND, findById("before-partition").getStatus());
        assertEquals(OperationStatus.NOT_FOUND, findById("after-partition").getStatus());
    }

    @Test
    public void test_a_sole_slice_commit_after_a_reap_does_not_report_success() throws Exception {
        send(txId, save("reaped"), false);
        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));

        final var commit = send(txId, commit(), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), commit.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, findById("reaped").getStatus());
    }

    @Test
    public void test_a_continuation_that_finds_another_transactions_leftover_is_still_refused() throws Exception {
        send(otherTxId, save("leftover"), false);

        final var refused = send(txId, save("continued"), true);

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), refused.getErrorCode(),
                "the leftover proves this transaction's own earlier op never reached the live slice");
        assertNull(clientTracker.txSession(sessionId));
        assertEquals(OperationStatus.NOT_FOUND, findById("leftover").getStatus());
        assertEquals(OperationStatus.NOT_FOUND, findById("continued").getStatus());
    }
}
