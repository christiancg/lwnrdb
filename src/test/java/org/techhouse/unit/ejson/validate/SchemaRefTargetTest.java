package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.MetaSchemaValidator;

public class SchemaRefTargetTest {
    private final EJson ejson = new EJson();
    private final MetaSchemaValidator meta = new MetaSchemaValidator();

    private boolean isValid(String schema) {
        return meta.validate(ejson.fromJson(schema, JsonObject.class)).isValid();
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
        final var result = meta.validate(ejson
                .fromJson("{\"required\":[\"a\"],\"properties\":{\"x\":{\"$ref\":\"#/required\"}}}", JsonObject.class));
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
}
