package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestGlobals;

public class AntiEntropyBucketTest extends AntiEntropyTestBase {
    private long liveVersion() throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(e -> e.getValue().equals(SEEDED_ID)).findFirst().orElseThrow().getVersion();
    }

    @Test
    public void test_a_superseded_tombstone_is_left_out_of_the_summary() throws Exception {
        seed();
        final var withoutTombstone = service.buildDigest(TestGlobals.DB, TestGlobals.COLL).getSummary();
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, SEEDED_ID, liveVersion() - 1);

        final var digest = service.buildDigest(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(withoutTombstone, digest.getSummary(),
                "a tombstone older than the live row must not keep two converged nodes' summaries apart");
        assertTrue(digest.getDigest().stream().noneMatch(DigestEntry::isDeleted));
    }

    @Test
    public void test_a_large_collection_answers_bucket_summaries_then_only_the_asked_buckets() throws Exception {
        final var objects = new java.util.ArrayList<org.techhouse.ejson.elements.JsonObject>();
        for (var i = 0; i < 300; i++) {
            final var object = new org.techhouse.ejson.elements.JsonObject();
            object.addProperty("_id", "big" + i);
            objects.add(object);
        }
        final var bulk = new org.techhouse.ops.req.BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(objects);
        processor.processMessage(bulk);
        final var query = new AntiEntropyPayload(TestGlobals.DB, TestGlobals.COLL);
        query.setSummary("other");
        query.setBucketCount(DigestHash.MIN_BUCKETS);

        final var vector = service.buildDigest(query);
        query.setBuckets(List.of(3));
        final var asked = service.buildDigest(query);

        assertEquals(DigestHash.MIN_BUCKETS, vector.getBucketSummaries().size());
        assertNull(vector.getDigest(), "a collection above twice the bucket count ships its bucket vector");
        assertFalse(asked.getDigest().isEmpty());
        assertTrue(asked.getDigest().stream()
                .allMatch(entry -> DigestHash.bucket(DigestHash.idHash(entry.getId()), DigestHash.MIN_BUCKETS) == 3));
    }

    @Test
    public void test_a_converged_round_builds_no_maps() throws Exception {
        seed();
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var response = new ClusterMessage();
            response.setType(ClusterMessageType.DIGEST_ACK);
            response.setAntiEntropy(service.buildDigest(message.getAntiEntropy()));
            return response;
        });
        injectPeer(pool);
        try (var digests = org.mockito.Mockito.mockStatic(LocalDigest.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            service.reconcile(TestGlobals.DB, TestGlobals.COLL);

            digests.verify(() -> LocalDigest.entries(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()),
                    org.mockito.Mockito.never());
        }
    }

    @Test
    public void test_compaction_is_skipped_when_nothing_is_due() throws Exception {
        final var retention = Configuration.getInstance().getTombstoneRetentionMs();
        fs.tombstones().append(TestGlobals.DB, TestGlobals.COLL, "recent",
                HybridClock.pack(System.currentTimeMillis(), 0));
        final var file = new java.io.File(TestGlobals.PATH + "/" + TestGlobals.DB + "/" + TestGlobals.COLL + "/"
                + TestGlobals.COLL + "-tombstones.idx");
        final var before = java.nio.file.Files.getLastModifiedTime(file.toPath());
        final var inode = java.nio.file.Files
                .readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        fs.tombstones().compactIfDue(TestGlobals.DB, TestGlobals.COLL,
                HybridClock.pack(System.currentTimeMillis() - Math.max(retention, 60_000L), 0));

        assertEquals(
                inode, java.nio.file.Files
                        .readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class).fileKey(),
                "nothing was due, so nothing is rewritten");
        assertEquals(before, java.nio.file.Files.getLastModifiedTime(file.toPath()));
    }
}
