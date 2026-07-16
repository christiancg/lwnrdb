package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

public class JsonSchemaTest {
    private final EJson ejson = new EJson();

    private JsonObject schema(String json) {
        return ejson.fromJson(json, JsonObject.class);
    }

    private JsonBaseElement instance(String json) {
        return ejson.fromJson(json, JsonObject.class);
    }

    private boolean validate(String schemaJson, String instanceJson) {
        return ejson.validateWithSchema(instance(instanceJson), schema(schemaJson)).isValid();
    }

    // a root pointer "#" resolves to the whole schema
    @Test
    public void test_root_pointer() {
        final var s = "{\"properties\":{\"n\":{\"$ref\":\"#\"}}}";
        assertTrue(validate(s, "{\"n\":{}}"));
    }

    // an array-index pointer step resolves
    @Test
    public void test_array_index_pointer() {
        final var s = "{\"prefixItems\":[{\"type\":\"number\"}],\"properties\":{\"n\":{\"$ref\":\"#/prefixItems/0\"}}}";
        assertTrue(validate(s, "{\"n\":5}"));
        assertFalse(validate(s, "{\"n\":\"x\"}"));
    }

    // an escaped pointer segment (~1 -> '/') resolves a key containing a slash
    @Test
    public void test_escaped_pointer_segment() {
        final var s = "{\"$defs\":{\"a/b\":{\"type\":\"number\"}},\"properties\":{\"n\":{\"$ref\":\"#/$defs/a~1b\"}}}";
        assertTrue(validate(s, "{\"n\":5}"));
        assertFalse(validate(s, "{\"n\":\"x\"}"));
    }

    // a non-fragment $ref is rejected
    @Test
    public void test_non_fragment_ref_throws() {
        final var s = "{\"properties\":{\"n\":{\"$ref\":\"http://example/x\"}}}";
        assertThrows(InvalidSchemaException.class, () -> validate(s, "{\"n\":1}"));
    }

    // a fragment that is not a pointer ("#x") is rejected
    @Test
    public void test_bad_fragment_throws() {
        final var s = "{\"properties\":{\"n\":{\"$ref\":\"#x\"}}}";
        assertThrows(InvalidSchemaException.class, () -> validate(s, "{\"n\":1}"));
    }

    // an unresolvable object key is rejected
    @Test
    public void test_unresolvable_key_throws() {
        final var s = "{\"properties\":{\"n\":{\"$ref\":\"#/$defs/missing\"}}}";
        assertThrows(InvalidSchemaException.class, () -> validate(s, "{\"n\":1}"));
    }

    // an out-of-range array index is rejected
    @Test
    public void test_array_index_out_of_range_throws() {
        final var s = "{\"prefixItems\":[{}],\"properties\":{\"n\":{\"$ref\":\"#/prefixItems/9\"}}}";
        assertThrows(InvalidSchemaException.class, () -> validate(s, "{\"n\":1}"));
    }

    // a non-numeric array index is rejected
    @Test
    public void test_array_index_not_a_number_throws() {
        final var s = "{\"prefixItems\":[{}],\"properties\":{\"n\":{\"$ref\":\"#/prefixItems/x\"}}}";
        assertThrows(InvalidSchemaException.class, () -> validate(s, "{\"n\":1}"));
    }

    // stepping into a primitive value is rejected
    @Test
    public void test_step_into_primitive_throws() {
        final var s = "{\"title\":\"hi\",\"properties\":{\"n\":{\"$ref\":\"#/title/x\"}}}";
        assertThrows(InvalidSchemaException.class, () -> validate(s, "{\"n\":1}"));
    }
}
