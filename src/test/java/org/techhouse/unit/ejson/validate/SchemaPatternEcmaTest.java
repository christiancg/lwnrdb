package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.InvalidSchemaException;
import org.techhouse.ejson.validate.MetaSchemaValidator;

public class SchemaPatternEcmaTest {
    private final EJson ejson = new EJson();
    private final MetaSchemaValidator meta = new MetaSchemaValidator();

    private JsonObject schema(String json) {
        return ejson.fromJson(json, JsonObject.class);
    }

    private JsonBaseElement val(String json) {
        return ejson.fromJson("{\"v\":" + json + "}", JsonObject.class).get("v");
    }

    private boolean ok(String schemaJson, String instanceJson) {
        return ejson.validateWithSchema(val(instanceJson), schema(schemaJson)).isValid();
    }

    private boolean matchesString(String pattern, String value) {
        final var patternSchema = new JsonObject();
        patternSchema.add("pattern", new JsonString(pattern));
        return ejson.validateWithSchema(new JsonString(value), patternSchema).isValid();
    }

    @Test
    public void test_dollar_does_not_match_before_a_trailing_newline() {
        assertTrue(ok("{\"pattern\":\"^[a-z]+$\"}", "\"abc\""));
        assertFalse(ok("{\"pattern\":\"^[a-z]+$\"}", "\"abc\\n\""),
                "ECMA-262 '$' without the m flag matches only at the end of input");
        assertFalse(ok("{\"pattern\":\"^[a-z]+$\"}", "\"abc\\r\\n\""));
    }

    @Test
    public void test_whitespace_class_is_unicode_aware() {
        assertFalse(matchesString("^\\S+$", "a\u00A0b"), "ECMA-262 \\s includes the no-break space");
        assertFalse(matchesString("^\\S+$", "a\u2028b"));
        assertTrue(matchesString("^\\s$", "\uFEFF"));
    }

    @Test
    public void test_pattern_is_an_unanchored_search() {
        assertTrue(ok("{\"pattern\":\"b\"}", "\"abc\""));
        assertFalse(ok("{\"pattern\":\"z\"}", "\"abc\""));
    }

    @Test
    public void test_unicode_property_escape_matches() {
        assertTrue(matchesString("^\\p{L}+$", "ñandú"));
        assertFalse(matchesString("^\\p{L}+$", "abc1"));
    }

    @Test
    public void test_astral_character_counts_as_one_with_unicode_mode() {
        assertTrue(matchesString("^.$", "\uD83D\uDE00"));
    }

    @Test
    public void test_empty_pattern_matches_everything() {
        assertTrue(ok("{\"pattern\":\"\"}", "\"anything\""));
    }

    @Test
    public void test_pattern_properties_use_ecma_semantics() {
        final var patternProperties = "{\"patternProperties\":{\"^[a-z]+$\":{\"type\":\"number\"}}}";
        assertFalse(ok(patternProperties, "{\"abc\":\"not a number\"}"));
        assertTrue(ok(patternProperties, "{\"abc\\n\":\"not a number\"}"),
                "a key with a trailing newline does not match '^[a-z]+$' in ECMA-262, so no subschema applies");
    }

    @Test
    public void test_property_names_pattern_uses_ecma_semantics() {
        final var propertyNames = "{\"propertyNames\":{\"pattern\":\"^[a-z]+$\"}}";
        assertTrue(ok(propertyNames, "{\"abc\":1}"));
        assertFalse(ok(propertyNames, "{\"abc\\n\":1}"));
    }

    @Test
    public void test_meta_validation_rejects_java_only_pattern_syntax() {
        assertFalse(meta.validate(schema("{\"pattern\":\"a++\"}")).isValid(), "possessive quantifiers are Java-only");
        assertFalse(meta.validate(schema("{\"pattern\":\"(?i)a\"}")).isValid(), "inline flags are Java-only");
        assertTrue(meta.validate(schema("{\"pattern\":\"^\\\\p{L}+$\"}")).isValid());
    }

    @Test
    public void test_meta_validation_rejects_java_only_pattern_properties_key() {
        assertFalse(meta.validate(schema("{\"patternProperties\":{\"a++\":{}}}")).isValid());
    }

    @Test
    public void test_an_uncompilable_stored_pattern_fails_closed_at_validation() {
        assertThrows(InvalidSchemaException.class, () -> matchesString("a++", "aaa"),
                "a pattern stored before the ECMA switch must refuse the write, never accept it unvalidated");
    }
}
