package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.heap.DocumentHeapSizer;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;

public class CacheSizeEstimatorTest {
    private static final HeapLayout LAYOUT = new HeapLayout(8, 12, 4);
    private static final String DB = "estimatorDb";
    private static final String COLL = "estimatorColl";
    private static final String TYPICAL_ID = "x".repeat(36);
    private static final long OLD_FLAT_FIELD_ENTRY_BYTES = 64L;
    private static final Class<?> HASH_MAP_NODE = HeapLayout.jdkClass("java.util.HashMap$Node");
    private static final Class<?> CONCURRENT_HASH_MAP_NODE = HeapLayout
            .jdkClass("java.util.concurrent.ConcurrentHashMap$Node");

    private static DbEntry entry(String id, JsonObject data) {
        data.addProperty("_id", id);
        return DbEntry.fromJsonObject(DB, COLL, data);
    }

    private static DbEntry entryWith(String id, String key, String value) {
        final var data = new JsonObject();
        data.addProperty(key, value);
        return entry(id, data);
    }

    @Test
    public void test_estimate_entry_is_memoized_and_reset_by_set_data() {
        final var dbEntry = entryWith("m1", "name", "abc");
        final var first = CacheSizeEstimator.estimateEntry(dbEntry);
        assertEquals(first, dbEntry.getCachedHeapBytes());

        dbEntry.getData().addProperty("extra", "an in-place change the memo does not see");
        assertEquals(first, CacheSizeEstimator.estimateEntry(dbEntry));

        dbEntry.byteSize();
        assertEquals(first, dbEntry.getCachedHeapBytes(), "byteSize() must not reset the heap memo");

        final var bigger = new JsonObject();
        bigger.addProperty("name", "a much longer value than before");
        bigger.addProperty("_id", "m1");
        dbEntry.setData(bigger);
        assertEquals(-1L, dbEntry.getCachedHeapBytes());
        assertTrue(CacheSizeEstimator.estimateEntry(dbEntry) > first);

        dbEntry.set_id("m2");
        assertEquals(-1L, dbEntry.getCachedHeapBytes());
    }

    @Test
    public void test_estimate_entry_is_the_entry_plus_its_tree() {
        final var dbEntry = entryWith("t1", "name", "abc");
        assertEquals(LAYOUT.shallowSize(DbEntry.class) + DocumentHeapSizer.sizeOf(dbEntry.getData(), LAYOUT),
                CacheSizeEstimator.estimateEntry(dbEntry, LAYOUT));
    }

    @Test
    public void test_estimate_entry_is_independent_of_byte_size() {
        final var numbers = new JsonArray();
        for (var i = 1; i <= 7; i++) {
            final var element = new JsonObject();
            element.addProperty("n", i);
            numbers.add(element);
        }
        final var shaped = new JsonObject();
        shaped.add("k", numbers);
        final var nested = entry("same", shaped);
        var padding = 0;
        DbEntry flat;
        do {
            flat = entryWith("same", "k", "a".repeat(padding++));
        } while (flat.byteSize() < nested.byteSize());

        assertEquals(nested.byteSize(), flat.byteSize());
        assertTrue(CacheSizeEstimator.estimateEntry(nested) > CacheSizeEstimator.estimateEntry(flat),
                "seven nested objects cost more heap than one string of the same serialized length");
    }

    @Test
    public void test_collection_size_adds_map_node_overhead() {
        final var documents = new LinkedHashMap<String, DbEntry>();
        documents.put("c1", entryWith("c1", "v", "one"));
        documents.put("c2", entryWith("c2", "v", "two"));
        final var current = HeapLayout.current();
        final var slot = current.shallowSize(CONCURRENT_HASH_MAP_NODE) + 2L * current.referenceBytes();

        var expected = 0L;
        for (final var dbEntry : documents.values()) {
            expected += CacheSizeEstimator.estimateEntry(dbEntry) + slot;
            assertEquals(CacheSizeEstimator.estimateEntry(dbEntry) + slot,
                    CacheSizeEstimator.estimateCollectionEntry(dbEntry));
        }
        assertEquals(expected, CacheSizeEstimator.estimateCollectionSize(documents));
        assertEquals(0L, CacheSizeEstimator.estimateCollectionSize(null));
    }

    @Test
    public void test_pk_index_size_uses_id_lengths() {
        final var shortId = new PkIndexEntry(DB, COLL, "abc", 0, 10, 0);
        final var longId = new PkIndexEntry(DB, COLL, "a".repeat(20), 10, 10, 0);
        final var perEntry = LAYOUT.referenceBytes() + LAYOUT.shallowSize(PkIndexEntry.class);
        final var expected = perEntry + LAYOUT.stringSize("abc") + perEntry + LAYOUT.stringSize("a".repeat(20));

        assertEquals(expected, CacheSizeEstimator.estimatePkIndexSize(List.of(shortId, longId), LAYOUT));
        assertEquals(0L, CacheSizeEstimator.estimatePkIndexSize(null));
        assertTrue(CacheSizeEstimator.estimatePkIndexSize(List.of(shortId)) > 0L);
    }

