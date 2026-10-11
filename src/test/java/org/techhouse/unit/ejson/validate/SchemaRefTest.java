package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.MetaSchemaValidator;
import org.techhouse.ejson.validate.SchemaValidationResult;

public class SchemaRefTest {
    private final EJson ejson = new EJson();
    private final MetaSchemaValidator meta = new MetaSchemaValidator();

    private JsonObject json(String text) {
        return ejson.fromJson(text, JsonObject.class);
    }

    private SchemaValidationResult validate(String schema) {
        return meta.validate(json(schema));
    }

    private boolean isValid(String schema) {
        return validate(schema).isValid();
    }

    private boolean instanceIsValid(String schema, String document) {
        return ejson.validateInstance(json(document), json(schema)).isValid();
    }

    private static String behindDefinitions(String target) {
        return "{\"definitions\":{\"r\":" + target + "},\"properties\":{\"role\":{\"$ref\":\"#/definitions/r\"}}}";
    }

    @Test
    public void test_a_ref_to_an_array_is_rejected() {
        assertFalse(isValid("{\"required\":[\"a\"],\"properties\":{\"x\":{\"$ref\":\"#/required\"}}}"));
    }

    @Test
    public void test_a_ref_to_a_string_is_rejected() {
        assertFalse(isValid("{\"title\":\"t\",\"properties\":{\"x\":{\"$ref\":\"#/title\"}}}"));
    }

    @Test
    public void test_a_ref_to_a_number_is_rejected() {
        assertFalse(isValid("{\"minimum\":1,\"properties\":{\"x\":{\"$ref\":\"#/minimum\"}}}"));
    }

    @Test
    public void test_the_error_names_the_ref() {
        final var result = validate("{\"required\":[\"a\"],\"properties\":{\"x\":{\"$ref\":\"#/required\"}}}");
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("must resolve to a schema")));
    }

    @Test
    public void test_a_ref_to_an_object_is_still_accepted() {
        assertTrue(
                isValid("{\"$defs\":{\"s\":{\"type\":\"string\"}},\"properties\":{\"x\":{\"$ref\":\"#/$defs/s\"}}}"));
    }

    @Test
    public void test_a_ref_to_a_boolean_is_still_accepted() {
        assertTrue(isValid("{\"$defs\":{\"any\":true},\"properties\":{\"x\":{\"$ref\":\"#/$defs/any\"}}}"));
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

    @Test
    public void test_a_malformed_enum_behind_definitions_is_refused() {
        final var result = validate(behindDefinitions("{\"enum\":\"admin\"}"));

        assertFalse(result.isValid(), "a non-array enum behind a $ref used to save and then enforce nothing");
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("/definitions/r/enum")),
                () -> "the error must name the target, got " + result.getErrors());
    }

    @Test
    public void test_a_malformed_min_length_behind_definitions_is_refused() {
        assertFalse(validate(behindDefinitions("{\"minLength\":\"x\"}")).isValid(),
                "a malformed keyword behind a $ref used to refuse every matching write with 503-11 instead");
    }

    @Test
    public void test_a_valid_target_behind_definitions_is_accepted_with_its_warning() {
        final var result = validate(behindDefinitions("{\"enum\":[\"admin\"]}"));

        assertTrue(result.isValid(), () -> "got " + result.getErrors());
        assertEquals(1, result.getWarnings().size(), () -> "got " + result.getWarnings());
        assertTrue(result.getWarnings().getFirst().contains("definitions"));
    }

    @Test
    public void test_an_unknown_keyword_inside_a_target_is_warned_about_once() {
        final var result = validate(behindDefinitions("{\"enum\":[\"admin\"],\"foo\":1}"));

        assertTrue(result.isValid(), () -> "got " + result.getErrors());
        assertEquals(1, result.getWarnings().stream().filter(warning -> warning.contains("'foo'")).count(),
                () -> "got " + result.getWarnings());
    }

    @Test
    public void test_a_ref_to_the_properties_map_is_checked_as_a_schema() {
        final var result = validate(
                "{\"properties\":{\"type\":{\"type\":\"string\"}," + "\"copy\":{\"$ref\":\"#/properties\"}}}");

        assertFalse(result.isValid(), "a map of schemas is not a schema: its member 'type' holds an object");
    }

    @Test
    public void test_a_cycle_reached_only_through_a_target_is_refused() {
        final var result = validate("{\"definitions\":{\"a\":{\"properties\":{\"y\":{\"$ref\":\"#/definitions/b\"}}},"
                + "\"b\":{\"$ref\":\"#/definitions/c\"},\"c\":{\"$ref\":\"#/definitions/b\"}},"
                + "\"properties\":{\"x\":{\"$ref\":\"#/definitions/a\"}}}");

        assertFalse(result.isValid(), "the b/c cycle sits under a target the keyword walk never descended into");
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("reference cycle")),
                () -> "got " + result.getErrors());
    }

    @Test
    public void test_recursion_through_properties_into_definitions_terminates() {
        final var result = validate("{\"definitions\":{\"node\":{\"type\":\"object\","
                + "\"properties\":{\"child\":{\"$ref\":\"#/definitions/node\"}}}},"
                + "\"properties\":{\"root\":{\"$ref\":\"#/definitions/node\"}}}");

        assertTrue(result.isValid(), () -> "recursion bounded by instance depth is legal, got " + result.getErrors());
    }

    @Test
    public void test_a_target_already_under_defs_adds_no_duplicate_warning() {
        final var result = validate(
                "{\"properties\":{\"x\":{\"$ref\":\"#/$defs/s\"}},\"$defs\":{\"s\":{\"type\":\"string\",\"foo\":1}}}");

        assertTrue(result.isValid(), () -> "got " + result.getErrors());
        assertEquals(1, result.getWarnings().size(), () -> "got " + result.getWarnings());
    }

    @Test
    public void test_a_boolean_target_behind_definitions_is_accepted() {
        assertTrue(validate(behindDefinitions("true")).isValid());
    }
}
