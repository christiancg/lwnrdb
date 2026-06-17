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
    // Insert key-value pairs and verify correct storage and retrieval
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

    // Insert null keys and verify NullPointerException thrown
    @Test
    public void test_null_key_throws_exception() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        assertThrows(NullPointerException.class, () -> map.put(null, 1));
    }

    // Constructor creates LinkedTreeMap with natural order comparator when allowNullValues is true
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

    // Constructor handles non-Comparable key types by throwing ClassCastException
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

    // Returns current size of map when size field is positive
    @Test
    public void test_returns_positive_size() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("key1", "value1");
        map.put("key2", "value2");
        map.put("key3", "value3");
        assertEquals(3, map.size());
    }

    // Returns zero when map is newly initialized
    @Test
    public void test_returns_zero_for_new_map() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        assertEquals(0, map.size());
    }

    // Returns value when key exists in map
    @Test
    public void test_get_returns_value_for_existing_key() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer result = map.get("key1");
        assertEquals(100, result);
    }

    // Handles null key by returning null
    @Test
    public void test_get_returns_null_for_null_key() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer result = map.get(null);
        assertNull(result);
    }

    // Returns true when key exists in the map
    @Test
    public void test_contains_existing_key_returns_true() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("testKey", "testValue");
        boolean result = map.containsKey("testKey");
        assertTrue(result);
    }

    // Returns false when key is null
    @Test
    public void test_contains_null_key_returns_false() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("testKey", "testValue");
        boolean result = map.containsKey(null);
        assertFalse(result);
    }

    // Put new key-value pair successfully when both key and value are non-null
    @Test
    public void test_put_new_key_value_pair_successfully() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        Integer result = map.put("test", 123);
        assertNull(result);
        assertEquals(123, map.get("test"));
        assertEquals(1, map.size());
    }

    // Attempt to put null key throws NullPointerException
    @Test
    public void test_put_null_key_throws_exception() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        NullPointerException exception = assertThrows(NullPointerException.class, () -> map.put(null, 123));
        assertEquals("key == null", exception.getMessage());
        assertEquals(0, map.size());
    }

    // Map is cleared and size becomes 0
    @Test
    public void test_clear_empty_map() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("key1", "value1");
        map.clear();
        assertTrue(map.isEmpty());
    }

    // Clear operation on map with multiple nodes works correctly
    @Test
    public void test_clear_populated_map() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("key1", "value1");
        map.put("key2", "value2");
        map.put("key3", "value3");
        map.clear();
        assertTrue(map.isEmpty());
    }

    // Remove existing key-value pair and return the value
    @Test
    public void test_remove_existing_key_returns_value() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer removedValue = map.remove("key1");
        assertEquals(100, removedValue);
        assertNull(map.get("key1"));
        assertEquals(0, map.size());
    }

    // Remove non-existent key returns null
    @Test
    public void test_remove_nonexistent_key_returns_null() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key1", 100);
        Integer removedValue = map.remove("nonexistent");
        assertNull(removedValue);
        assertEquals(1, map.size());
        assertEquals(100, map.get("key1"));
    }

    // Returns existing EntrySet instance when entrySet field is not null
    @Test
    public void test_returns_existing_entryset_instance() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        Set<Map.Entry<String, String>> firstCall = map.entrySet();
        Set<Map.Entry<String, String>> secondCall = map.entrySet();
        assertSame(firstCall, secondCall);
    }

    // Returns existing KeySet instance when keySet field is not null
    @Test
    public void test_returns_existing_keyset_instance() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        Set<String> keySet1 = map.keySet();
        Set<String> keySet2 = map.keySet();
        assertSame(keySet1, keySet2);
    }

    // Multiple concurrent calls to keySet() should return same instance
    @Test
    public void test_concurrent_calls_return_same_instance_for_keyset() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        Set<String> keySet1 = map.keySet();
        Set<String> keySet2 = map.keySet();
        Set<String> keySet3 = map.keySet();
        assertAll(() -> assertSame(keySet1, keySet2), () -> assertSame(keySet2, keySet3),
                () -> assertSame(keySet1, keySet3));
    }

    // put with null value throws when allowNullValues=false (L50)
    @Test
    public void test_put_null_value_throws_when_null_values_not_allowed() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(false);
        assertThrows(NullPointerException.class, () -> map.put("key", null));
    }

    // Constructor with null comparator uses natural order (L25-27)
    @Test
    public void test_constructor_with_null_comparator_uses_natural_order() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(null, true);
        map.put("b", "B");
        map.put("a", "A");
        assertEquals("A", map.get("a"));
        assertEquals("B", map.get("b"));
    }

    // Custom comparator is used in find (L82, L89)
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

    // findByObject with incompatible type returns null via ClassCastException (L136-137)
    @Test
    public void test_find_by_object_incompatible_type_returns_null() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("key", 1);
        // Pass an Integer as key to a String-keyed map — ClassCastException caught internally
        assertNull(map.get(42));
        assertFalse(map.containsKey(42));
    }

    // findByEntry and equal() are exercised via entrySet().contains() (L141-148)
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
        assertFalse(map.entrySet().contains("not_an_entry"));
    }

    // removeInternal with a node that has both left and right children (L152-184)
    @Test
    public void test_remove_node_with_two_children() {
        LinkedTreeMap<Integer, String> map = new LinkedTreeMap<>(true);
        // Insert enough nodes to guarantee a two-child removal
        for (int i = 1; i <= 7; i++)
            map.put(i, "v" + i);
        // Remove root-area node (4) which has both children in a balanced tree
        map.remove(4);
        assertNull(map.get(4));
        assertEquals(6, map.size());
    }

    // Removing multiple nodes exercises rebalance delta=-1/1 break path (L281-284)
    @Test
    public void test_remove_triggers_rebalance() {
        LinkedTreeMap<Integer, String> map = new LinkedTreeMap<>(true);
        for (int i = 1; i <= 10; i++)
            map.put(i, "v" + i);
        // Remove several nodes to trigger various rebalancing paths
        for (int i = 1; i <= 5; i++)
            map.remove(i);
        assertEquals(5, map.size());
        for (int i = 6; i <= 10; i++)
            assertNotNull(map.get(i));
    }

    // Node.setValue() throws when null not allowed (L384-386)
    @Test
    public void test_entry_set_value_null_throws_when_null_not_allowed() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(false);
        map.put("k", "v");
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        assertThrows(NullPointerException.class, () -> entry.setValue(null));
    }

    // Node.setValue() replaces value and returns old value (L387-389)
    @Test
    public void test_entry_set_value_replaces_value() {
        LinkedTreeMap<String, String> map = new LinkedTreeMap<>(true);
        map.put("k", "old");
        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        String old = entry.setValue("new");
        assertEquals("old", old);
        assertEquals("new", map.get("k"));
    }

    // Node.equals(), hashCode(), toString() (L392-406)
    @Test
    public void test_entry_equals_hashcode_tostring() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("k", 42);
        Map.Entry<String, Integer> entry = map.entrySet().iterator().next();
        // equals with matching entry
        assertTrue(entry.equals(Map.entry("k", 42)));
        // equals with non-matching
        assertFalse(entry.equals(Map.entry("k", 99)));
        assertFalse(entry.equals(Map.entry("x", 42)));
        assertFalse(entry.equals("not_an_entry"));
        // hashCode is consistent
        assertEquals(entry.hashCode(), entry.hashCode());
        // toString
        assertEquals("k=42", entry.toString());
    }

    // Node.first() and Node.last() via removeInternal with two-child nodes (L409-426)
    @Test
    public void test_node_first_last_via_remove_two_children() {
        LinkedTreeMap<Integer, String> map = new LinkedTreeMap<>(true);
        // 5, 3, 7, 1, 4 creates a tree where 3 has both 1 (left) and 4 (right)
        map.put(5, "five");
        map.put(3, "three");
        map.put(7, "seven");
        map.put(1, "one");
        map.put(4, "four");
        // Removing 3 triggers removeInternal with two children, calling first() or last()
        map.remove(3);
        assertNull(map.get(3));
        assertNotNull(map.get(1));
        assertNotNull(map.get(4));
    }

    // Iterator throws NoSuchElementException when no more elements (L447-448)
    @Test
    public void test_iterator_throws_no_such_element() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        it.next(); // consume the only element
        assertThrows(NoSuchElementException.class, it::next);
    }

    // Iterator throws ConcurrentModificationException on structural modification (L450-451)
    @Test
    public void test_iterator_throws_concurrent_modification() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        it.next();
        map.put("c", 3); // structural modification
        assertThrows(ConcurrentModificationException.class, it::next);
    }

    // Iterator.remove() removes the last returned entry (L457-463)
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

    // Iterator.remove() without prior next() throws IllegalStateException
    @Test
    public void test_iterator_remove_without_next_throws() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
        assertThrows(IllegalStateException.class, it::remove);
    }

    // EntrySet.remove() removes a matching entry (L484-494)
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

    // EntrySet.remove() with non-matching entry returns false
    @Test
    public void test_entry_set_remove_non_matching_returns_false() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        assertFalse(map.entrySet().remove(Map.entry("a", 99)));
        assertFalse(map.entrySet().remove("not_an_entry"));
    }

    // KeySet.size() returns correct count (L504)
    @Test
    public void test_keyset_size() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        assertEquals(2, map.keySet().size());
    }

    // KeySet iterator traverses all keys (L508-512)
    @Test
    public void test_keyset_iterator() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        Set<String> keys = new HashSet<>();
        for (String key : map.keySet())
            keys.add(key);
        assertEquals(Set.of("a", "b"), keys);
    }

    // KeySet.contains() delegates to containsKey (L515-516)
    @Test
    public void test_keyset_contains() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        assertTrue(map.keySet().contains("a"));
        assertFalse(map.keySet().contains("z"));
    }

    // KeySet.remove() removes the key-value pair (L519-520)
    @Test
    public void test_keyset_remove() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        assertTrue(map.keySet().remove("a"));
        assertFalse(map.containsKey("a"));
        assertFalse(map.keySet().remove("z"));
    }

    // KeySet.clear() empties the map (L523-524)
    @Test
    public void test_keyset_clear() {
        LinkedTreeMap<String, Integer> map = new LinkedTreeMap<>(true);
        map.put("a", 1);
        map.put("b", 2);
        map.keySet().clear();
        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
    }
}
