package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.cluster.DigestHash;

public class DigestHashTest {
    @Test
    public void test_id_hashes_match_their_golden_values() {
        assertEquals(0x82a2a958a9bece5bL, DigestHash.idHash("a"));
        assertEquals(0x448f18072836dcf6L, DigestHash.idHash("doc-42"));
        assertEquals(0xefd01f60ba992926L, DigestHash.idHash(""));
    }

    @Test
    public void test_entry_hashes_match_their_golden_values() {
        final var idHash = DigestHash.idHash("doc-42");

        assertEquals(0xfc4d497fb1357687L, DigestHash.entryHash(idHash, 123456789L, 512L, false));
        assertEquals(0xdc02c7b9abbac2cfL, DigestHash.entryHash(idHash, 123456789L, 0L, true));
    }

    @Test
    public void test_buckets_come_from_the_top_bits_of_the_id_hash() {
        assertEquals(10, DigestHash.bucket(DigestHash.idHash("a"), 16));
        assertEquals(2090, DigestHash.bucket(DigestHash.idHash("a"), 4096));
        assertEquals(8, DigestHash.bucket(DigestHash.idHash("doc-42"), 16));
    }

    @Test
    public void test_every_field_of_an_entry_changes_its_hash() {
        final var idHash = DigestHash.idHash("x");
        final var base = DigestHash.entryHash(idHash, 7L, 40L, false);

        assertNotEquals(base, DigestHash.entryHash(idHash, 8L, 40L, false));
        assertNotEquals(base, DigestHash.entryHash(idHash, 7L, 41L, false));
        assertNotEquals(base, DigestHash.entryHash(idHash, 7L, 40L, true));
        assertNotEquals(base, DigestHash.entryHash(DigestHash.idHash("y"), 7L, 40L, false));
    }

    @Test
    public void test_the_bucket_count_is_a_clamped_power_of_two() {
        assertEquals(16, DigestHash.bucketCountFor(0));
        assertEquals(16, DigestHash.bucketCountFor(1024));
        assertEquals(32, DigestHash.bucketCountFor(1025));
        assertEquals(2048, DigestHash.bucketCountFor(100_000));
        assertEquals(4096, DigestHash.bucketCountFor(10_000_000));
        assertTrue(DigestHash.isBucketCount(4096));
        assertFalse(DigestHash.isBucketCount(8192));
        assertFalse(DigestHash.isBucketCount(48));
        assertFalse(DigestHash.isBucketCount(0));
    }

    @Test
    public void test_a_summary_is_the_count_and_the_hex_sum() {
        assertEquals("3:ff", DigestHash.summary(3, 255L));
        assertEquals("0:0", DigestHash.summary(0, 0L));
    }
}
