package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.*;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JsonArrayTest {
    // Add JsonBaseElement to array and verify size increases
    @Test
    public void test_add_element_increases_size() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");

        array.add(element);

        assertEquals(1, array.size());
        assertTrue(array.contains(element));
    }

    // Add null element and verify it's converted to JsonNull.INSTANCE
    @Test
    public void test_add_null_converts_to_json_null() {
        JsonArray array = new JsonArray();

        array.add(JsonNull.INSTANCE);

        assertEquals(1, array.size());
        assertTrue(array.contains(JsonNull.INSTANCE));
        assertEquals(JsonNull.INSTANCE, array.get(0));
    }

    // Add valid JsonBaseElement to array and verify it's added correctly
    @Test
    public void test_add_valid_element() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");

        array.add(element);

        assertEquals(1, array.size());
        assertSame(element, array.get(0));
    }

    // Adding all elements from a non-empty source array to an empty target array
    @Test
    public void test_add_all_from_non_empty_source() {
        JsonArray target = new JsonArray();
        JsonArray source = new JsonArray();
        source.add("test1");
        source.add("test2");

        target.addAll(source);

        assertEquals(2, target.size());
        assertEquals(new JsonString("test1"), target.get(0));
        assertEquals(new JsonString("test2"), target.get(1));
    }

    // Adding elements from null source array
    @Test
    public void test_add_all_from_null_source() {
        JsonArray target = new JsonArray();

        assertThrows(NullPointerException.class, () -> target.addAll(null));

        assertEquals(0, target.size());
    }

    // Adding a non-empty string creates a new JsonString element in the array
    @Test
    public void test_add_string_creates_json_string_element() {
        JsonArray array = new JsonArray();
        String testString = "test";

        array.add(testString);

        assertEquals(1, array.size());
        assertInstanceOf(JsonString.class, array.get(0));
        assertEquals(testString, ((JsonString)array.get(0)).getValue());
    }

    // Adding null string should create JsonString with null value
    @Test
    public void test_add_null_string_creates_json_string_with_null() {
        JsonArray array = new JsonArray();
        String nullString = null;

        array.add(nullString);

        assertEquals(1, array.size());
        assertInstanceOf(JsonString.class, array.get(0));
        assertNull(((JsonString)array.get(0)).getValue());
    }

    // Set valid JsonBaseElement at existing index and return previous element
    @Test
    public void set_valid_element_returns_previous_element() {
        JsonArray array = new JsonArray();
        array.add(new JsonString("first"));
        JsonString newElement = new JsonString("second");

        JsonBaseElement previous = array.set(0, newElement);

        assertEquals(new JsonString("first"), previous);
        assertEquals(newElement, array.get(0));
    }

    // Set element at index 0 in empty array throws IndexOutOfBoundsException
    @Test
    public void set_element_in_empty_array_throws_exception() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");

        assertThrows(IndexOutOfBoundsException.class, () -> array.set(0, element));
    }

    // Remove existing element from array and return true
    @Test
    public void test_remove_existing_element_returns_true() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");
        array.add(element);

        boolean result = array.remove(element);

        assertTrue(result);
        assertEquals(0, array.size());
    }

    // Remove element from empty array returns false
    @Test
    public void test_remove_from_empty_array_returns_false() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");

        boolean result = array.remove(element);

        assertFalse(result);
        assertEquals(0, array.size());
    }

    // Remove element at valid index and return the removed element
    @Test
    public void test_remove_element_at_valid_index() {
        JsonArray array = new JsonArray();
        JsonString element1 = new JsonString("first");
        JsonString element2 = new JsonString("second");
        array.add(element1);
        array.add(element2);

        JsonBaseElement removed = array.remove(1);

        assertEquals(element2, removed);
        assertEquals(1, array.size());
        assertEquals(element1, array.get(0));
    }

    // Remove element at index 0 from array with single element
    @Test
    public void test_remove_single_element() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("only");
        array.add(element);

        JsonBaseElement removed = array.remove(0);

        assertEquals(element, removed);
        assertEquals(0, array.size());
        assertTrue(array.isEmpty());
    }

    // Check if array contains a previously added JsonBaseElement
    @Test
    public void test_contains_added_element() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");
        array.add(element);

        assertTrue(array.contains(element));
    }

    // Check contains() with null element
    @Test
    public void test_contains_null_element() {
        JsonArray array = new JsonArray();
        array.add(JsonNull.INSTANCE);

        assertTrue(array.contains(JsonNull.INSTANCE));
    }

    // Returns 0 for a newly created empty JsonArray
    @Test
    public void test_empty_array_size_is_zero() {
        JsonArray array = new JsonArray();

        assertEquals(0, array.size());
    }

    // Size remains unchanged after failed operations
    @Test
    public void test_size_unchanged_after_failed_remove() {
        JsonArray array = new JsonArray();
        array.add("test");
        int initialSize = array.size();

        array.remove(new JsonString("non-existent"));

        assertEquals(initialSize, array.size());
    }

    // Return true when JsonArray is initialized with no elements
    @Test
    public void test_empty_array_is_empty() {
        JsonArray jsonArray = new JsonArray();

        assertTrue(jsonArray.isEmpty());
    }

    // Verify isEmpty behavior after adding and removing same element
    @Test
    public void test_array_empty_after_add_remove() {
        JsonArray jsonArray = new JsonArray();
        JsonString element = new JsonString("test");

        jsonArray.add(element);
        assertFalse(jsonArray.isEmpty());

        jsonArray.remove(element);
        assertTrue(jsonArray.isEmpty());
    }

    // Get element at valid index returns correct JsonBaseElement
    @Test
    public void test_get_element_at_valid_index() {
        JsonArray array = new JsonArray();
        JsonString element = new JsonString("test");
        array.add(element);

        JsonBaseElement result = array.get(0);

        assertEquals(element, result);
    }

    // Get element at negative index throws IndexOutOfBoundsException
    @Test
    public void test_get_element_at_negative_index_throws_exception() {
        JsonArray array = new JsonArray();
        array.add(new JsonString("test"));

        assertThrows(IndexOutOfBoundsException.class, () -> array.get(-1));
    }

    // Return list containing multiple JsonBaseElement objects
    @Test
    public void test_asList_returns_list_with_multiple_elements() {
        JsonArray jsonArray = new JsonArray();
        jsonArray.add(new JsonString("first"));
        jsonArray.add(new JsonNumber(42));
        jsonArray.add(JsonNull.INSTANCE);

        List<JsonBaseElement> result = jsonArray.asList();

        assertEquals(3, result.size());
        assertInstanceOf(JsonString.class, result.get(0));
        assertInstanceOf(JsonNumber.class, result.get(1));
        assertInstanceOf(JsonNull.class, result.get(2));
    }

    // Verify returned list is modifiable without affecting original JsonArray
    @Test
    public void test_asList_modifications_do_not_affect_original() {
        JsonArray jsonArray = new JsonArray();
        jsonArray.add(new JsonString("test"));
        jsonArray.add(new JsonNumber(123));

        List<JsonBaseElement> result = jsonArray.asList();
        result.add(new JsonBoolean(true));

        assertEquals(3, result.size());
        assertEquals(2, jsonArray.size());
        assertEquals(new JsonString("test"), jsonArray.get(0));
        assertEquals(new JsonNumber(123), jsonArray.get(1));
    }

    // Compare JsonArray with itself returns true
    @Test
    public void test_equals_with_same_instance() {
        JsonArray array = new JsonArray();
        array.add("test");
        array.add("test2");

        boolean result = array.equals(array);

        assertTrue(result);
    }

    // Compare JsonArray with itself returns true
    @Test
    public void test_equals_with_other_instance() {
        JsonArray array = new JsonArray();
        array.add("test");
        array.add("test2");

        JsonArray array1 = new JsonArray();
        array1.add("test");
        array1.add("test2");

        boolean result = array.equals(array1);

        assertTrue(result);
    }

    // Compare with null returns false
    @Test
    public void test_equals_with_null() {
        JsonArray array = new JsonArray();
        array.add("test");

        boolean result = array.equals(null);

        assertFalse(result);
    }

    // Returns same hash code for JsonArrays with identical elements in same order
    @Test
    public void test_identical_arrays_have_same_hashcode() {
        JsonArray array1 = new JsonArray();
        array1.add("test");
        array1.add(new JsonString("value"));

        JsonArray array2 = new JsonArray();
        array2.add("test");
        array2.add(new JsonString("value"));

        assertEquals(array1.hashCode(), array2.hashCode());
    }

    // Returns valid hash code for empty JsonArray
    @Test
    public void test_empty_array_hashcode() {
        JsonArray emptyArray = new JsonArray();

        int hashCode = emptyArray.hashCode();

        JsonArray anotherEmptyArray = new JsonArray();
        assertEquals(hashCode, anotherEmptyArray.hashCode());
        assertNotEquals(0, hashCode);
    }

    // Iterator returns elements in the same order they were added
    @Test
    public void test_iterator_maintains_insertion_order() {
        JsonArray array = new JsonArray();
        array.add(new JsonString("first"));
        array.add(new JsonNumber(2));
        array.add(new JsonBoolean(true));

        Iterator<JsonBaseElement> iterator = array.iterator();

        assertTrue(iterator.hasNext());
        assertEquals(new JsonString("first"), iterator.next());

        assertTrue(iterator.hasNext());
        assertEquals(new JsonNumber(2), iterator.next());

        assertTrue(iterator.hasNext());
        assertEquals(new JsonBoolean(true), iterator.next());

        assertFalse(iterator.hasNext());
    }

    // Iterator behavior on empty JsonArray
    @Test
    public void test_iterator_empty_array() {
        JsonArray array = new JsonArray();
        Iterator<JsonBaseElement> iterator = array.iterator();

        assertFalse(iterator.hasNext());
    }

    // Deep copy of empty array returns new empty array
    @Test
    public void test_empty_array_deep_copy() {
        JsonArray originalArray = new JsonArray();

        JsonBaseElement copiedArray = originalArray.deepCopy();

        assertAll(
                () -> assertInstanceOf(JsonArray.class, copiedArray),
                () -> assertTrue(copiedArray instanceof JsonArray && ((JsonArray)copiedArray).isEmpty()),
                () -> assertNotSame(originalArray, copiedArray)
        );
    }

    // Deep copy of array containing null elements
    @Test
    public void test_array_with_null_deep_copy() {
        JsonArray originalArray = new JsonArray();
        originalArray.add((JsonBaseElement)null);
        originalArray.add((JsonBaseElement)null);

        JsonBaseElement copiedArray = originalArray.deepCopy();

        assertAll(
                () -> assertEquals(2, ((JsonArray)copiedArray).size()),
                () -> assertTrue(((JsonArray)copiedArray).get(0).isJsonNull()),
                () -> assertTrue(((JsonArray)copiedArray).get(1).isJsonNull()),
                () -> assertNotSame(originalArray, copiedArray)
        );
    }
}