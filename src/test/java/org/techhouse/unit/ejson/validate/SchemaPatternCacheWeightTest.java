package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.test.TestUtils;

public class SchemaPatternCacheWeightTest {
    private static final String CACHE_MAX_BYTES = "schemaPatternCacheMaxBytes";
    private static final String MAX_PATTERN_LENGTH = "schemaMaxPatternLength";
    private static final long SMALL_CACHE_BYTES = 4L * 1024 * 1024;
    private static final int HEAVY_REPEATS = 800;
    private static final int EVICTED_REPEATS = 200;
    private final EJson ejson = new EJson();
    private final Configuration configuration = Configuration.getInstance();

    @AfterEach
    void restoreDefaults() throws Exception {
        TestUtils.setPrivateField(configuration, CACHE_MAX_BYTES,
                ConfigKey.SCHEMA_PATTERN_CACHE_MAX_BYTES.defaultValue());
        TestUtils.setPrivateField(configuration, MAX_PATTERN_LENGTH,
                ConfigKey.SCHEMA_MAX_PATTERN_LENGTH.defaultValue());
    }

    private static Class<?> schemaPatterns() throws Exception {
        return Class.forName("org.techhouse.ejson.validate.SchemaPatterns");
    }

    private static Map<?, ?> compiledPatterns() throws Exception {
        return TestUtils.getPrivateStaticField(schemaPatterns(), "COMPILED", Map.class);
    }

    private static long cachedBytes() throws Exception {
        return TestUtils.getPrivateStaticField(schemaPatterns(), "cachedBytes", Long.class);
    }

    private static long weightOf(Object cached) throws Exception {
        final var accessor = cached.getClass().getDeclaredMethod("weight");
        accessor.setAccessible(true);
        return (long) accessor.invoke(cached);
    }

    private boolean matchesPattern(String pattern, String value) {
        final var schema = new JsonObject();
        schema.add("pattern", new JsonString(pattern));
        return ejson.validateWithSchema(new JsonString(value), schema).isValid();
    }

    private static String heavyPattern(int variant) {
        return "^v" + variant + "-" + "\\P{L}".repeat(EVICTED_REPEATS);
    }

    @Test
    public void test_heavy_patterns_are_evicted_by_weight() throws Exception {
        TestUtils.setPrivateField(configuration, CACHE_MAX_BYTES, SMALL_CACHE_BYTES);
        for (var variant = 0; variant < 40; variant++) {
            assertFalse(matchesPattern(heavyPattern(variant), "v" + variant + "-x"));
            assertTrue(cachedBytes() <= SMALL_CACHE_BYTES, "cached " + cachedBytes() + " bytes");
        }
        final var cache = compiledPatterns();
        assertTrue(cache.containsKey(heavyPattern(39)));
        assertFalse(cache.containsKey(heavyPattern(0)));
        var weights = 0L;
        for (final var cached : cache.values()) {
            weights += weightOf(cached);
        }
        assertEquals(weights, cachedBytes());
    }

    @Test
    public void test_estimated_bytes_follow_the_compiled_sets_and_literals() {
        final var tiny = RegexParser.compile("a", "u").estimatedBytes();
        final var heavy = RegexParser.compile("\\P{L}".repeat(HEAVY_REPEATS), "u").estimatedBytes();
        final var longLiteral = RegexParser.compile("a".repeat(10_000), "u").estimatedBytes();
        assertTrue(tiny > 0);
        assertTrue(heavy > 1000L * tiny, "heavy " + heavy + " vs tiny " + tiny);
        assertTrue(longLiteral >= 10_000, "a 10000-char literal estimates " + longLiteral);
    }

    @Test
    public void test_a_program_heavier_than_the_whole_cache_is_matched_but_not_admitted() throws Exception {
        TestUtils.setPrivateField(configuration, CACHE_MAX_BYTES, "1Kb");
        final var pattern = "^too-heavy-" + "\\P{L}".repeat(50);
        assertTrue(matchesPattern(pattern, "too-heavy-" + "1".repeat(50)));
        assertFalse(compiledPatterns().containsKey(pattern));
    }

    @Test
    public void test_a_pattern_over_the_length_cap_is_matched_but_not_admitted() throws Exception {
        TestUtils.setPrivateField(configuration, MAX_PATTERN_LENGTH, 8);
        final var pattern = "^over-the-length-cap$";
        assertTrue(matchesPattern(pattern, "over-the-length-cap"));
        assertFalse(compiledPatterns().containsKey(pattern));
        final var shortPattern = "^short$";
        assertTrue(matchesPattern(shortPattern, "short"));
        assertTrue(compiledPatterns().containsKey(shortPattern));
    }
}
