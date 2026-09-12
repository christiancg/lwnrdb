package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.IterableTypeAdapter;

public class IterableTypeAdapterTest {
    @BeforeEach
    public void setUp() {
        new EJson();
    }

    @Test
    public void test_serialize_non_empty_iterable_to_json() {
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);
        List<String> testList = Arrays.asList("test1", "test2", "test3");

        String result = adapter.toJson(testList);

        assertEquals("[\"test1\",\"test2\",\"test3\"]", result);
    }

    @Test
    public void test_serialize_iterable_with_null_values() {
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);
        List<String> testList = Arrays.asList("test1", null, "test3");

        String result = adapter.toJson(testList);

        assertEquals("[\"test1\",null,\"test3\"]", result);
    }

    @Test
    public void test_convert_json_array_to_iterable() {
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);

        JsonArray jsonArray = new JsonArray();
        jsonArray.add("test1");
        jsonArray.add("test2");

        Iterable<String> result = adapter.fromJson(jsonArray);

        assertNotNull(result);

        List<String> resultList = StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(result.iterator(), Spliterator.ORDERED), false).toList();

        assertEquals(2, resultList.size());
        assertEquals("test1", resultList.get(0));
        assertEquals("test2", resultList.get(1));
    }

    @Test
    public void test_return_null_for_non_array_input() {
        IterableTypeAdapter<String> adapter = new IterableTypeAdapter<>(String.class);

        JsonString jsonString = new JsonString("test");

        Iterable<String> result = adapter.fromJson(jsonString);

        assertNull(result);
    }
}
