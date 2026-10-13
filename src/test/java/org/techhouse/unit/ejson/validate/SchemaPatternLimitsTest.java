package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.SchemaBudgetExceededException;
import org.techhouse.ejson.validate.SchemaBudget;
import org.techhouse.test.TestUtils;

public class SchemaPatternLimitsTest {
    private static final int CACHED_PATTERN_CAP = 4096;
    private final EJson ejson = new EJson();

    private JsonObject json(String text) {
        return ejson.fromJson(text, JsonObject.class);
    }

    private static Map<?, ?> compiledPatterns() throws Exception {
        return TestUtils.getPrivateStaticField(Class.forName("org.techhouse.ejson.validate.SchemaPatterns"), "COMPILED",
                Map.class);
    }

    private static long cachedCharacters() throws Exception {
        return TestUtils.getPrivateStaticField(Class.forName("org.techhouse.ejson.validate.SchemaPatterns"),
                "cachedChars", Long.class);
    }

    private boolean matchesPattern(String pattern, String value) {
        final var schema = new JsonObject();
        schema.add("pattern", new JsonString(pattern));
        return ejson.validateWithSchema(new JsonString(value), schema).isValid();
    }

    @Test
    public void test_the_longest_pattern_is_found_in_pattern_values_and_pattern_property_keys() {
        assertEquals(0, ejson.longestSchemaPattern(json("{\"type\":\"string\"}")));
        assertEquals(5, ejson.longestSchemaPattern(json("{\"pattern\":\"^abc$\"}")));
        assertEquals(7, ejson.longestSchemaPattern(
                json("{\"properties\":{\"a\":{\"pattern\":\"x\"}},\"patternProperties\":{\"^[a-z]$\":{}}}")));
    }

    @Test
    public void test_patterns_reached_only_through_defs_and_refs_are_measured() {
        assertEquals(9, ejson.longestSchemaPattern(
                json("{\"$defs\":{\"d\":{\"items\":{\"pattern\":\"123456789\"}}}," + "\"$ref\":\"#/$defs/d\"}")));
        assertEquals(4,
                ejson.longestSchemaPattern(json("{\"$ref\":\"#/const/x\",\"const\":{\"x\":{\"pattern\":\"abcd\"}}}")));
        assertEquals(3, ejson.longestSchemaPattern(json("{\"$ref\":\"#\",\"propertyNames\":{\"pattern\":\"abc\"}}")));
    }

    @Test
    public void test_keyword_lookalikes_in_data_positions_are_not_patterns() {
        assertEquals(0, ejson.longestSchemaPattern(json("{\"const\":{\"pattern\":\"not-a-schema-pattern\"},"
                + "\"enum\":[{\"pattern\":\"nor-this-one\"}],\"properties\":{\"pattern\":{\"type\":\"string\"}}}")));
    }

    @Test
    public void test_an_unresolvable_ref_is_skipped() {
        assertEquals(2, ejson.longestSchemaPattern(json("{\"$ref\":\"#/missing\",\"pattern\":\"ab\"}")));
    }

    @Test
    public void test_the_pattern_cache_evicts_and_does_not_recompile_forever() throws Exception {
        for (var i = 0; i < CACHED_PATTERN_CAP + 200; i++) {
            assertTrue(matchesPattern("^cache-evict-" + i + "$", "cache-evict-" + i));
        }
        final var cache = compiledPatterns();
        assertTrue(cache.size() <= CACHED_PATTERN_CAP, "cached " + cache.size());
        assertTrue(cache.containsKey("^cache-evict-" + (CACHED_PATTERN_CAP + 199) + "$"));
        final var lengths = cache.keySet().stream().mapToLong(key -> ((String) key).length()).sum();
        assertEquals(lengths, cachedCharacters());
    }

    @Test
    public void test_a_reused_pattern_is_served_from_the_cache() throws Exception {
        final var pattern = "^cache-reuse-[0-9]+$";
        assertTrue(matchesPattern(pattern, "cache-reuse-1"));
        final var first = compiledPatterns().get(pattern);
        assertTrue(matchesPattern(pattern, "cache-reuse-22"));
        assertSame(first, compiledPatterns().get(pattern));
    }

    @Test
    public void test_a_shared_budget_is_spent_across_documents() {
        final var schema = json("{\"type\":\"object\",\"properties\":{\"tags\":{\"items\":{\"type\":\"string\"}}}}");
        final var document = json("{\"tags\":[" + "\"t\",".repeat(299) + "\"t\"]}");
        final var shared = new SchemaBudget(1000);
        assertTrue(ejson.validateInstance(document, schema, shared).isValid());
        assertTrue(ejson.validateInstance(document, schema, shared).isValid());
        assertTrue(ejson.validateInstance(document, schema, shared).isValid());
        assertThrows(SchemaBudgetExceededException.class, () -> ejson.validateInstance(document, schema, shared));
        assertTrue(ejson.validateInstance(document, schema, 1000L).isValid());
    }

    @Test
    public void test_bulk_steps_are_refused_once_the_budget_cannot_cover_them() {
        final var budget = new SchemaBudget(10);
        budget.steps(10);
        assertThrows(SchemaBudgetExceededException.class, budget::step);
        final var partial = new SchemaBudget(5);
        assertThrows(SchemaBudgetExceededException.class, () -> partial.steps(6));
    }
}
