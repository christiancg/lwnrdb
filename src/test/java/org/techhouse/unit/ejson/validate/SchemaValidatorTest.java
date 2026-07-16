package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.validate.JsonSchema;
import org.techhouse.ejson.validate.SchemaValidator;

public class SchemaValidatorTest {
    private final EJson ejson = new EJson();
    private final SchemaValidator validator = new SchemaValidator();

    private JsonObject schema(String json) {
        return ejson.fromJson(json, JsonObject.class);
    }

    private JsonBaseElement val(String json) {
        return ejson.fromJson("{\"v\":" + json + "}", JsonObject.class).get("v");
    }

    private boolean ok(String schemaJson, String instanceJson) {
        return ejson.validateWithSchema(val(instanceJson), schema(schemaJson)).isValid();
    }

    // type: string
    @Test
    public void test_type_string() {
        assertTrue(ok("{\"type\":\"string\"}", "\"hi\""));
        assertFalse(ok("{\"type\":\"string\"}", "5"));
    }

    // type: integer distinguishes whole numbers from fractional
    @Test
    public void test_type_integer_vs_number() {
        assertTrue(ok("{\"type\":\"integer\"}", "5"));
        assertFalse(ok("{\"type\":\"integer\"}", "5.5"));
        assertTrue(ok("{\"type\":\"number\"}", "5.5"));
    }

    // type: array of names matches any listed
    @Test
    public void test_type_union() {
        assertTrue(ok("{\"type\":[\"string\",\"null\"]}", "\"x\""));
        assertTrue(ok("{\"type\":[\"string\",\"null\"]}", "null"));
        assertFalse(ok("{\"type\":[\"string\",\"null\"]}", "5"));
    }

    // type: boolean and null
    @Test
    public void test_type_boolean_and_null() {
        assertTrue(ok("{\"type\":\"boolean\"}", "true"));
        assertFalse(ok("{\"type\":\"boolean\"}", "\"true\""));
        assertTrue(ok("{\"type\":\"null\"}", "null"));
    }

    // enum membership
    @Test
    public void test_enum() {
        assertTrue(ok("{\"enum\":[\"a\",\"b\"]}", "\"a\""));
        assertFalse(ok("{\"enum\":[\"a\",\"b\"]}", "\"c\""));
    }

    // const equality
    @Test
    public void test_const() {
        assertTrue(ok("{\"const\":42}", "42"));
        assertFalse(ok("{\"const\":42}", "43"));
    }

    // required properties
    @Test
    public void test_required() {
        assertTrue(ok("{\"required\":[\"name\"]}", "{\"name\":\"x\"}"));
        assertFalse(ok("{\"required\":[\"name\"]}", "{\"age\":1}"));
    }

    // properties validate nested values
    @Test
    public void test_properties_nested() {
        final var s = "{\"properties\":{\"age\":{\"type\":\"integer\",\"minimum\":0}}}";
        assertTrue(ok(s, "{\"age\":10}"));
        assertFalse(ok(s, "{\"age\":-1}"));
        assertFalse(ok(s, "{\"age\":\"x\"}"));
    }

    // additionalProperties:false rejects unknown keys
    @Test
    public void test_additional_properties_false() {
        final var s = "{\"properties\":{\"a\":{}},\"additionalProperties\":false}";
        assertTrue(ok(s, "{\"a\":1}"));
        assertFalse(ok(s, "{\"a\":1,\"b\":2}"));
    }

    // additionalProperties as a schema validates the extra keys
    @Test
    public void test_additional_properties_schema() {
        final var s = "{\"properties\":{\"a\":{}},\"additionalProperties\":{\"type\":\"string\"}}";
        assertTrue(ok(s, "{\"a\":1,\"b\":\"x\"}"));
        assertFalse(ok(s, "{\"a\":1,\"b\":2}"));
    }

    // patternProperties applies schemas to matching keys
    @Test
    public void test_pattern_properties() {
        final var s = "{\"patternProperties\":{\"^s_\":{\"type\":\"string\"}}}";
        assertTrue(ok(s, "{\"s_name\":\"x\"}"));
        assertFalse(ok(s, "{\"s_name\":5}"));
    }

    // propertyNames constrains the keys themselves
    @Test
    public void test_property_names() {
        final var s = "{\"propertyNames\":{\"pattern\":\"^[a-z]+$\"}}";
        assertTrue(ok(s, "{\"abc\":1}"));
        assertFalse(ok(s, "{\"AbC\":1}"));
    }

