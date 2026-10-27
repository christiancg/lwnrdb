package org.techhouse.cluster;

public final class DigestHash {
    public static final int MIN_BUCKETS = 16;
    public static final int MAX_BUCKETS = 4096;
    private static final int ENTRIES_PER_BUCKET = 64;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long VERSION_MIX = 0x9E3779B97F4A7C15L;
    private static final long DELETED_MIX = 0x5851F42D4C957F2DL;
    private static final int BUCKET_SHIFT = 52;

    private DigestHash() {
    }

    public static long idHash(String id) {
        var hash = FNV_OFFSET;
        for (var i = 0; i < id.length(); i++) {
            hash ^= id.charAt(i);
            hash *= FNV_PRIME;
        }
        return fmix64(hash);
    }

    public static long entryHash(long idHash, long version, long length, boolean deleted) {
        return fmix64(idHash ^ version * VERSION_MIX ^ Long.rotateLeft(length, 32) ^ (deleted ? DELETED_MIX : 0L));
    }

    public static int bucket(long idHash, int bucketCount) {
        return (int) (idHash >>> BUCKET_SHIFT) & (bucketCount - 1);
    }

    public static int bucketCountFor(long entries) {
        final var wanted = Math.max(1L, (entries + ENTRIES_PER_BUCKET - 1) / ENTRIES_PER_BUCKET);
        final var power = Long.highestOneBit(wanted) == wanted ? wanted : Long.highestOneBit(wanted) << 1;
        return Math.clamp(power, MIN_BUCKETS, MAX_BUCKETS);
    }

    public static boolean isBucketCount(int bucketCount) {
        return bucketCount >= 1 && bucketCount <= MAX_BUCKETS && Integer.bitCount(bucketCount) == 1;
    }

    public static String summary(long count, long sum) {
        return count + ":" + Long.toHexString(sum);
    }

    static long fmix64(long value) {
        var k = value;
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
