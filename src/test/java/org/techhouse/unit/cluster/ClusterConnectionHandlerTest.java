package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterConnectionHandlerTest {
    private static final long ACK_TIMEOUT_MS = 30000L;
    private static final long SHORT_ACK_TIMEOUT_MS = 3000L;
    private static final List<String> QUEUED_IDS = List.of("drain1", "drain2");
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final PeerConnectionPool pool = new PeerConnectionPool();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final Configuration config = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start(true, ACK_TIMEOUT_MS);
        cluster.configureMembership(1,
                new NodeInfo("self", "127.0.0.1", cluster.serverPort(), NodeState.ALIVE, 1L, 1L));
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private ClusterMessage envelope(ClusterMessageType type) {
        final var message = new ClusterMessage();
        message.setType(type);
        message.setSecret(ClusterTestHarness.SECRET);
        return message;
    }

    // A SAVE into a collection whose write lock the test holds: it reaches the handler and then blocks,
    // which is the shape of the slow request that used to hold up everything queued behind it.
    private ClusterMessage blockedSaveRequest() {
        return saveRequest("blocked");
    }

    private ClusterMessage saveRequest(String id) {
        final var raw = "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
        final var message = envelope(ClusterMessageType.FORWARD_REQUEST);
        message.setForwardBody(ForwardBody.encode(raw));
        return message;
    }

    // The regression: the handler answered one request at a time, so a peer's gossip queued behind that
    // peer's own slow write and timed out with it - costing the cluster its write quorum.
    @Test
    public void test_gossip_is_answered_while_another_request_on_the_same_connection_is_blocked() throws Exception {
        final var address = cluster.serverAddress();
        final var blockedDone = new CountDownLatch(1);
        final Thread blocked;

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            blocked = Thread.ofVirtual().start(() -> {
                try {
                    pool.request(address, blockedSaveRequest(), ACK_TIMEOUT_MS);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    blockedDone.countDown();
                }
            });
            // It has to be inside the handler and waiting on the lock before the gossip goes out, or there
            // would be nothing for the gossip to be queued behind.
            assertFalse(blockedDone.await(1, TimeUnit.SECONDS), "the forwarded save should still be blocked");

            final var ack = pool.request(address, envelope(ClusterMessageType.GOSSIP), 5000L);

            assertNotNull(ack);
            assertEquals(ClusterMessageType.GOSSIP_ACK, ack.getType());
            assertEquals(1L, blockedDone.getCount(), "the save must still be blocked, or this proved nothing");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertTrue(blockedDone.await(30, TimeUnit.SECONDS), "the save must finish once the lock is released");
        blocked.join();
    }

    // The handler drains its queue while the socket is still open; a shutdownNow() in place of that
    // drain would silently drop committed work.
    @Test
    public void test_requests_already_queued_are_applied_after_the_peer_stops_sending() throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try (var raw = new Socket("127.0.0.1", cluster.serverPort())) {
            final var out = new BufferedWriter(new OutputStreamWriter(raw.getOutputStream(), StandardCharsets.UTF_8));
            for (final var id : QUEUED_IDS) {
                out.write(eJson.toJson(saveRequest(id)));
                out.newLine();
            }
            out.flush();
            // Half close, so the peer is done sending: that ends the read loop exactly as a full disconnect
            // would, while leaving this side able to read whatever the drain still answers.
            raw.shutdownOutput();
            final var in = new BufferedReader(new InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
            // One pause, not a poll: it only has to outlast reading two lines and an EOF off a loopback
            // socket, so that both requests are queued and the read loop has ended before the lock frees.
            Thread.sleep(500);

            locks.release(TestGlobals.DB, TestGlobals.COLL);

            for (final var id : QUEUED_IDS) {
                assertNotNull(in.readLine(), () -> "no answer for " + id + ": the drain dropped it");
            }
        }

        for (final var id : QUEUED_IDS) {
            assertEquals(OperationStatus.OK, findById(id).getStatus(), () -> id + " was answered but not applied");
        }
    }

    private OperationResponse findById(String id) {
        final var json = "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}";
        return processor.processMessage(RequestParser.parseRequest(json));
    }

    // Only gossip left the serial path: a peer's writes must still apply in the order it sent them.
    @Test
    public void test_requests_other_than_gossip_are_still_answered_in_order() throws Exception {
        final var address = cluster.serverAddress();

        final var save = pool.request(address, blockedSaveRequest(), ACK_TIMEOUT_MS);
        final var snapshot = pool.request(address, envelope(ClusterMessageType.ADMIN_SNAPSHOT), ACK_TIMEOUT_MS);

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, save.getType());
        assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, snapshot.getType());
    }

    @Test
    public void test_a_digest_blocked_on_a_collection_lock_does_not_hold_up_the_connection() throws Exception {
        markAdminSyncCompleted(true);
        final var address = cluster.serverAddress();
        final var digestDone = new CountDownLatch(1);
        final Thread digest;

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            digest = Thread.ofVirtual().start(() -> {
                try {
                    pool.request(address, antiEntropyRequest(ClusterMessageType.DIGEST, 0L), ACK_TIMEOUT_MS);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    digestDone.countDown();
                }
            });
            assertFalse(digestDone.await(1, TimeUnit.SECONDS), "the digest should still be waiting on the lock");

            final var ack = pool.request(address, envelope(ClusterMessageType.ADMIN_SNAPSHOT), 5000L);

            assertNotNull(ack);
            assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, ack.getType());
            assertEquals(1L, digestDone.getCount(), "the digest must still be blocked, or this proved nothing");
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
            markAdminSyncCompleted(false);
        }

        assertTrue(digestDone.await(30, TimeUnit.SECONDS), "the digest must finish once the lock is released");
        digest.join();
    }

    private void markAdminSyncCompleted(boolean completed) throws Exception {
        TestUtils.getPrivateField(IocContainer.get(AdminAntiEntropyService.class), "adminSyncCompleted",
                AtomicBoolean.class).set(completed);
    }

    private ClusterMessage antiEntropyRequest(ClusterMessageType type, long incarnation) {
        final var message = envelope(type);
        final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
        payload.setIds(List.of("a"));
        payload.setIncarnationValue(incarnation);
        message.setAntiEntropy(payload);
        return message;
    }

    @Test
    public void test_digest_is_refused_before_the_first_admin_sync() throws Exception {
        markAdminSyncCompleted(false);

        final var response = pool.request(cluster.serverAddress(), antiEntropyRequest(ClusterMessageType.DIGEST, 0L),
                ACK_TIMEOUT_MS);

        assertEquals(ClusterMessageType.ERROR, response.getType(), "a node that has not conformed to the admin"
                + " snapshot yet must not describe its documents, or a peer pulls a dropped incarnation back");
    }

    @Test
    public void test_pull_is_refused_before_the_first_admin_sync() throws Exception {
        markAdminSyncCompleted(false);

        final var response = pool.request(cluster.serverAddress(), antiEntropyRequest(ClusterMessageType.PULL, 0L),
                ACK_TIMEOUT_MS);

        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    @Test
    public void test_digest_carries_the_queried_incarnation_into_the_service() throws Exception {
        markAdminSyncCompleted(true);
        try {
            IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL)
                    .setIncarnation(100L);

            final var response = pool.request(cluster.serverAddress(),
                    antiEntropyRequest(ClusterMessageType.DIGEST, 200L), ACK_TIMEOUT_MS);

            assertEquals(ClusterMessageType.DIGEST_ACK, response.getType());
            assertTrue(response.getAntiEntropy().isStaleIncarnation(),
                    "the incarnation on the query must reach the service, or the mismatch goes undetected");
        } finally {
            markAdminSyncCompleted(false);
        }
    }

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
    public void test_commit_tx_with_no_live_session_still_commits_when_uncontended() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        seedPreparedSlice(dtxId, "committx-uncontended");
        final var address = cluster.serverAddress();

        final var response = pool.request(address, txResolutionRequest(ClusterMessageType.COMMIT_TX, dtxId), 5000L);

        assertEquals(ClusterMessageType.COMMIT_TX_ACK, response.getType());
        assertEquals(OperationStatus.OK, findById("committx-uncontended").getStatus());
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }
}
