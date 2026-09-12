package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.MetaSchemaValidator;

public class MetaSchemaValidatorTest {
    private final EJson ejson = new EJson();
    private final MetaSchemaValidator meta = new MetaSchemaValidator();

    private JsonObject schema(String json) {
        return ejson.fromJson(json, JsonObject.class);
    }

    @Test
    public void test_boolean_schema_is_valid() {
        assertTrue(meta.validate(new JsonBoolean(true)).isValid());
        assertTrue(meta.validate(new JsonBoolean(false)).isValid());
    }

    @Test
    public void test_null_schema_is_missing() {
        final var result = meta.validate(null);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().getFirst().contains("missing"));
    }

    @Test
    public void test_number_schema_is_invalid() {
        final var result = meta.validate(new JsonNumber(3));
        assertFalse(result.isValid());
        assertTrue(result.getErrors().getFirst().contains("must be an object or a boolean"));
    }

    @Test
    public void test_valid_complex_schema() {
        final var result = meta.validate(schema("""
                {"type":"object","required":["name"],
                 "properties":{"name":{"type":"string","minLength":1},
                               "age":{"type":"integer","minimum":0}},
                 "additionalProperties":false,
                 "$defs":{"pos":{"type":"number","minimum":0}}}
                """));
        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    @Test
    public void test_unknown_type_name_rejected() {
        assertFalse(meta.validate(schema("{\"type\":\"strong\"}")).isValid());
    }

    @Test
    public void test_empty_type_array_rejected() {
        assertFalse(meta.validate(schema("{\"type\":[]}")).isValid());
    }

    @Test
    public void test_type_array_non_string_rejected() {
        assertFalse(meta.validate(schema("{\"type\":[1]}")).isValid());
    }

    @Test
    public void test_type_wrong_shape_rejected() {
        assertFalse(meta.validate(schema("{\"type\":{}}")).isValid());
    }

    @Test
    public void test_type_array_valid() {
        assertTrue(meta.validate(schema("{\"type\":[\"string\",\"null\"]}")).isValid());
    }

    @Test
    public void test_unsupported_keyword_rejected() {
        final var result = meta.validate(schema("{\"unevaluatedProperties\":false}"));
        assertFalse(result.isValid());
        assertTrue(result.getErrors().getFirst().contains("not supported"));
    }

    @Test
    public void test_unknown_keyword_warns_but_valid() {
        final var result = meta.validate(schema("{\"requird\":[\"name\"]}"));
        assertTrue(result.isValid());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().getFirst().contains("requird"));
    }

    @Test
    public void test_nested_unknown_keyword_warns() {
        final var result = meta.validate(schema("{\"properties\":{\"a\":{\"foo\":1}}}"));
        assertTrue(result.isValid());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().getFirst().contains("/properties/a/foo"));
    }

    @Test
    public void test_annotation_keywords_do_not_warn() {
        final var result = meta.validate(schema("{\"$schema\":\"x\",\"title\":\"t\",\"description\":\"d\"}"));
        assertTrue(result.isValid());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    public void test_enum_must_be_non_empty_array() {
        assertFalse(meta.validate(schema("{\"enum\":[]}")).isValid());
        assertFalse(meta.validate(schema("{\"enum\":\"a\"}")).isValid());
        assertTrue(meta.validate(schema("{\"enum\":[1,2]}")).isValid());
    }

    @Test
    public void test_const_any_value() {
        assertTrue(meta.validate(schema("{\"const\":{\"a\":1}}")).isValid());
    }

    @Test
    public void test_multiple_of_must_be_positive() {
        assertFalse(meta.validate(schema("{\"multipleOf\":0}")).isValid());
        assertFalse(meta.validate(schema("{\"multipleOf\":\"x\"}")).isValid());
        assertTrue(meta.validate(schema("{\"multipleOf\":2.5}")).isValid());
    }

    @Test
    public void test_numeric_bounds_must_be_numbers() {
        assertFalse(meta.validate(schema("{\"minimum\":\"x\"}")).isValid());
        assertTrue(meta
                .validate(schema("{\"minimum\":1,\"maximum\":2," + "\"exclusiveMinimum\":0,\"exclusiveMaximum\":3}"))
                .isValid());
    }

    @Test
    public void test_non_negative_integer_keywords() {
        assertFalse(meta.validate(schema("{\"minLength\":-1}")).isValid());
        assertFalse(meta.validate(schema("{\"maxItems\":1.5}")).isValid());
        assertFalse(meta.validate(schema("{\"minItems\":\"x\"}")).isValid());
        assertTrue(meta.validate(schema("{\"minLength\":0,\"maxLength\":5,"
                + "\"minItems\":1,\"maxItems\":2,\"minContains\":1,\"maxContains\":2,"
                + "\"minProperties\":1,\"maxProperties\":9}")).isValid());
    }

    @Test
    public void test_pattern_validation() {
        assertFalse(meta.validate(schema("{\"pattern\":1}")).isValid());
        assertFalse(meta.validate(schema("{\"pattern\":\"[\"}")).isValid());
        assertTrue(meta.validate(schema("{\"pattern\":\"^a+$\"}")).isValid());
    }

    @Test
    public void test_format_must_be_string() {
        assertFalse(meta.validate(schema("{\"format\":1}")).isValid());
        assertTrue(meta.validate(schema("{\"format\":\"date-time\"}")).isValid());
    }

    @Test
    public void test_unique_items_must_be_boolean() {
        assertFalse(meta.validate(schema("{\"uniqueItems\":\"yes\"}")).isValid());
        assertTrue(meta.validate(schema("{\"uniqueItems\":true}")).isValid());
    }

    @Test
    public void test_required_string_array() {
        assertFalse(meta.validate(schema("{\"required\":\"a\"}")).isValid());
        assertFalse(meta.validate(schema("{\"required\":[1]}")).isValid());
        assertFalse(meta.validate(schema("{\"required\":[\"a\",\"a\"]}")).isValid());
        assertTrue(meta.validate(schema("{\"required\":[\"a\",\"b\"]}")).isValid());
    }

    @Test
    public void test_dependent_required_shape() {
        assertFalse(meta.validate(schema("{\"dependentRequired\":[]}")).isValid());
        assertFalse(meta.validate(schema("{\"dependentRequired\":{\"a\":\"b\"}}")).isValid());
        assertTrue(meta.validate(schema("{\"dependentRequired\":{\"a\":[\"b\"]}}")).isValid());
    }

    @Test
    public void test_schema_map_keywords() {
        assertFalse(meta.validate(schema("{\"properties\":[]}")).isValid());
        assertFalse(meta.validate(schema("{\"properties\":{\"a\":1}}")).isValid());
        assertTrue(meta.validate(schema("{\"properties\":{\"a\":{\"type\":\"string\"}}}")).isValid());
        assertTrue(meta.validate(schema("{\"dependentSchemas\":{\"a\":true}}")).isValid());
    }

    @Test
    public void test_pattern_properties_shape() {
        assertFalse(meta.validate(schema("{\"patternProperties\":[]}")).isValid());
        assertFalse(meta.validate(schema("{\"patternProperties\":{\"[\":{}}}")).isValid());
        assertFalse(meta.validate(schema("{\"patternProperties\":{\"^a\":1}}")).isValid());
        assertTrue(meta.validate(schema("{\"patternProperties\":{\"^a\":{\"type\":\"string\"}}}")).isValid());
    }

    @Test
    public void test_single_schema_keywords() {
        assertFalse(meta.validate(schema("{\"additionalProperties\":1}")).isValid());
        assertTrue(meta.validate(schema("{\"additionalProperties\":false}")).isValid());
        assertTrue(meta.validate(schema("{\"propertyNames\":{\"minLength\":1},"
                + "\"items\":true,\"contains\":{},\"not\":{}," + "\"if\":{},\"then\":{},\"else\":{}}")).isValid());
    }

    @Test
    public void test_schema_array_keywords() {
        assertFalse(meta.validate(schema("{\"allOf\":{}}")).isValid());
        assertFalse(meta.validate(schema("{\"allOf\":[]}")).isValid());
        assertFalse(meta.validate(schema("{\"anyOf\":[1]}")).isValid());
        assertTrue(meta.validate(
                schema("{\"allOf\":[{\"type\":\"string\"}]," + "\"anyOf\":[true],\"oneOf\":[{}],\"prefixItems\":[{}]}"))
                .isValid());
    }

    @Test
    public void test_custom_type_validation() {
        assertFalse(meta.validate(schema("{\"customType\":1}")).isValid());
        assertFalse(meta.validate(schema("{\"customType\":\"nope\"}")).isValid());
        assertTrue(meta.validate(schema("{\"customType\":\"geo\"}")).isValid());
        assertTrue(meta.validate(schema("{\"customType\":\"vector\"}")).isValid());
        assertTrue(meta.validate(schema("{\"customType\":\"datetime\"}")).isValid());
        assertTrue(meta.validate(schema("{\"customType\":\"time\"}")).isValid());
    }

    @Test
    public void test_custom_type_does_not_warn() {
        assertTrue(meta.validate(schema("{\"customType\":\"geo\"}")).getWarnings().isEmpty());
    }

    @Test
    public void test_ref_must_be_string() {
        assertFalse(meta.validate(schema("{\"$ref\":1}")).isValid());
        assertTrue(meta.validate(schema("{\"$ref\":\"#/$defs/x\"}")).isValid());
    }

    @Test
    public void test_annotations_accepted() {
        final var result = meta.validate(schema("{\"$schema\":\"x\",\"$id\":\"y\",\"title\":\"t\","
                + "\"description\":\"d\",\"default\":5,\"examples\":[1],\"deprecated\":true}"));
        assertTrue(result.isValid());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    public void test_const_null_value() {
        assertTrue(meta.validate(schema("{\"const\":null}")).isValid());
    }
}
