package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.ScanOrderMemo;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class ScanOrderMemoTest {
    private static final String COLLECTION = "db|coll";

    private static DbEntry entry(String id, long page) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var entry = DbEntry.fromJsonObject("db", "coll", data);
        entry.setPage(page);
        return entry;
    }

    private static Map<String, DbEntry> collection(DbEntry... entries) {
        final var map = new ConcurrentHashMap<String, DbEntry>();
        for (final var entry : entries) {
            map.put(entry.get_id(), entry);
        }
        return map;
    }

    private static List<String> ids(DbEntry[] rows) {
        return Arrays.stream(rows).map(DbEntry::get_id).toList();
    }

    @Test
    public void test_rows_come_back_by_page_then_id_and_the_order_is_reused() {
        final var memo = new ScanOrderMemo();
        final var b = entry("b", 1);
        final var cached = collection(entry("c", 2), b, entry("a", 1));
        final var first = memo.inScanOrder(COLLECTION, cached);
        assertEquals(List.of("a", "b", "c"), ids(first));
        final var second = memo.inScanOrder(COLLECTION, cached);
        assertEquals(List.of("a", "b", "c"), ids(second));
        assertSame(b, second[1]);
        assertEquals(1, memo.rememberedCollections());
    }

    @Test
    public void test_the_order_is_rebuilt_after_any_change() {
        final var memo = new ScanOrderMemo();
        final var cached = collection(entry("b", 1), entry("a", 1), entry("c", 2));
        memo.inScanOrder(COLLECTION, cached);
        cached.put("d", entry("d", 0));
        assertEquals(List.of("d", "a", "b", "c"), ids(memo.inScanOrder(COLLECTION, cached)));
        cached.put("a", entry("a", 3));
        assertEquals(List.of("d", "b", "c", "a"), ids(memo.inScanOrder(COLLECTION, cached)));
        cached.remove("b");
        assertEquals(List.of("d", "c", "a"), ids(memo.inScanOrder(COLLECTION, cached)));
        cached.remove("c");
        cached.put("e", entry("e", 2));
        assertEquals(List.of("d", "e", "a"), ids(memo.inScanOrder(COLLECTION, cached)));
    }

    @Test
    public void test_a_new_map_for_the_collection_is_never_trusted() {
        final var memo = new ScanOrderMemo();
        memo.inScanOrder(COLLECTION, collection(entry("a", 1), entry("b", 2)));
        final var readmitted = collection(entry("a", 2), entry("b", 1));
        assertEquals(List.of("b", "a"), ids(memo.inScanOrder(COLLECTION, readmitted)));
    }

    @Test
    public void test_at_most_eight_collections_are_remembered_and_no_row_is_held() throws Exception {
        final var memo = new ScanOrderMemo();
        final var maps = new java.util.ArrayList<Map<String, DbEntry>>();
        for (var i = 0; i < 12; i++) {
            final var cached = collection(entry("a", i));
            maps.add(cached);
            memo.inScanOrder("db|c" + i, cached);
        }
        assertEquals(8, memo.rememberedCollections());
        final Field orders = ScanOrderMemo.class.getDeclaredField("orders");
        orders.setAccessible(true);
        for (final var order : ((Map<?, ?>) orders.get(memo)).values()) {
            for (final var component : order.getClass().getRecordComponents()) {
                assertTrue(component.getType() != DbEntry[].class && component.getType() != DbEntry.class);
            }
        }
        assertEquals(12, maps.size());
    }
}
