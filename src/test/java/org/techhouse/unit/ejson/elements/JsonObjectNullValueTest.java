package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public class JsonObjectNullValueTest {
    private static final String PROPERTY = "field";
    private final EJson eJson = IocContainer.get(EJson.class);

    @Test
    public void test_add_property_with_null_string_is_a_json_null() {
        final var jsonObject = new JsonObject();

        jsonObject.addProperty(PROPERTY, (String) null);

        assertSame(JsonNull.INSTANCE, jsonObject.get(PROPERTY));
    }

    @Test
    public void test_add_property_with_null_boolean_is_a_json_null() {
        final var jsonObject = new JsonObject();

        jsonObject.addProperty(PROPERTY, (Boolean) null);

        assertSame(JsonNull.INSTANCE, jsonObject.get(PROPERTY));
    }

    @Test
    public void test_add_property_with_null_integer_is_a_json_null() {
        final var jsonObject = new JsonObject();

        jsonObject.addProperty(PROPERTY, (Integer) null);

        assertSame(JsonNull.INSTANCE, jsonObject.get(PROPERTY));
    }

    @Test
    public void test_add_property_with_null_long_is_a_json_null() {
        final var jsonObject = new JsonObject();

        jsonObject.addProperty(PROPERTY, (Long) null);

        assertSame(JsonNull.INSTANCE, jsonObject.get(PROPERTY));
    }

    @Test
    public void test_add_with_a_null_string_value_is_a_json_null() {
        final var jsonObject = new JsonObject();

        jsonObject.add(PROPERTY, (String) null);

        assertSame(JsonNull.INSTANCE, jsonObject.get(PROPERTY));
    }

    @Test
    public void test_a_null_property_round_trips_to_the_same_element() {
        final var inMemory = new JsonObject();
        inMemory.addProperty("_id", "r1");
        inMemory.addProperty("procedure", (String) null);
        inMemory.addProperty("durationMs", (Number) null);
        inMemory.addProperty("truncated", (Boolean) null);

        final var fromDisk = eJson.fromJson(eJson.toJson(inMemory), JsonObject.class);

        assertSame(JsonNull.INSTANCE, inMemory.get("procedure"));
        assertSame(JsonNull.INSTANCE, fromDisk.get("procedure"));
        assertSame(JsonNull.INSTANCE, inMemory.get("durationMs"));
        assertSame(JsonNull.INSTANCE, fromDisk.get("durationMs"));
        assertSame(JsonNull.INSTANCE, inMemory.get("truncated"));
        assertSame(JsonNull.INSTANCE, fromDisk.get("truncated"));
    }

    @Test
    public void test_a_null_value_serializes_to_a_bare_json_null() {
        final var jsonObject = new JsonObject();

        jsonObject.addProperty(PROPERTY, (Number) null);

        assertEquals("{\"field\":null}", eJson.toJson(jsonObject));
    }

    @Test
    public void test_a_null_key_still_throws_for_every_overload() {
        final var jsonObject = new JsonObject();

        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, (String) null));
        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, (Boolean) null));
        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, (Integer) null));
        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, (Number) null));
        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, (Long) null));
        assertThrows(NullPointerException.class, () -> jsonObject.add(null, (String) null));
    }

    @Test
    public void test_an_empty_string_and_a_zero_are_values_not_nulls() {
        final var jsonObject = new JsonObject();

        jsonObject.addProperty("empty", "");
        jsonObject.addProperty("zero", 0);
        jsonObject.addProperty("off", false);

        assertEquals("", jsonObject.get("empty").asJsonString().getValue());
        assertEquals(0, jsonObject.get("zero").asJsonNumber().asInteger());
        assertFalse(jsonObject.get("off").asJsonBoolean().getValue());
    }
}