    // min/maxProperties count constraints
    @Test
    public void test_property_counts() {
        assertFalse(ok("{\"minProperties\":2}", "{\"a\":1}"));
        assertTrue(ok("{\"minProperties\":2}", "{\"a\":1,\"b\":2}"));
        assertFalse(ok("{\"maxProperties\":1}", "{\"a\":1,\"b\":2}"));
    }

    // dependentRequired triggers only when the trigger key is present
    @Test
    public void test_dependent_required() {
        final var s = "{\"dependentRequired\":{\"credit_card\":[\"billing_address\"]}}";
        assertTrue(ok(s, "{\"name\":\"x\"}"));
        assertFalse(ok(s, "{\"credit_card\":\"1\"}"));
        assertTrue(ok(s, "{\"credit_card\":\"1\",\"billing_address\":\"a\"}"));
    }

    // dependentSchemas applies a schema when the trigger key is present
    @Test
    public void test_dependent_schemas() {
        final var s = "{\"dependentSchemas\":{\"a\":{\"required\":[\"b\"]}}}";
        assertTrue(ok(s, "{\"x\":1}"));
        assertFalse(ok(s, "{\"a\":1}"));
        assertTrue(ok(s, "{\"a\":1,\"b\":2}"));
    }

    // a false boolean subschema rejects any value at that location
    @Test
    public void test_boolean_false_subschema() {
        final var s = "{\"properties\":{\"a\":false}}";
        assertTrue(ok(s, "{}"));
        assertFalse(ok(s, "{\"a\":1}"));
    }

    // array item count
    @Test
    public void test_array_item_counts() {
        assertFalse(ok("{\"minItems\":2}", "[1]"));
        assertTrue(ok("{\"minItems\":2}", "[1,2]"));
        assertFalse(ok("{\"maxItems\":1}", "[1,2]"));
    }

    // uniqueItems
    @Test
    public void test_unique_items() {
        assertFalse(ok("{\"uniqueItems\":true}", "[1,1]"));
        assertTrue(ok("{\"uniqueItems\":true}", "[1,2,3]"));
        assertTrue(ok("{\"uniqueItems\":false}", "[1,1]"));
    }

    // prefixItems validate positionally, items validate the rest
    @Test
    public void test_prefix_items_and_items() {
        final var s = "{\"prefixItems\":[{\"type\":\"string\"}],\"items\":{\"type\":\"number\"}}";
        assertTrue(ok(s, "[\"a\",1,2]"));
        assertFalse(ok(s, "[\"a\",\"b\"]"));
        assertFalse(ok(s, "[1,2]"));
    }

    // contains with min/maxContains
    @Test
    public void test_contains() {
        assertTrue(ok("{\"contains\":{\"type\":\"number\"}}", "[\"a\",1]"));
        assertFalse(ok("{\"contains\":{\"type\":\"number\"}}", "[\"a\",\"b\"]"));
        assertFalse(ok("{\"contains\":{\"type\":\"number\"},\"minContains\":2}", "[1,\"a\"]"));
        assertFalse(ok("{\"contains\":{\"type\":\"number\"},\"maxContains\":1}", "[1,2]"));
    }

    // string length and pattern
    @Test
    public void test_string_constraints() {
        assertFalse(ok("{\"minLength\":2}", "\"a\""));
        assertTrue(ok("{\"minLength\":2}", "\"ab\""));
        assertFalse(ok("{\"maxLength\":2}", "\"abc\""));
        assertTrue(ok("{\"pattern\":\"^a\"}", "\"abc\""));
        assertFalse(ok("{\"pattern\":\"^a\"}", "\"xyz\""));
    }

    // numeric bounds and multipleOf
    @Test
    public void test_number_constraints() {
        assertFalse(ok("{\"minimum\":10}", "5"));
        assertTrue(ok("{\"minimum\":10}", "10"));
        assertFalse(ok("{\"maximum\":10}", "11"));
        assertFalse(ok("{\"exclusiveMinimum\":10}", "10"));
        assertFalse(ok("{\"exclusiveMaximum\":10}", "10"));
        assertTrue(ok("{\"multipleOf\":2}", "10"));
        assertFalse(ok("{\"multipleOf\":2}", "7"));
    }

    // allOf: all subschemas must hold
    @Test
    public void test_all_of() {
        final var s = "{\"allOf\":[{\"type\":\"number\"},{\"minimum\":0}]}";
        assertTrue(ok(s, "5"));
        assertFalse(ok(s, "-1"));
    }

