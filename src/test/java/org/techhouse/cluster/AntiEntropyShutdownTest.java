package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestGlobals;

public class AntiEntropyShutdownTest extends AntiEntropyTestBase {
    private static final String ID = SEEDED_ID;

    @AfterEach
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void clearInterrupt() {
        Thread.interrupted();
    }

    private PeerConnectionPool peerInterruptingOnDigest(DigestEntry entry) throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            final var payload = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
            if (message.getType() == ClusterMessageType.DIGEST) {
                Thread.currentThread().interrupt();
                response.setType(ClusterMessageType.DIGEST_ACK);
                payload.setDigest(List.of(entry));
            } else {
                final var doc = new JsonObject();
                doc.addProperty("_id", ID);
                doc.addProperty("v", 9);
                response.setType(ClusterMessageType.PULL_ACK);
                payload.setDocuments(List.of(doc));
                payload.setVersions(List.of(Long.toString(entry.versionValue())));
            }
            response.setAntiEntropy(payload);
            return response;
        });
        injectPeer(pool);
        return pool;
    }

    @Test
    public void test_a_sweep_interrupted_mid_digest_pulls_nothing() throws Exception {
        final var pool = peerInterruptingOnDigest(new DigestEntry(ID, 1000L, false));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);
        final var stillInterrupted = Thread.interrupted();

        assertTrue(stillInterrupted, "the shutdown interrupt must survive the digest round");
        assertFalse(hasLive(ID), "an interrupted sweep must not apply a pull after the shutdown drained the queue");
        verify(pool, times(1)).request(any(), any(), anyLong());
    }

    @Test
    public void test_a_sweep_interrupted_mid_digest_deletes_nothing() throws Exception {
        seed();
        peerInterruptingOnDigest(
                new DigestEntry(ID, HybridClock.pack(System.currentTimeMillis() + 1_000_000L, 0), true));

        service.reconcile(TestGlobals.DB, TestGlobals.COLL);
        final var stillInterrupted = Thread.interrupted();

        assertTrue(stillInterrupted, "the shutdown interrupt must survive the digest round");
        assertTrue(hasLive(ID), "an interrupted sweep must not apply a delete after the shutdown drained the queue");
    }

    @Test
    public void test_an_interrupted_sweep_visits_no_collection() throws Exception {
        final var pool = peerInterruptingOnDigest(new DigestEntry(ID, 1000L, false));
        final var sweep = AntiEntropyService.class.getDeclaredMethod("reconcileAllCollections");
        sweep.setAccessible(true);
        Thread.currentThread().interrupt();

        sweep.invoke(service);
        final var stillInterrupted = Thread.interrupted();

        assertTrue(stillInterrupted);
        verify(pool, never()).request(any(), any(), anyLong());
    }
}
