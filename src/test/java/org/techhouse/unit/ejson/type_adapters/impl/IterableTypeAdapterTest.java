package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.IterableTypeAdapter;

import java.util.Arrays;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

public class IterableTypeAdapterTest {
    @BeforeEach
    public void setUp() {
        new EJson();
    }

    // Convert non-empty Iterable<T> to JSON string with proper array format
    @Test
    public void test_serialize_non_empty_iterable_to_json() {
        // Arrange
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);
        List<String> testList = Arrays.asList("test1", "test2", "test3");

        // Act
        String result = adapter.toJson(testList);

        // Assert
        assertEquals("[\"test1\",\"test2\",\"test3\"]", result);
    }

    // Handle null values inside Iterable during serialization
    @Test
    public void test_serialize_iterable_with_null_values() {
        // Arrange
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);
        List<String> testList = Arrays.asList("test1", null, "test3");

        // Act
        String result = adapter.toJson(testList);

        // Assert
        assertEquals("[\"test1\",null,\"test3\"]", result);
    }

    // Convert JsonArray to Iterable<T> with valid elements
    @Test
    public void test_convert_json_array_to_iterable() {
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);

        JsonArray jsonArray = new JsonArray();
        jsonArray.add("test1");
        jsonArray.add("test2");

        Iterable<String> result = adapter.fromJson(jsonArray);

        assertNotNull(result);

        List<String> resultList = StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(result.iterator(), Spliterator.ORDERED), false)
                .toList();

        assertEquals(2, resultList.size());
        assertEquals("test1", resultList.get(0));
        assertEquals("test2", resultList.get(1));
    }

    // Return null when input is not a JsonArray
    @Test
    public void test_return_null_for_non_array_input() {
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);

        JsonString jsonString = new JsonString("test");

        Iterable<String> result = adapter.fromJson(jsonString);

        assertNull(result);
    }
}