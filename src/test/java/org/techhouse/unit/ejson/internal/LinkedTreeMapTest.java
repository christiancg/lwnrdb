package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.LinkedTreeMap;

public class LinkedTreeMapTest {
    @Test
    public void test_insert_and_retrieve_key_value_pairs() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("one", 1);
        map.put("two", 2);
        map.put("three", 3);
        assertEquals(3, map.size());
        assertEquals(1, map.get("one"));
        assertEquals(2, map.get("two"));
        assertEquals(3, map.get("three"));
    }

    @Test
    public void test_null_key_throws_exception() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        assertThrows(NullPointerException.class, () -> map.put(null, 1));
    }

    @Test
    public void test_constructor_with_natural_order_and_null_values_allowed() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", null);
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
        assertNull(map.get("c"));
        assertEquals(3, map.size());
    }

    @Test
    public void test_constructor_throws_on_non_comparable_key() {
        class NonComparableClass {
        }
        LinkedTreeMap<NonComparableClass, String> map = new LinkedTreeMap<>(true);
        NonComparableClass key1 = new NonComparableClass();
        NonComparableClass key2 = new NonComparableClass();
        assertThrows(ClassCastException.class, () -> {
            map.put(key1, "value1");
            map.put(key2, "value2");
        });
    }

    @Test
    public void test_returns_positive_size() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("key1", "value1");
        map.put("key2", "value2");
        map.put("key3", "value3");
        assertEquals(3, map.size());
    }

    @Test
    public void test_returns_zero_for_new_map() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        assertEquals(0, map.size());
    }

    @Test
    public void test_get_returns_value_for_existing_key() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer result = map.get("key1");
        assertEquals(100, result);
    }

    @Test
    public void test_get_returns_null_for_null_key() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer result = map.get(null);
        assertNull(result);
    }

    @Test
    public void test_contains_existing_key_returns_true() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("testKey", "testValue");
        boolean result = map.containsKey("testKey");
        assertTrue(result);
    }

    @Test
    public void test_contains_null_key_returns_false() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("testKey", "testValue");
        boolean result = map.containsKey(null);
        assertFalse(result);
    }

    @Test
    public void test_put_new_key_value_pair_successfully() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        Integer result = map.put("test", 123);
        assertNull(result);
        assertEquals(123, map.get("test"));
        assertEquals(1, map.size());
    }

    @Test
    public void test_put_null_key_throws_exception() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        NullPointerException exception = assertThrows(NullPointerException.class, () -> map.put(null, 123));
        assertEquals("key == null", exception.getMessage());
        assertEquals(0, map.size());
    }

    @Test
    public void test_clear_empty_map() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("key1", "value1");
        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    public void test_clear_populated_map() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("key1", "value1");
        map.put("key2", "value2");
        map.put("key3", "value3");
        map.clear();
        assertTrue(map.isEmpty());
    }

    @Test
    public void test_remove_existing_key_returns_value() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer removedValue = map.remove("key1");
        assertEquals(100, removedValue);
        assertNull(map.get("key1"));
        assertEquals(0, map.size());
    }

    @Test
    public void test_remove_nonexistent_key_returns_null() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer removedValue = map.remove("nonexistent");
        assertNull(removedValue);
        assertEquals(1, map.size());
        assertEquals(100, map.get("key1"));
    }

    @Test
    public void test_returns_existing_entry_set_instance() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        Set<Map.Entry<String, String>> firstCall = map.entrySet();
        Set<Map.Entry<String, String>> secondCall = map.entrySet();
        assertSame(firstCall, secondCall);
    }

    @Test
    public void test_returns_existing_keyset_instance() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        Set<String> keySet1 = map.keySet();
        Set<String> keySet2 = map.keySet();
        assertSame(keySet1, keySet2);
    }

    @Test
    public void test_concurrent_calls_return_same_instance_for_keyset() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        Set<String> keySet1 = map.keySet();
        Set<String> keySet2 = map.keySet();
        Set<String> keySet3 = map.keySet();
        assertAll(() -> assertSame(keySet1, keySet2), () -> assertSame(keySet2, keySet3),
                () -> assertSame(keySet1, keySet3));
    }

    @Test
    public void test_put_null_value_throws_when_null_values_not_allowed() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(false);
        assertThrows(NullPointerException.class, () -> map.put("key", null));
    }

    @Test
    public void test_constructor_with_null_comparator_uses_natural_order() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(null, true);
        map.put("b", "B");
        map.put("a", "A");
        assertEquals("A", map.get("a"));
        assertEquals("B", map.get("b"));
    }

    @Test
    public void test_custom_comparator_find() {
        Comparator<String> reverseOrder = Comparator.reverseOrder();
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(reverseOrder, true);
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        assertEquals(1, map.get("a"));
        assertEquals(2, map.get("b"));
        assertEquals(3, map.get("c"));
        assertTrue(map.containsKey("a"));
        assertFalse(map.containsKey("z"));
    }

    @Test
    public void test_find_by_object_incompatible_type_returns_null() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key", 1);
        assertNull(map.get(42)); // NOPMD - intentional type-mismatch test
        assertFalse(map.containsKey(42)); // NOPMD - intentional type-mismatch test
    }

    @Test
    public void test_entry_set_contains_via_find_by_entry() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("x", 10);
        Map.Entry<String, Integer> matching = Map.entry("x", 10);
        Map.Entry<String, Integer> wrongValue = Map.entry("x", 99);
        Map.Entry<String, Integer> wrongKey = Map.entry("z", 10);
        assertTrue(map.entrySet().contains(matching));
        assertFalse(map.entrySet().contains(wrongValue));
        assertFalse(map.entrySet().contains(wrongKey));
        assertFalse(map.entrySet().contains("not_an_entry")); // NOPMD - intentional type-mismatch test
    }

    @Test
    public void test_remove_node_with_two_children() {
        LinkedTreeMap<Integer, String> map = new LinkedTreeMap<>(true);
        for (int i = 1; i <= 7; i++)
            map.put(i, "v" + i);
        map.remove(4);
        assertNull(map.get(4));
        assertEquals(6, map.size());
    }

    @Test
    public void test_remove_triggers_rebalance() {
        LinkedTreeMap<Integer, String> map = new LinkedTreeMap<>(true);
        for (int i = 1; i <= 10; i++)
            map.put(i, "v" + i);
        for (int i = 1; i <= 5; i++)
            map.remove(i);
        assertEquals(5, map.size());
        for (int i = 6; i <= 10; i++)
            assertNotNull(map.get(i));
    }

    @Test
    public void test_entry_set_value_null_throws_when_null_not_allowed() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(false);
        map.put("k", "v");
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        assertThrows(NullPointerException.class, () -> entry.setValue(null));
    }

    @Test
    public void test_entry_set_value_replaces_value() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("k", "old");
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        String old = entry.setValue("new");
        assertEquals("old", old);
        assertEquals("new", map.get("k"));
    }

    @Test
    public void test_entry_equals_hashcode_to_string() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("k", 42);
        Map.Entry<String, Integer> entry = map.entrySet().iterator().next();
        assertEquals(entry, Map.entry("k", 42));
        assertNotEquals(entry, Map.entry("k", 99));
        assertNotEquals(entry, Map.entry("x", 42));
        assertNotEquals("not_an_entry", entry);
        assertEquals(entry.hashCode(), entry.hashCode());
        assertEquals("k=42", entry.toString());
    }

    @Test
    public void test_node_first_last_via_remove_two_children() {
        LinkedTreeMap<Integer, String> map = new LinkedTreeMap<>(true);
        map.put(5, "five");
        map.put(3, "three");
        map.put(7, "seven");
        map.put(1, "one");
        map.put(4, "four");
        map.remove(3);
        assertNull(map.get(3));
        assertNotNull(map.get(1));
        assertNotNull(map.get(4));
    }

    @Test
    public void test_iterator_throws_no_such_element() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        it.next();
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    public void test_iterator_throws_concurrent_modification() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        it.next();
        map.put("c", 3);
        assertThrows(ConcurrentModificationException.class, it::next);
    }

    @Test
    public void test_iterator_remove_entry() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        it.next();
        it.remove();
        assertEquals(1, map.size());
    }

    @Test
    public void test_iterator_remove_without_next_throws() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        assertThrows(IllegalStateException.class, it::remove);
    }

    @Test
    public void test_entry_set_remove_matching_entry() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        boolean removed = map.entrySet().remove(Map.entry("a", 1));
        assertTrue(removed);
        assertNull(map.get("a"));
        assertEquals(1, map.size());
    }

    @Test
    public void test_entry_set_remove_non_matching_returns_false() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        assertFalse(map.entrySet().remove(Map.entry("a", 99)));
        assertFalse(map.entrySet().remove("not_an_entry")); // NOPMD - intentional type-mismatch test
    }

    @Test
    public void test_keyset_size() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        assertEquals(2, map.size());
    }

    @Test
    public void test_keyset_iterator() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        Set<String> keys = new HashSet<>(map.keySet());
        assertEquals(Set.of("a", "b"), keys);
    }

    @Test
    public void test_keyset_contains() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        assertTrue(map.containsKey("a"));
        assertFalse(map.containsKey("z"));
    }

    @Test
    public void test_keyset_remove() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        final var keySet = map.keySet();
        assertTrue(keySet.remove("a"));
        assertFalse(map.containsKey("a"));
        assertFalse(keySet.remove("z"));
    }

    @Test
    public void test_keyset_clear() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        map.clear();
        assertTrue(map.isEmpty());
    }
}
