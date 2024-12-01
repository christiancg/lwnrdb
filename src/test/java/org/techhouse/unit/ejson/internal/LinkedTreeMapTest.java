package org.techhouse.unit.ejson.internal;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.LinkedTreeMap;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> map.put(null, 123)
        );
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
        assertAll(
                () -> assertSame(keySet1, keySet2),
                () -> assertSame(keySet2, keySet3),
                () -> assertSame(keySet1, keySet3)
        );
    }
}