    @Test
    public void test_pk_bytes_per_entry_times_count() {
        final var entries = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 4; i++) {
            entries.add(new PkIndexEntry(DB, COLL, "id-" + i, i * 10L, 10, 0));
        }
        final var perEntry = CacheSizeEstimator.pkIndexBytesPerEntry(entries, LAYOUT);
        assertEquals(LAYOUT.referenceBytes() + LAYOUT.shallowSize(PkIndexEntry.class) + LAYOUT.stringSize("id-0"),
                perEntry);
        assertEquals(4 * perEntry, CacheSizeEstimator.estimatePkIndexSize(entries, LAYOUT));
        assertEquals(LAYOUT.referenceBytes() + LAYOUT.shallowSize(PkIndexEntry.class) + LAYOUT.stringSize(TYPICAL_ID),
                CacheSizeEstimator.pkIndexBytesPerEntry(List.of(), LAYOUT),
                "an empty index is charged a typical entry per future row");
        assertEquals(CacheSizeEstimator.pkIndexBytesPerEntry(null, HeapLayout.current()),
                CacheSizeEstimator.pkIndexBytesPerEntry(null));
    }

    private static long fieldEntryOverhead() {
        return LAYOUT.referenceBytes() + LAYOUT.shallowSize(FieldIndexEntry.class) + LAYOUT.shallowSize(HashSet.class)
                + LAYOUT.shallowSize(HashMap.class);
    }

    @Test
    public void test_field_index_size_counts_ids_and_values() {
        final var ids = HashSet.<String>newHashSet(2);
        ids.add("a1");
        ids.add("b2");
        final List<FieldIndexEntry<?>> entries = List.of(new FieldIndexEntry<>(DB, COLL, "red", ids),
                new FieldIndexEntry<>(DB, COLL, 42.0, Set.of("c3")), new FieldIndexEntry<>(DB, COLL, true, null));
        final var idCost = LAYOUT.shallowSize(HASH_MAP_NODE) + LAYOUT.stringSize("a1");
        final var red = fieldEntryOverhead() + LAYOUT.stringSize("red") + LAYOUT.presizedHashTableSize(2) + 2 * idCost;
        final var number = fieldEntryOverhead() + LAYOUT.shallowSize(Double.class) + LAYOUT.presizedHashTableSize(1)
                + idCost;
        final var bool = fieldEntryOverhead();

        assertEquals(red + number + bool, CacheSizeEstimator.estimateFieldIndexSize(entries, LAYOUT));
        assertEquals(0L, CacheSizeEstimator.estimateFieldIndexSize(null));
        assertTrue(CacheSizeEstimator.estimateFieldIndexSize(entries) > 0L);
    }

    @Test
    public void test_field_index_count_fallback_uses_the_model_not_a_flat_constant() {
        final var singleIdEntry = fieldEntryOverhead() + LAYOUT.presizedHashTableSize(1)
                + LAYOUT.shallowSize(HASH_MAP_NODE) + LAYOUT.stringSize(TYPICAL_ID) + LAYOUT.shallowSize(Double.class);
        assertEquals(3 * singleIdEntry, CacheSizeEstimator.estimateFieldIndexSizeByCount(3, LAYOUT));
        assertNotEquals(3 * OLD_FLAT_FIELD_ENTRY_BYTES, CacheSizeEstimator.estimateFieldIndexSizeByCount(3));
        assertEquals(0L, CacheSizeEstimator.estimateFieldIndexSizeByCount(0));
    }

    @Test
    public void test_page_heap_estimate_scales_a_sample() {
        final var page = new ArrayList<DbEntry>();
        for (var i = 0; i < 100; i++) {
            final var value = i < CacheSizeEstimator.PAGE_SAMPLE_SIZE ? "s" : "a far longer value than the sample";
            page.add(entryWith("p" + i, "v", value));
        }
        var sampled = 0L;
        for (var i = 0; i < CacheSizeEstimator.PAGE_SAMPLE_SIZE; i++) {
            sampled += CacheSizeEstimator.estimateEntry(page.get(i));
        }

        assertEquals(sampled * 100 / CacheSizeEstimator.PAGE_SAMPLE_SIZE, CacheSizeEstimator.estimatePageHeap(page));
        assertEquals(-1L, page.get(CacheSizeEstimator.PAGE_SAMPLE_SIZE).getCachedHeapBytes(),
                "documents past the sample are never walked");
    }

    @Test
    public void test_page_heap_of_a_small_page_is_exact() {
        final var page = List.of(entryWith("q1", "v", "one"), entryWith("q2", "v", "a longer two"));
        assertEquals(CacheSizeEstimator.estimateEntry(page.get(0)) + CacheSizeEstimator.estimateEntry(page.get(1)),
                CacheSizeEstimator.estimatePageHeap(page));
        assertEquals(0L, CacheSizeEstimator.estimatePageHeap(List.of()));
        assertEquals(0L, CacheSizeEstimator.estimatePageHeap(null));
    }
}
