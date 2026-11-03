package org.techhouse.cluster.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cluster.DigestHash;

public final class AdminTombstoneDigest {
    private final int bucketCount;
    private final long[] sums;
    private final long[] counts;

    private AdminTombstoneDigest(int bucketCount) {
        this.bucketCount = bucketCount;
        this.sums = new long[bucketCount];
        this.counts = new long[bucketCount];
    }

    public static AdminTombstoneDigest of(Map<String, Long> tombstones) {
        return of(tombstones, DigestHash.bucketCountFor(tombstones.size()));
    }

    public static AdminTombstoneDigest of(Map<String, Long> tombstones, int bucketCount) {
        final var digest = new AdminTombstoneDigest(bucketCount);
        tombstones.forEach((key, version) -> {
            final var idHash = DigestHash.idHash(key);
            final var bucket = DigestHash.bucket(idHash, bucketCount);
            digest.sums[bucket] += DigestHash.entryHash(idHash, version, 0L, true);
            digest.counts[bucket]++;
        });
        return digest;
    }

    public static boolean isValid(int bucketCount, List<String> summaries) {
        return DigestHash.isBucketCount(bucketCount) && summaries != null && summaries.size() == bucketCount;
    }

    public static int bucketOf(String key, int bucketCount) {
        return DigestHash.bucket(DigestHash.idHash(key), bucketCount);
    }

    public int bucketCount() {
        return bucketCount;
    }

    public List<String> summaries() {
        final var summaries = new ArrayList<String>(bucketCount);
        for (var bucket = 0; bucket < bucketCount; bucket++) {
            summaries.add(DigestHash.summary(counts[bucket], sums[bucket]));
        }
        return summaries;
    }

    public Set<Integer> differingFrom(List<String> peerSummaries) {
        final var differing = new HashSet<Integer>();
        for (var bucket = 0; bucket < bucketCount; bucket++) {
            if (!DigestHash.summary(counts[bucket], sums[bucket]).equals(peerSummaries.get(bucket))) {
                differing.add(bucket);
            }
        }
        return differing;
    }
}
