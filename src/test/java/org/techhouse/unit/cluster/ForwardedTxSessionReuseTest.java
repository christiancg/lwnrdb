package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;

public class ForwardedTxSessionReuseTest extends ClusterConnectionHandlerTestBase {
    private static final String COORDINATOR = "127.0.0.1:5000";

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final String sessionId = UUID.randomUUID().toString();
    private final String tx1 = UUID.randomUUID().toString();
    private final String tx2 = UUID.randomUUID().toString();

    @AfterEach
    public void removeSession() throws Exception {
        Tx2pcLog.deleteParticipantMarker(tx1);
        TxCommitLog.clearLocalCommit(tx1);
        clientTracker.removeTxSession(sessionId);
    }

    private ClusterMessage forwarded(String txId, String body, String actingUser) {
        final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
        message.setActingUser(actingUser);
        message.setTxSessionId(sessionId);
        message.setTxId(txId);
        message.setForwardBody(ForwardBody.encode(body));
        return message;
    }

    private ClusterMessage control(ClusterMessageType type, String txId) {
        final var message = envelope(type);
        message.setTxSessionId(sessionId);
        message.setTxId(txId);
        message.setTxParticipants(List.of(COORDINATOR));
        return message;
    }

    private static String save(String id) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
    }

    private static String control(String type) {
        return "{\"type\":\"" + type + "\"}";
    }

    private OperationResponse send(String txId, String body) throws Exception {
        return send(txId, body, "admin");
    }

    private OperationResponse send(String txId, String body, String actingUser) throws Exception {
        final var reply = pool.request(cluster.serverAddress(), forwarded(txId, body, actingUser), ACK_TIMEOUT_MS);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, reply.getType(), reply.getErrorMessage());
        return eJson.fromJson(ForwardBody.decode(reply.getForwardBody()), OperationResponse.class);
    }

    private ClusterMessage sendControl(ClusterMessageType type, String txId) throws Exception {
        return pool.request(cluster.serverAddress(), control(type, txId), ACK_TIMEOUT_MS);
    }

    private Transaction activeTransaction() {
        final var session = clientTracker.txSession(sessionId);
        assertNotNull(session);
        final var transaction = clientTracker.getActiveTransaction(session.clientId());
        assertNotNull(transaction);
        return transaction;
    }

    private void prepareTx1() throws Exception {
        Tx2pcLog.recordParticipantPrepared(tx1, COORDINATOR, List.of(COORDINATOR),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
    }

    private void releaseFencedTx1() throws Exception {
        Tx2pcLog.deleteParticipantMarker(tx1);
        TxCommitLog.clearLocalCommit(tx1);
        assertEquals(OperationStatus.OK, send(tx1, control("ROLLBACK_TRANSACTION")).getStatus());
    }

    @Test
    public void test_a_new_transaction_on_the_same_session_does_not_commit_the_abandoned_ones_writes()
            throws Exception {
        assertEquals(OperationStatus.OK, send(tx1, save("abandoned")).getStatus());
        assertEquals(OperationStatus.OK, send(tx2, save("kept")).getStatus());

        assertEquals(OperationStatus.OK, send(tx2, control("COMMIT_TRANSACTION")).getStatus());

        assertEquals(OperationStatus.OK, findById("kept").getStatus());
        assertEquals(OperationStatus.NOT_FOUND, findById("abandoned").getStatus(),
                "a write of a transaction the edge discarded must not ride on the next transaction's commit");
    }

    private String sessionUser() {
        return clientTracker.getAuthenticatedUsername(clientTracker.txSession(sessionId).clientId());
    }

    @Test
    public void test_a_new_transaction_on_a_leftover_session_runs_as_its_own_acting_user() throws Exception {
        assertEquals(OperationStatus.OK, send(tx1, save("abandoned"), "admin").getStatus());
        assertEquals("admin", sessionUser());

        assertEquals(OperationStatus.OK, send(tx2, save("kept"), "editor").getStatus());

        assertEquals("editor", sessionUser(),
                "the next transaction's hooks and triggers must not run as the user who created the session");
        send(tx2, control("ROLLBACK_TRANSACTION"), "editor");
    }

    @Test
    public void test_a_continuation_keeps_the_user_its_transaction_started_with() throws Exception {
        send(tx1, save("first"), "editor");

        send(tx1, save("second"), "admin");

        assertEquals("editor", sessionUser(), "only a forward that starts a transaction binds the session user");
        send(tx1, control("ROLLBACK_TRANSACTION"));
    }

    @Test
    public void test_the_abandoned_transaction_is_rolled_back_when_the_next_one_arrives() throws Exception {
        send(tx1, save("abandoned"));
        final var abandonedOps = List.copyOf(activeTransaction().getBufferedOpIds());
        assertFalse(abandonedOps.isEmpty());

        send(tx2, save("kept"));

        assertEquals(tx2, activeTransaction().getTransactionId().toString());
        assertTrue(AdminOperationHelper.readTransactionOps(abandonedOps).isEmpty(),
                "the abandoned transaction's buffered ops must be discarded with it");
        send(tx2, control("ROLLBACK_TRANSACTION"));
    }

    @Test
    public void test_a_prepared_previous_transaction_refuses_the_next_ones_op_with_409_12() throws Exception {
        send(tx1, save("prepared"));
        prepareTx1();

        final var refused = send(tx2, save("refused"));

        assertEquals(ErrorCode.TRANSACTION_PREVIOUS_UNRESOLVED.getCode(), refused.getErrorCode());
        assertEquals(tx1, activeTransaction().getTransactionId().toString());
        assertTrue(Tx2pcLog.isPrepared(tx1), "the prepared slice belongs to recovery and must be left intact");
        releaseFencedTx1();
        assertEquals(OperationStatus.NOT_FOUND, findById("refused").getStatus());
    }

    @Test
    public void test_a_locally_committed_previous_transaction_refuses_the_next_ones_op_with_409_12() throws Exception {
        send(tx1, save("committing"));
        TxCommitLog.recordLocalCommit(tx1, activeTransaction().getBufferedOpIds(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

        final var refused = send(tx2, save("refused"));

        assertEquals(ErrorCode.TRANSACTION_PREVIOUS_UNRESOLVED.getCode(), refused.getErrorCode());
        assertEquals(tx1, activeTransaction().getTransactionId().toString());
        releaseFencedTx1();
    }

    @Test
    public void test_a_refused_commit_on_a_fenced_session_keeps_the_session_registered() throws Exception {
        send(tx1, save("prepared"));
        prepareTx1();

        final var refused = send(tx2, control("COMMIT_TRANSACTION"));

        assertEquals(ErrorCode.TRANSACTION_PREVIOUS_UNRESOLVED.getCode(), refused.getErrorCode());
        assertNotNull(clientTracker.txSession(sessionId),
                "the session is the only handle on the fenced transaction's locks and must survive the refusal");
        releaseFencedTx1();
    }

    @Test
    public void test_a_rollback_under_the_next_id_retires_the_abandoned_one_and_frees_the_session() throws Exception {
        send(tx1, save("abandoned"));

        final var rollback = send(tx2, control("ROLLBACK_TRANSACTION"));

        assertEquals(ErrorCode.NO_ACTIVE_TRANSACTION.getCode(), rollback.getErrorCode());
        assertNull(clientTracker.txSession(sessionId));
        assertEquals(OperationStatus.NOT_FOUND, findById("abandoned").getStatus());
    }

    @Test
    public void test_prepare_for_another_transaction_votes_no_and_records_no_marker() throws Exception {
        send(tx1, save("live"));

        final var vote = sendControl(ClusterMessageType.PREPARE_TX, tx2);

        assertEquals(ClusterMessageType.ERROR, vote.getType());
        assertFalse(Tx2pcLog.isPrepared(tx1));
        assertFalse(Tx2pcLog.isPrepared(tx2));
        send(tx1, control("ROLLBACK_TRANSACTION"));
    }

    @Test
    public void test_commit_tx_for_another_transaction_leaves_the_live_one_untouched() throws Exception {
        send(tx1, save("live"));

        assertEquals(ClusterMessageType.COMMIT_TX_ACK, sendControl(ClusterMessageType.COMMIT_TX, tx2).getType());

        assertEquals(tx1, activeTransaction().getTransactionId().toString());
        assertEquals(1, activeTransaction().getBufferedOpIds().size());
        assertEquals(OperationStatus.OK, send(tx1, control("COMMIT_TRANSACTION")).getStatus());
        assertEquals(OperationStatus.OK, findById("live").getStatus());
    }

    @Test
    public void test_abort_tx_for_another_transaction_does_not_roll_back_the_live_one() throws Exception {
        send(tx1, save("live"));

        assertEquals(ClusterMessageType.ABORT_TX_ACK, sendControl(ClusterMessageType.ABORT_TX, tx2).getType());

        assertEquals(tx1, activeTransaction().getTransactionId().toString());
        assertEquals(OperationStatus.OK, send(tx1, control("COMMIT_TRANSACTION")).getStatus());
        assertEquals(OperationStatus.OK, findById("live").getStatus());
    }

    @Test
    public void test_matching_ids_keep_the_existing_behaviour() throws Exception {
        send(tx1, save("first"));
        send(tx1, save("second"));

        assertEquals(OperationStatus.OK, send(tx1, control("COMMIT_TRANSACTION")).getStatus());

        assertEquals(OperationStatus.OK, findById("first").getStatus());
        assertEquals(OperationStatus.OK, findById("second").getStatus());
        assertNull(clientTracker.txSession(sessionId));
    }

    @Test
    public void test_a_forward_without_a_tx_id_is_not_treated_as_a_mismatch() throws Exception {
        send(tx1, save("first"));
        send(null, save("second"));

        assertEquals(tx1, activeTransaction().getTransactionId().toString());
        assertEquals(OperationStatus.OK, send(tx1, control("COMMIT_TRANSACTION")).getStatus());
        assertEquals(OperationStatus.OK, findById("first").getStatus());
        assertEquals(OperationStatus.OK, findById("second").getStatus());
    }
}
