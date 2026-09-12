package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.JsonArrayTypeAdapter;

public class JsonArrayTypeAdapterTest {
    @Test
    public void test_empty_json_array_conversion() {
        JsonArrayTypeAdapter adapter = new JsonArrayTypeAdapter();
        JsonArray emptyArray = new JsonArray();

        String result = adapter.toJson(emptyArray);

        assertEquals("[]", result);
    }

    @Test
    public void test_null_json_array_handling() {
        JsonArrayTypeAdapter adapter = new JsonArrayTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    @Test
    public void test_json_array_with_null_elements() {
        new EJson();
        JsonArrayTypeAdapter adapter = new JsonArrayTypeAdapter();
        JsonArray arrayWithNull = new JsonArray();
        arrayWithNull.add((JsonBaseElement) null);
        arrayWithNull.add(new JsonString("test"));
        arrayWithNull.add((JsonBaseElement) null);

        String result = adapter.toJson(arrayWithNull);

        assertEquals("[null,\"test\",null]", result);
    }

    @Test
    public void test_null_input() {
        JsonArrayTypeAdapter adapter = new JsonArrayTypeAdapter();

        JsonBaseElement result = adapter.fromJson(null);

        assertNull(result);
    }
}
