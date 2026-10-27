package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.techhouse.cache.PkIndexList;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.TombstoneView;

final class LocalDigest {
    private final int bucketCount;
    private final long[] sums;
    private final long[] counts;
    private long total;
    private long sum;

    private LocalDigest(int bucketCount) {
        this.bucketCount = bucketCount;
        this.sums = new long[bucketCount];
        this.counts = new long[bucketCount];
    }

    static LocalDigest of(List<PkIndexEntry> live, TombstoneView tombstones, int bucketCount) {
        final var digest = new LocalDigest(bucketCount);
        for (final var row : live) {
            digest.add(row.getValue(), row.getVersion(), row.getLength(), false);
        }
        tombstones.forEach((id, version) -> {
            if (outlives(live, id, version)) {
                digest.add(id, version, 0L, true);
            }
        });
        return digest;
    }

    static LocalDigest ofEntries(List<DigestEntry> entries, int bucketCount) {
        final var digest = new LocalDigest(bucketCount);
        for (final var entry : entries) {
            digest.add(entry.getId(), entry.versionValue(), entry.isDeleted() ? 0L : entry.getLength(),
                    entry.isDeleted());
        }
        return digest;
    }

    static List<DigestEntry> entries(List<PkIndexEntry> live, TombstoneView tombstones, int bucketCount,
            Set<Integer> buckets) {
        final var entries = new ArrayList<DigestEntry>();
        for (final var row : live) {
            if (inBuckets(row.getValue(), bucketCount, buckets)) {
                entries.add(new DigestEntry(row.getValue(), row.getVersion(), false, row.getLength()));
            }
        }
        tombstones.forEach((id, version) -> {
            if (inBuckets(id, bucketCount, buckets) && outlives(live, id, version)) {
                entries.add(new DigestEntry(id, version, true));
            }
        });
        return entries;
    }

    static boolean outlives(List<PkIndexEntry> live, String id, long tombstoneVersion) {
        final var row = PkIndexList.rowOf(live, id);
        return row == null || tombstoneVersion >= row.getVersion();
    }

    static boolean inBuckets(String id, int bucketCount, Set<Integer> buckets) {
        return buckets == null || buckets.contains(DigestHash.bucket(DigestHash.idHash(id), bucketCount));
    }

    private void add(String id, long version, long length, boolean deleted) {
        final var idHash = DigestHash.idHash(id);
        final var hash = DigestHash.entryHash(idHash, version, length, deleted);
        final var bucket = DigestHash.bucket(idHash, bucketCount);
        sums[bucket] += hash;
        counts[bucket]++;
        sum += hash;
        total++;
    }

    long total() {
        return total;
    }

    String summary() {
        return DigestHash.summary(total, sum);
    }

    List<String> bucketSummaries() {
        final var summaries = new ArrayList<String>(bucketCount);
        for (var bucket = 0; bucket < bucketCount; bucket++) {
            summaries.add(DigestHash.summary(counts[bucket], sums[bucket]));
        }
        return summaries;
    }

    Set<Integer> differingBuckets(LocalDigest peer) {
        final var differing = new TreeSet<Integer>();
        for (var bucket = 0; bucket < bucketCount; bucket++) {
            if (counts[bucket] != peer.counts[bucket] || sums[bucket] != peer.sums[bucket]) {
                differing.add(bucket);
            }
        }
        return differing;
    }

    Set<Integer> differingBuckets(List<String> peerSummaries) {
        final var differing = new TreeSet<Integer>();
        for (var bucket = 0; bucket < bucketCount; bucket++) {
            if (!DigestHash.summary(counts[bucket], sums[bucket]).equals(peerSummaries.get(bucket))) {
                differing.add(bucket);
            }
        }
        return differing;
    }
}
