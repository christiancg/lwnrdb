package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.test.TestGlobals;

public class AntiEntropyIncarnationApplyTest extends AntiEntropyTestBase {
    private static final String COLL = "aeRecreated";
    private static final long PEER_VERSION = 1_000L;

    private void create() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLL)).getStatus());
    }

    private void dropAndRecreate() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, COLL)).getStatus());
        create();
    }

    private static JsonObject deadDocument() {
        final var document = new JsonObject();
        document.addProperty("_id", "dead");
        return document;
    }

    private static ClusterMessage answer(ClusterMessage request, ClusterMessageType type, AntiEntropyPayload payload) {
        payload.setIncarnation(request.getAntiEntropy().getIncarnation());
        final var response = new ClusterMessage();
        response.setType(type);
        response.setAntiEntropy(payload);
        return response;
    }

    private PeerConnectionPool peerThatAnswers(List<ClusterMessage> requests, Runnable beforePullAnswer,
            Runnable beforeDigestAnswer, DigestEntry peerEntry) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage request = invocation.getArgument(1);
            requests.add(request);
            final var payload = new AntiEntropyPayload(TestGlobals.DB, COLL);
            if (request.getType() == ClusterMessageType.DIGEST) {
                beforeDigestAnswer.run();
                payload.setDigest(List.of(peerEntry));
                return answer(request, ClusterMessageType.DIGEST_ACK, payload);
            }
            beforePullAnswer.run();
            payload.setDocuments(List.of(deadDocument()));
            payload.setVersions(List.of(Long.toString(PEER_VERSION)));
            return answer(request, ClusterMessageType.PULL_ACK, payload);
        });
        return pool;
    }

    private OperationStatus findDead() {
        final var request = new FindByIdRequest(TestGlobals.DB, COLL);
        request.set_id("dead");
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_a_pull_answered_across_a_drop_and_recreate_seeds_nothing() throws Exception {
        create();
        final var before = CollectionIncarnation.current(TestGlobals.DB, COLL);
        injectPeer(peerThatAnswers(new ArrayList<>(), this::dropAndRecreate, () -> {
        }, new DigestEntry("dead", PEER_VERSION, false)));

        service.reconcile(TestGlobals.DB, COLL);

        assertNotEquals(before, CollectionIncarnation.current(TestGlobals.DB, COLL));
        assertEquals(OperationStatus.NOT_FOUND, findDead(),
                "a pre-drop document pulled into the new incarnation is replicated cluster-wide from there");
    }

    @Test
    public void test_a_delete_decided_across_a_drop_and_recreate_writes_no_tombstone() throws Exception {
        create();
        injectPeer(peerThatAnswers(new ArrayList<>(), () -> {
        }, this::dropAndRecreate, new DigestEntry("dead", PEER_VERSION, true)));

        service.reconcile(TestGlobals.DB, COLL);

        assertFalse(fs.tombstones().read(TestGlobals.DB, COLL).containsKey("dead"));
    }

    @Test
    public void test_a_reconcile_round_sends_the_incarnation_it_snapshotted() throws Exception {
        create();
        final var incarnation = CollectionIncarnation.current(TestGlobals.DB, COLL);
        final var requests = new ArrayList<ClusterMessage>();
        injectPeer(peerThatAnswers(requests, () -> {
        }, () -> {
        }, new DigestEntry("dead", PEER_VERSION, false)));

        service.reconcile(TestGlobals.DB, COLL);

        assertEquals(2, requests.size());
        for (final var request : requests) {
            assertEquals(incarnation, request.getAntiEntropy().incarnationValue());
        }
        assertEquals(OperationStatus.OK, findDead());
    }
}
