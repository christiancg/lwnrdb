package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

// A peer sends every kind of request over one connection, so what a slow one does to the rest of that
// connection's traffic is the behaviour under test.
public class ClusterConnectionHandlerTest {
    private static final long ACK_TIMEOUT_MS = 30000L;
    private static final List<String> QUEUED_IDS = List.of("drain1", "drain2");
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final PeerConnectionPool pool = new PeerConnectionPool();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final EJson eJson = IocContainer.get(EJson.class);

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
    // peer's own slow write and timed out with it. A peer that stops gossiping stops counting towards
    // aliveCount(), which is what write quorum is measured from - so one slow write cost the quorum.
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

    // The peer going away must not cancel what it already sent. The handler drains its queue before it
    // lets the socket go, and drains it while the socket is still open, so those answers are still
    // delivered - a shutdownNow() in place of that drain would silently drop committed work.
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

    // Ordering still holds for everything else. Only gossip left the serial path, because replication
    // correctness rests on a peer's writes being applied in the order it sent them.
    @Test
    public void test_requests_other_than_gossip_are_still_answered_in_order() throws Exception {
        final var address = cluster.serverAddress();

        final var save = pool.request(address, blockedSaveRequest(), ACK_TIMEOUT_MS);
        final var snapshot = pool.request(address, envelope(ClusterMessageType.ADMIN_SNAPSHOT), ACK_TIMEOUT_MS);

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, save.getType());
        assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, snapshot.getType());
    }
}
