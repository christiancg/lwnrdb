package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;

public class SchemaRefCycleTest {
    private final EJson ejson = new EJson();

    private JsonObject json(String text) {
        return ejson.fromJson(text, JsonObject.class);
    }

    private boolean instanceIsValid(String schema, String document) {
        return ejson.validateInstance(json(document), json(schema)).isValid();
    }

    @Test
    public void test_a_stored_self_referential_schema_reports_an_error_rather_than_overflowing() {
        assertFalse(instanceIsValid("{\"$ref\":\"#\"}", "{\"a\":1}"),
                "schemas saved before the meta check existed must degrade to a validation error");
    }

    @Test
    public void test_a_stored_cyclic_defs_schema_reports_an_error_rather_than_overflowing() {
        final var schema = "{\"$ref\":\"#/$defs/a\",\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},"
                + "\"b\":{\"$ref\":\"#/$defs/a\"}}}";
        assertFalse(instanceIsValid(schema, "{\"a\":1}"));
    }

    @Test
    public void test_a_cycle_through_an_applicator_reports_an_error() {
        assertFalse(instanceIsValid("{\"allOf\":[{\"$ref\":\"#\"}]}", "{\"a\":1}"));
    }

    @Test
    public void test_the_reported_error_names_the_cycle() {
        final var result = ejson.validateInstance(json("{\"a\":1}"), json("{\"$ref\":\"#\"}"));
        assertTrue(result.getErrors().getFirst().contains("cycle"));
    }

    @Test
    public void test_recursion_bounded_by_instance_depth_still_validates() {
        final var schema = "{\"type\":\"object\",\"properties\":{\"child\":{\"$ref\":\"#\"},"
                + "\"name\":{\"type\":\"string\"}}}";
        assertTrue(instanceIsValid(schema, "{\"name\":\"a\",\"child\":{\"name\":\"b\",\"child\":{\"name\":\"c\"}}}"));
        assertFalse(instanceIsValid(schema, "{\"name\":\"a\",\"child\":{\"name\":3}}"),
                "the guard must not stop a recursive schema from still rejecting a bad nested value");
    }

    @Test
    public void test_the_same_definition_may_be_reused_by_sibling_properties() {
        final var schema = "{\"$defs\":{\"s\":{\"type\":\"string\"}},\"type\":\"object\",\"properties\":{"
                + "\"a\":{\"$ref\":\"#/$defs/s\"},\"b\":{\"$ref\":\"#/$defs/s\"}}}";
        assertTrue(instanceIsValid(schema, "{\"a\":\"x\",\"b\":\"y\"}"),
                "two siblings resolving to one definition is reuse, not a cycle");
    }
}
