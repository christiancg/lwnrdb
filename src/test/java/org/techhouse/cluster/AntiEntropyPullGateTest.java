package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;

public class AntiEntropyPullGateTest extends AntiEntropyTestBase {
    private static final String ID = SEEDED_ID;
    private static final String HIGHER_NODE_ID = "zeta";
    private static final long PEER_VERSION = 5L;
    private static final int BEYOND_ONE_BATCH = AntiEntropyService.PULL_BATCH_SIZE + 1;

    private final AtomicInteger pulls = new AtomicInteger();

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("v", 9);
        return object;
    }

    private PkIndexEntry localEntry() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(entry -> entry.getValue().equals(ID)).findFirst().orElseThrow();
    }

    private int localCount() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size();
    }

    private PeerConnectionPool peer(List<DigestEntry> digest, int failingPull) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            if (message.getType() == ClusterMessageType.DIGEST) {
                response.setType(ClusterMessageType.DIGEST_ACK);
                payload.setDigest(digest);
            } else if (pulls.incrementAndGet() == failingPull) {
                response.setType(ClusterMessageType.ERROR);
                response.setErrorMessage("injected pull failure");
                return response;
            } else {
                response.setType(ClusterMessageType.PULL_ACK);
                final var documents = new ArrayList<JsonObject>();
                final var versions = new ArrayList<String>();
                for (final var id : message.getAntiEntropy().getIds()) {
                    documents.add(document(id));
                    versions.add(Long.toString(PEER_VERSION));
                }
                payload.setDocuments(documents);
                payload.setVersions(versions);
            }
            response.setAntiEntropy(payload);
            return response;
        });
        return pool;
    }

    private static List<DigestEntry> remoteOnlyDigestBeyondOneBatch() {
        final var digest = new ArrayList<DigestEntry>();
        for (var i = 0; i < BEYOND_ONE_BATCH; i++) {
            digest.add(new DigestEntry("remote-" + i, PEER_VERSION, false, HIGHER_NODE_ID, 20L));
        }
        return digest;
    }

    @Test
    public void test_an_equal_version_equal_length_copy_is_not_pulled() throws Exception {
        seed();
        final var local = localEntry();
        injectPeer(peer(List.of(new DigestEntry(ID, local.getVersion(), false, HIGHER_NODE_ID, local.getLength())), 0),
                node(HIGHER_NODE_ID, 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(0, pulls.get(), "an identical copy at the same version must not be re-pulled and rewritten");
    }

    @Test
    public void test_an_equal_version_different_length_copy_is_still_pulled_from_the_higher_node_id() throws Exception {
        seed();
        final var local = localEntry();
        injectPeer(
                peer(List.of(new DigestEntry(ID, local.getVersion(), false, HIGHER_NODE_ID, local.getLength() + 1)), 0),
                node(HIGHER_NODE_ID, 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(1, pulls.get(), "two different copies at one version still converge on the higher node id");
    }

    @Test
    public void test_pulls_are_sent_in_batches() throws Exception {
        injectPeer(peer(remoteOnlyDigestBeyondOneBatch(), 0), node(HIGHER_NODE_ID, 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, pulls.get(), "one unbounded PULL can outlast the ack timeout on a large collection");
        assertEquals(BEYOND_ONE_BATCH, localCount(), "every batch must be applied");
    }

    @Test
    public void test_a_failed_batch_does_not_stop_the_others() throws Exception {
        injectPeer(peer(remoteOnlyDigestBeyondOneBatch(), 1), node(HIGHER_NODE_ID, 5001));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(2, pulls.get());
        assertEquals(1, localCount(), "the batch after the failed one must still be pulled and applied");
    }

    private void save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.addProperty("payload", id + "-value");
        request.setObject(object);
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private PkIndexEntry byId(String id) throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(e -> e.getValue().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void test_pull_answers_each_requested_id_with_its_own_document() throws Exception {
        save("a");
        save("b");

        final var pull = service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a", "b"));

        assertEquals(2, pull.getDocuments().size());
        for (final var document : pull.getDocuments()) {
            final var id = document.get(Globals.PK_FIELD).asJsonString().getValue();
            assertEquals(id + "-value", document.get("payload").asJsonString().getValue(),
                    "a pulled document must be the one its id names");
        }
    }

    @Test
    public void test_pull_never_answers_with_a_different_document() throws Exception {
        save("a");
        save("b");
        final var target = byId("a");
        final var other = byId("b");
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "a");
        target.setPosition(other.getPosition());
        target.setLength(other.getLength());
        target.setPage(other.getPage());

        assertThrows(Exception.class, () -> service.buildPull(TestGlobals.DB, TestGlobals.COLL, List.of("a")),
                "a stale offset must fail the pull rather than ship another document's body to a peer");
    }
}