    // anyOf: at least one subschema must hold
    @Test
    public void test_any_of() {
        final var s = "{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}";
        assertTrue(ok(s, "\"x\""));
        assertTrue(ok(s, "5"));
        assertFalse(ok(s, "true"));
    }

    // oneOf: exactly one subschema must hold
    @Test
    public void test_one_of() {
        assertTrue(ok("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}", "\"x\""));
        assertFalse(ok("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"number\"}]}", "true"));
        assertFalse(ok("{\"oneOf\":[{\"minimum\":0},{\"maximum\":10}]}", "5"));
    }

    // not: the subschema must fail
    @Test
    public void test_not() {
        assertTrue(ok("{\"not\":{\"type\":\"string\"}}", "5"));
        assertFalse(ok("{\"not\":{\"type\":\"string\"}}", "\"x\""));
    }

    // if/then/else across all branches
    @Test
    public void test_if_then_else() {
        final var s = "{\"if\":{\"type\":\"number\"},\"then\":{\"minimum\":10},\"else\":{\"type\":\"string\"}}";
        assertFalse(ok(s, "5"));
        assertTrue(ok(s, "20"));
        assertTrue(ok(s, "\"x\""));
        assertFalse(ok(s, "true"));
    }

    // customType enforces a specific EJson custom type
    @Test
    public void test_custom_type_geo() {
        assertTrue(ok("{\"customType\":\"geo\"}", "\"#geo(40.7,-74.0)\""));
        assertFalse(ok("{\"customType\":\"geo\"}", "\"hello\""));
        assertFalse(ok("{\"customType\":\"geo\"}", "5"));
    }

    // customType distinguishes between different custom types
    @Test
    public void test_custom_type_vector_vs_geo() {
        assertTrue(ok("{\"customType\":\"vector\"}", "\"#vector(0.1,0.2,0.3)\""));
        assertFalse(ok("{\"customType\":\"vector\"}", "\"#geo(40.7,-74.0)\""));
    }

    // customType works nested inside properties
    @Test
    public void test_custom_type_in_properties() {
        final var s = "{\"properties\":{\"loc\":{\"customType\":\"geo\"}},\"required\":[\"loc\"]}";
        assertTrue(ok(s, "{\"loc\":\"#geo(1.0,2.0)\"}"));
        assertFalse(ok(s, "{\"loc\":\"downtown\"}"));
    }

    // a custom value still satisfies type:string (custom types are strings)
    @Test
    public void test_custom_value_is_a_string() {
        assertTrue(ok("{\"type\":\"string\"}", "\"#geo(1.0,2.0)\""));
    }

    // local $ref resolves against $defs
    @Test
    public void test_ref_resolution() {
        final var s = "{\"$defs\":{\"pos\":{\"type\":\"number\",\"minimum\":0}},"
                + "\"properties\":{\"n\":{\"$ref\":\"#/$defs/pos\"}}}";
        assertTrue(ok(s, "{\"n\":5}"));
        assertFalse(ok(s, "{\"n\":-1}"));
    }

    // multiple violations accumulate multiple error messages
    @Test
    public void test_multiple_errors_accumulate() {
        final var result = ejson.validateWithSchema(val("{\"age\":-1}"),
                schema("{\"required\":[\"name\"],\"properties\":{\"age\":{\"minimum\":0}}}"));
        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().size());
    }

    // --- direct SchemaValidator paths not reachable through the meta-validating facade ---

    // a null root schema accepts anything
    @Test
    public void test_direct_null_root_schema() {
        assertTrue(validator.validate(new JsonNumber(1), new JsonSchema(null)).isValid());
    }

    // a boolean root schema accepts (true) or rejects (false) everything
    @Test
    public void test_direct_boolean_root_schema() {
        assertTrue(validator.validate(new JsonNumber(1), new JsonSchema(new JsonBoolean(true))).isValid());
        assertFalse(validator.validate(new JsonNumber(1), new JsonSchema(new JsonBoolean(false))).isValid());
    }

    // a non-object, non-boolean root schema is treated as always valid
    @Test
    public void test_direct_non_object_root_schema() {
        assertTrue(validator.validate(new JsonString("x"), new JsonSchema(new JsonNumber(1))).isValid());
    }

    // an unknown type name never matches
    @Test
    public void test_direct_unknown_type_name() {
        final var s = new JsonObject();
        s.add("type", new JsonString("strong"));
        assertFalse(validator.validate(new JsonString("x"), new JsonSchema(s)).isValid());
    }
}
