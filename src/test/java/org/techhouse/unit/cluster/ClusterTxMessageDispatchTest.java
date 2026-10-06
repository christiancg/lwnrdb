package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterTxMessageDispatchTest extends ClusterConnectionHandlerTestBase {

    private void seedPreparedSlice(String dtxId, String id) throws Exception {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "coordinator", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, obj));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));
    }

    private ClusterMessage txResolutionRequest(ClusterMessageType type, String dtxId) {
        final var message = envelope(type);
        message.setTxId(dtxId);
        message.setTxSessionId(UUID.randomUUID().toString());
        return message;
    }

    @Test
    public void test_commit_tx_with_no_live_session_gives_up_on_a_busy_collection_lock_instead_of_blocking_the_connection()
            throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        seedPreparedSlice(dtxId, "committx-busy");
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_ACK_TIMEOUT_MS);
        final var address = cluster.serverAddress();
        final var commitDone = new CountDownLatch(1);
        final var commitResponse = new AtomicReference<ClusterMessage>();
        final Thread commit;

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            commit = Thread.ofVirtual().start(() -> {
                try {
                    commitResponse.set(
                            pool.request(address, txResolutionRequest(ClusterMessageType.COMMIT_TX, dtxId), 10000L));
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    commitDone.countDown();
                }
            });
            assertFalse(commitDone.await(500, TimeUnit.MILLISECONDS), "the commit should still be waiting on the lock");

            final var ack = pool.request(address, envelope(ClusterMessageType.ADMIN_SNAPSHOT),
                    SHORT_ACK_TIMEOUT_MS + 5000L);

            assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, ack.getType(),
                    "a message queued behind the commit on the ordered lane must still be answered, not left"
                            + " waiting on the same lock forever");
            assertEquals(0L, commitDone.getCount(),
                    "the commit must have already given up on its own budget for the lane to reach the snapshot");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        commit.join();
        assertNotNull(commitResponse.get());
        assertEquals(ClusterMessageType.ERROR, commitResponse.get().getType(),
                "a busy collection lock must be reported as a failure, not resolved by an eventual retry");
    }

    @Test
    public void test_abort_tx_with_no_live_session_completes_promptly_even_while_the_collection_lock_is_held()
            throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        seedPreparedSlice(dtxId, "aborttx-busy");
        final var address = cluster.serverAddress();

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var started = System.currentTimeMillis();
            final var response = pool.request(address, txResolutionRequest(ClusterMessageType.ABORT_TX, dtxId), 5000L);
            final var elapsed = System.currentTimeMillis() - started;

            assertEquals(ClusterMessageType.ABORT_TX_ACK, response.getType());
            assertTrue(elapsed < 2000, "an abort takes no collection lock, so it must not wait on one");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        assertFalse(Tx2pcLog.isPrepared(dtxId), "the marker must be resolved once the abort completes");
    }

    @Test
    public void test_commit_tx_is_not_acked_while_the_durable_replay_is_incomplete() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("into-a-missing-collection"));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "coordinator", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, "missing-coll", obj));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:5000", List.of("127.0.0.1:5000"),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, "missing-coll")));

        final var response = pool.request(cluster.serverAddress(),
                txResolutionRequest(ClusterMessageType.COMMIT_TX, dtxId), 10000L);

        assertEquals(ClusterMessageType.ERROR, response.getType(),
                "a slice that did not apply must not be acknowledged, or the coordinator forgets the commit");
        assertTrue(response.getErrorMessage().contains(dtxId));
        assertTrue(Tx2pcLog.isPrepared(dtxId), "the slice stays in doubt for the coordinator to re-drive");
    }

    @Test
    public void test_commit_tx_with_no_live_session_still_commits_when_uncontended() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        seedPreparedSlice(dtxId, "committx-uncontended");
        final var address = cluster.serverAddress();

        final var response = pool.request(address, txResolutionRequest(ClusterMessageType.COMMIT_TX, dtxId), 5000L);

        assertEquals(ClusterMessageType.COMMIT_TX_ACK, response.getType());
        assertEquals(OperationStatus.OK, findById("committx-uncontended").getStatus());
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    private void occupySessionsSingleThread(String sessionId, CountDownLatch releaseWork) {
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var session = clientTracker.registerTxSession(sessionId, "test-user", null);
        session.submit(() -> releaseWork.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void test_handle_forward_tx_times_out_when_the_session_is_slow() throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_ACK_TIMEOUT_MS);
        final var sessionId = UUID.randomUUID().toString();
        final var releaseWork = new CountDownLatch(1);
        occupySessionsSingleThread(sessionId, releaseWork);
        try {
            final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
            message.setTxSessionId(sessionId);
            message.setTxId(UUID.randomUUID().toString());
            message.setForwardBody(ForwardBody.encode(rawSaveRequest()));

            final var started = System.currentTimeMillis();
            final var response = pool.request(cluster.serverAddress(), message, SHORT_ACK_TIMEOUT_MS + 5000L);
            final var elapsed = System.currentTimeMillis() - started;

            assertEquals(ClusterMessageType.ERROR, response.getType(),
                    "a session whose executor is still busy must not block the handler forever");
            assertTrue(elapsed < SHORT_ACK_TIMEOUT_MS + 3000L, "the handler must give up within its own ack budget");
        } finally {
            releaseWork.countDown();
            IocContainer.get(ClientTracker.class).removeTxSession(sessionId);
        }
    }

    @Test
    public void test_handle_prepare_tx_times_out_and_votes_no() throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_ACK_TIMEOUT_MS);
        final var sessionId = UUID.randomUUID().toString();
        final var releaseWork = new CountDownLatch(1);
        occupySessionsSingleThread(sessionId, releaseWork);
        try {
            final var message = envelope(ClusterMessageType.PREPARE_TX);
            message.setTxSessionId(sessionId);
            message.setTxParticipants(List.of("127.0.0.1:5000"));

            final var started = System.currentTimeMillis();
            final var response = pool.request(cluster.serverAddress(), message, SHORT_ACK_TIMEOUT_MS + 5000L);
            final var elapsed = System.currentTimeMillis() - started;

            assertEquals(ClusterMessageType.ERROR, response.getType(),
                    "a participant whose session cannot answer in time must vote no rather than block the lane");
            assertTrue(elapsed < SHORT_ACK_TIMEOUT_MS + 3000L, "the handler must give up within its own ack budget");
        } finally {
            releaseWork.countDown();
            IocContainer.get(ClientTracker.class).removeTxSession(sessionId);
        }
    }

    @Test
    public void test_resolve_tx_times_out_when_the_session_is_slow() throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", SHORT_ACK_TIMEOUT_MS);
        final var sessionId = UUID.randomUUID().toString();
        final var releaseWork = new CountDownLatch(1);
        occupySessionsSingleThread(sessionId, releaseWork);
        try {
            final var message = txResolutionRequest(ClusterMessageType.COMMIT_TX, UUID.randomUUID().toString());
            message.setTxSessionId(sessionId);

            final var started = System.currentTimeMillis();
            final var response = pool.request(cluster.serverAddress(), message, SHORT_ACK_TIMEOUT_MS + 5000L);
            final var elapsed = System.currentTimeMillis() - started;

            assertEquals(ClusterMessageType.ERROR, response.getType(),
                    "a session whose executor cannot resolve the transaction in time must not block the lane");
            assertTrue(elapsed < SHORT_ACK_TIMEOUT_MS + 3000L, "the handler must give up within its own ack budget");
        } finally {
            releaseWork.countDown();
            IocContainer.get(ClientTracker.class).removeTxSession(sessionId);
        }
    }

    private String rawSaveRequest() {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"forwardtx-slow\",\"v\":1}}";
    }

    @Test
    public void test_tx_status_is_answered_while_a_slow_session_blocks_the_ordered_lane() throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", ACK_TIMEOUT_MS);
        final var sessionId = UUID.randomUUID().toString();
        final var releaseWork = new CountDownLatch(1);
        occupySessionsSingleThread(sessionId, releaseWork);
        final var blockedDone = new CountDownLatch(1);
        final Thread blocked;
        try {
            final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
            message.setTxSessionId(sessionId);
            message.setTxId(UUID.randomUUID().toString());
            message.setForwardBody(ForwardBody.encode(rawSaveRequest()));

            blocked = Thread.ofVirtual().start(() -> {
                try {
                    pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS + 5000L);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    blockedDone.countDown();
                }
            });
            assertFalse(blockedDone.await(500, TimeUnit.MILLISECONDS),
                    "the forwarded tx request should still be waiting on the busy session");

            final var status = pool.request(cluster.serverAddress(),
                    txResolutionRequest(ClusterMessageType.TX_STATUS, UUID.randomUUID().toString()), 5000L);

            assertNotNull(status);
            assertEquals(ClusterMessageType.TX_STATUS_ACK, status.getType());
            assertEquals(1L, blockedDone.getCount(),
                    "the forwarded tx request must still be blocked, or this proved nothing");
        } finally {
            releaseWork.countDown();
            IocContainer.get(ClientTracker.class).removeTxSession(sessionId);
        }
        assertTrue(blockedDone.await(30, TimeUnit.SECONDS));
        blocked.join();
    }

    @Test
    public void test_list_tx_is_answered_while_a_slow_session_blocks_the_ordered_lane() throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", ACK_TIMEOUT_MS);
        final var sessionId = UUID.randomUUID().toString();
        final var releaseWork = new CountDownLatch(1);
        occupySessionsSingleThread(sessionId, releaseWork);
        final var blockedDone = new CountDownLatch(1);
        final Thread blocked;
        try {
            final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
            message.setTxSessionId(sessionId);
            message.setTxId(UUID.randomUUID().toString());
            message.setForwardBody(ForwardBody.encode(rawSaveRequest()));

            blocked = Thread.ofVirtual().start(() -> {
                try {
                    pool.request(cluster.serverAddress(), message, ACK_TIMEOUT_MS + 5000L);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    blockedDone.countDown();
                }
            });
            assertFalse(blockedDone.await(500, TimeUnit.MILLISECONDS),
                    "the forwarded tx request should still be waiting on the busy session");

            final var list = pool.request(cluster.serverAddress(), envelope(ClusterMessageType.LIST_TX), 5000L);

            assertNotNull(list);
            assertEquals(ClusterMessageType.LIST_TX_ACK, list.getType());
            assertEquals(1L, blockedDone.getCount(),
                    "the forwarded tx request must still be blocked, or this proved nothing");
        } finally {
            releaseWork.countDown();
            IocContainer.get(ClientTracker.class).removeTxSession(sessionId);
        }
        assertTrue(blockedDone.await(30, TimeUnit.SECONDS));
        blocked.join();
    }

    @Test
    public void test_only_gossip_forward_request_digest_pull_tx_status_and_list_tx_may_overtake_the_ordered_lane()
            throws Exception {
        final var mayOvertake = org.techhouse.cluster.ClusterConnectionHandler.class.getDeclaredMethod("mayOvertake",
                ClusterMessageType.class);
        mayOvertake.setAccessible(true);
        final var expectedExempt = java.util.EnumSet.of(ClusterMessageType.GOSSIP, ClusterMessageType.FORWARD_REQUEST,
                ClusterMessageType.DIGEST, ClusterMessageType.PULL, ClusterMessageType.TX_STATUS,
                ClusterMessageType.LIST_TX);

        for (final var type : ClusterMessageType.values()) {
            final var exempt = (boolean) mayOvertake.invoke(null, type);
            assertEquals(expectedExempt.contains(type), exempt, () -> type + " must "
                    + (expectedExempt.contains(type) ? "" : "not ") + "be exempted from the ordered lane");
        }
    }
}
