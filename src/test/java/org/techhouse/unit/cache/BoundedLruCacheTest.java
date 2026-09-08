package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.BoundedLruCache;

public class BoundedLruCacheTest {

    private static BoundedLruCache<String> byEntries(int maxEntries) {
        return new BoundedLruCache<>(maxEntries, 0L, _ -> 1L);
    }

    private static BoundedLruCache<String> byBytes(long maxBytes) {
        return new BoundedLruCache<>(Integer.MAX_VALUE, maxBytes, value -> (long) value.length());
    }

    @Test
    public void test_evicts_eldest_when_entry_cap_exceeded() {
        final var cache = byEntries(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals(2, cache.size());
        assertNull(cache.get("a"));
        assertNotNull(cache.get("b"));
        assertNotNull(cache.get("c"));
    }

    @Test
    public void test_evicts_when_byte_cap_exceeded() {
        final var cache = byBytes(10L);
        cache.put("a", "12345");
        cache.put("b", "12345");
        cache.put("c", "12345");
        assertEquals(2, cache.size());
        assertEquals(10L, cache.bytes());
        assertNull(cache.get("a"));
    }

    @Test
    public void test_access_order_keeps_hot_entry() {
        final var cache = byEntries(2);
        cache.put("a", "1");
        cache.put("b", "2");
        // Touching "a" makes "b" the least recently used, so the next insert evicts "b" instead.
        cache.get("a");
        cache.put("c", "3");
        assertNotNull(cache.get("a"));
        assertNull(cache.get("b"));
    }

    @Test
    public void test_max_entries_zero_disables_caching() {
        final var cache = byEntries(0);
        cache.put("a", "1");
        assertNull(cache.get("a"));
        assertEquals(0, cache.size());
        assertEquals(0L, cache.bytes());
    }

    @Test
    public void test_remove_if_drops_matching_keys_and_bytes() {
        final var cache = byBytes(0L);
        cache.put("db1|x", "12345");
        cache.put("db1|y", "12345");
        cache.put("db2|z", "12345");
        cache.removeIf(key -> key.startsWith("db1|"));
        assertEquals(1, cache.size());
        assertEquals(5L, cache.bytes());
        assertNotNull(cache.get("db2|z"));
    }

    @Test
    public void test_remove_updates_byte_total() {
        final var cache = byBytes(0L);
        cache.put("a", "12345");
        cache.remove("a");
        assertEquals(0L, cache.bytes());
        assertEquals(0, cache.size());
    }

    @Test
    public void test_replacing_a_key_does_not_double_count_bytes() {
        final var cache = byBytes(0L);
        cache.put("a", "12345");
        cache.put("a", "123");
        assertEquals(1, cache.size());
        assertEquals(3L, cache.bytes());
    }

    @Test
    public void test_clear_empties_the_cache() {
        final var cache = byBytes(0L);
        cache.put("a", "12345");
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.bytes());
    }

    @Test
    public void test_single_entry_larger_than_byte_cap_is_kept() {
        // Otherwise a cache whose bound is smaller than one value would evict on every insert and never serve
        // anything, turning a too-small budget into a permanent cache miss.
        final var cache = byBytes(2L);
        cache.put("a", "1234567890");
        assertNotNull(cache.get("a"));
        assertEquals(1, cache.size());
    }

    @Test
    public void test_concurrent_puts_stay_consistent() throws Exception {
        final var cache = byEntries(50);
        final var threads = 8;
        final var ready = new CountDownLatch(threads);
        final var done = new CountDownLatch(threads);
        for (var t = 0; t < threads; t++) {
            final var index = t;
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                for (var i = 0; i < 200; i++) {
                    cache.put("k" + index + "-" + i, "v");
                    cache.get("k" + index + "-" + i);
                }
                done.countDown();
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(cache.size() <= 50);
        assertEquals(cache.size(), cache.bytes());
    }
}
