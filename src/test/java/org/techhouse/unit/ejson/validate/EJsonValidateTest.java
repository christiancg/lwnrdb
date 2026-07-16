package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

public class EJsonValidateTest {
    private final EJson ejson = new EJson();

    private JsonObject schema(String json) {
        return ejson.fromJson(json, JsonObject.class);
    }

    // validateSchema(JsonObject) accepts a well-formed schema
    @Test
    public void test_validate_schema_object_valid() {
        assertTrue(ejson.validateSchema(schema("{\"type\":\"object\"}")).isValid());
    }

    // validateSchema(JsonObject) rejects a malformed schema
    @Test
    public void test_validate_schema_object_invalid() {
        assertFalse(ejson.validateSchema(schema("{\"required\":\"name\"}")).isValid());
    }

    // validateSchema(String) parses and accepts a well-formed schema
    @Test
    public void test_validate_schema_string_valid() {
        assertTrue(ejson.validateSchema("{\"type\":\"string\",\"minLength\":1}").isValid());
    }

    // validateSchema(String) rejects a malformed schema
    @Test
    public void test_validate_schema_string_invalid_keyword() {
        assertFalse(ejson.validateSchema("{\"minimum\":\"x\"}").isValid());
    }

    // validateSchema(String) reports non-JSON input as invalid
    @Test
    public void test_validate_schema_string_not_json() {
        final var result = ejson.validateSchema("not json");
        assertFalse(result.isValid());
        assertTrue(result.getErrors().getFirst().contains("not a valid JSON object"));
    }

    // validateWithSchema validates a compliant instance
    @Test
    public void test_validate_with_schema_compliant() {
        final var result = ejson.validateWithSchema(new JsonNumber(5), schema("{\"type\":\"integer\",\"minimum\":0}"));
        assertTrue(result.isValid());
    }

    // validateWithSchema flags a non-compliant instance
    @Test
    public void test_validate_with_schema_non_compliant() {
        final var result = ejson.validateWithSchema(new JsonNumber(-5), schema("{\"type\":\"integer\",\"minimum\":0}"));
        assertFalse(result.isValid());
    }

    // validateWithSchema throws when the schema itself is invalid
    @Test
    public void test_validate_with_invalid_schema_throws() {
        assertThrows(InvalidSchemaException.class,
                () -> ejson.validateWithSchema(new JsonNumber(5), schema("{\"minimum\":\"x\"}")));
    }
}
