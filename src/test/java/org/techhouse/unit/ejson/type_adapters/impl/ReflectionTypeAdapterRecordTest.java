package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.type_adapters.impl.ReflectionTypeAdapter;

public class ReflectionTypeAdapterRecordTest {
    private EJson eJson;

    public record Simple(String name, int count, boolean flag) {
    }

    public record Nullable(String name, String maybe) {
    }

    public record Primitives(int intValue, long longValue, double doubleValue, boolean boolValue) {
    }

    public record Nested(String name, Simple inner) {
    }

    public record WithList(String name, List<String> values) {
    }

    @BeforeEach
    public void setUp() {
        eJson = new EJson();
    }

    @Test
    public void test_serializes_record_components_in_declaration_order() {
        assertEquals("{\"name\":\"abc\",\"count\":7,\"flag\":true}", eJson.toJson(new Simple("abc", 7, true)));
    }

    @Test
    public void test_deserializes_record_with_all_components_present() {
        final var result = eJson.fromJson("{\"name\":\"abc\",\"count\":7,\"flag\":true}", Simple.class);
        assertNotNull(result);
        assertEquals("abc", result.name());
        assertEquals(7, result.count());
        assertTrue(result.flag());
    }

    @Test
    public void test_deserializes_record_with_absent_reference_component_as_null() {
        final var result = eJson.fromJson("{\"name\":\"x\"}", Nullable.class);
        assertNotNull(result);
        assertEquals("x", result.name());
        assertNull(result.maybe());
    }

    @Test
    public void test_deserializes_record_with_absent_primitive_component_as_zero() {
        final var result = eJson.fromJson("{\"intValue\":5}", Primitives.class);
        assertNotNull(result);
        assertEquals(5, result.intValue());
        assertEquals(0L, result.longValue());
        assertEquals(0.0d, result.doubleValue());
        assertFalse(result.boolValue());
    }

    @Test
    public void test_deserializes_record_with_explicit_null_component() {
        final var result = eJson.fromJson("{\"name\":\"x\",\"maybe\":null}", Nullable.class);
        assertNotNull(result);
        assertEquals("x", result.name());
        assertNull(result.maybe());
    }

    @Test
    public void test_deserializes_record_with_explicit_null_primitive_component_as_zero() {
        final var result = eJson.fromJson("{\"intValue\":null,\"longValue\":3}", Primitives.class);
        assertNotNull(result);
        assertEquals(0, result.intValue());
        assertEquals(3L, result.longValue());
    }

    @Test
    public void test_deserializes_nested_record_component() {
        final var result = eJson.fromJson("{\"name\":\"outer\",\"inner\":{\"name\":\"in\",\"count\":2,\"flag\":false}}",
                Nested.class);
        assertNotNull(result);
        assertEquals("outer", result.name());
        assertNotNull(result.inner());
        assertEquals("in", result.inner().name());
        assertEquals(2, result.inner().count());
        assertFalse(result.inner().flag());
    }

    @Test
    public void test_deserializes_record_with_generic_list_component() {
        final var result = eJson.fromJson("{\"name\":\"x\",\"values\":[\"a\",\"b\"]}", WithList.class);
        assertNotNull(result);
        assertEquals(List.of("a", "b"), result.values());
    }

    @Test
    public void test_round_trips_record_through_json() {
        final var original = new Simple("round", 42, false);
        assertEquals(original, eJson.fromJson(eJson.toJson(original), Simple.class));
    }

    @Test
    public void test_returns_null_for_non_object_json() {
        assertNull(new ReflectionTypeAdapter<>(Simple.class).fromJson(new JsonArray()));
    }
}
