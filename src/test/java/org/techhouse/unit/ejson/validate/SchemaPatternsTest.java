package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.validate.SchemaBudget;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;
import org.techhouse.test.TestUtils;
import org.techhouse.unit.simplejs.internal.regex.RegexCorpus;

public class SchemaPatternsTest {
    private static final String CACHE_MAX_BYTES = "schemaPatternCacheMaxBytes";
    private static final String UNICODE = "u";
    private static final int VALIDATORS = 16;
    private static final int ROUNDS = 400;
    private final EJson ejson = new EJson();
    private final Configuration configuration = Configuration.getInstance();

    @AfterEach
    void restoreDefaults() throws Exception {
        TestUtils.setPrivateField(configuration, CACHE_MAX_BYTES,
                ConfigKey.SCHEMA_PATTERN_CACHE_MAX_BYTES.defaultValue());
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

    private static long remaining(SchemaBudget budget) throws Exception {
        final var field = SchemaBudget.class.getDeclaredField("remaining");
        field.setAccessible(true);
        return field.getLong(budget);
    }

    private static JsonObject patternSchema(String pattern) {
        final var schema = new JsonObject();
        schema.add("pattern", new JsonString(pattern));
        return schema;
    }

    private boolean matchesPattern(String pattern, String value) {
        return ejson.validateWithSchema(new JsonString(value), patternSchema(pattern)).isValid();
    }

    private static boolean compilesInUnicodeMode(String pattern) {
        try {
            RegexParser.compile(pattern, UNICODE);
            return true;
        } catch (SyntaxErrorException | IllegalStateException e) {
            return false;
        }
    }

    private static final class CountingBudget implements RegexStepBudget {
        private long steps;

        @Override
        public void step() {
            steps++;
        }

        @Override
        public void steps(int count) {
            steps += count;
        }
    }

    @Test
    public void test_a_pattern_matches_exactly_when_exec_finds_a_match() {
        var compared = 0;
        for (final var pattern : RegexCorpus.PATTERNS) {
            if (!compilesInUnicodeMode(pattern[0])) {
                continue;
            }
            final var program = RegexParser.compile(pattern[0], UNICODE);
            for (final var input : RegexCorpus.INPUTS) {
                assertEquals(RegexMatcher.exec(program, input, 0, false) != null, matchesPattern(pattern[0], input),
                        pattern[0] + " on " + input);
                compared++;
            }
        }
        assertTrue(compared > 300, "compared " + compared);
    }

    @Test
    public void test_the_schema_budget_is_charged_the_steps_exec_would_charge() throws Exception {
        for (final var pattern : List.of("^[a-z]+[0-9]*$", "(x+x+)+y",
                "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            for (final var input : List.of("user42", "xxxxxxxxxxxx!", "someone@example.com", "")) {
                final var counted = new CountingBudget();
                RegexMatcher.exec(RegexParser.compile(pattern, UNICODE), input, 0, false, counted);
                final var budget = new SchemaBudget(1_000_000);
                ejson.validateInstance(new JsonString(input), patternSchema(pattern), budget);
                assertEquals(1 + counted.steps, 1_000_000 - remaining(budget), pattern + " on " + input);
            }
        }
    }

    @Test
    public void test_a_recently_used_pattern_outlives_older_ones() throws Exception {
        final var patterns = new ArrayList<String>();
        for (var i = 0; i < 4; i++) {
            patterns.add("^lru-recency-" + i + "-[a-z]+$");
        }
        for (var i = 0; i < 3; i++) {
            assertTrue(matchesPattern(patterns.get(i), "lru-recency-" + i + "-abc"));
        }
        final var weight = weightOf(compiledPatterns().get(patterns.get(0)));
        assertEquals(weight, weightOf(compiledPatterns().get(patterns.get(1))));
        assertEquals(weight, weightOf(compiledPatterns().get(patterns.get(2))));
        assertTrue(matchesPattern(patterns.get(0), "lru-recency-0-again"));
        TestUtils.setPrivateField(configuration, CACHE_MAX_BYTES, 3 * weight + weight / 2);
        assertTrue(matchesPattern(patterns.get(3), "lru-recency-3-abc"));
        final var cache = compiledPatterns();
        assertTrue(cache.containsKey(patterns.get(0)));
        assertFalse(cache.containsKey(patterns.get(1)));
        assertTrue(cache.containsKey(patterns.get(2)));
        assertTrue(cache.containsKey(patterns.get(3)));
        assertTrue(cachedBytes() <= 3 * weight + weight / 2);
    }

    @Test
    public void test_sixteen_concurrent_validators_agree_and_keep_the_cache_consistent() throws Exception {
        TestUtils.setPrivateField(configuration, CACHE_MAX_BYTES, "64Kb");
        final var schema = ejson.fromJson("{\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\",\"pattern\":\"^[a-z]+[0-9]*$\"},"
                + "\"email\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$\"},"
                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"^t[0-9]+$\"}}},"
                + "\"patternProperties\":{\"^x-[a-z]+$\":{\"type\":\"number\"}}}", JsonObject.class);
        final var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(VALIDATORS)) {
            final var results = new ArrayList<Future<Integer>>();
            for (var t = 0; t < VALIDATORS; t++) {
                final var thread = t;
                results.add(pool.submit(() -> {
                    start.await();
                    var mismatches = 0;
                    for (var i = 0; i < ROUNDS; i++) {
                        final var valid = (i + thread) % 3 != 0;
                        final var document = ejson.fromJson("{\"name\":\"" + (valid ? "user" + i : "User " + i)
                                + "\",\"email\":\"u" + i + "@example.com\",\"tags\":[\"t1\",\"t" + thread + "\"],"
                                + "\"x-extra\":" + i + "}", JsonObject.class);
                        if (ejson.validateInstance(document, schema, new SchemaBudget(5_000_000)).isValid() != valid) {
                            mismatches++;
                        }
                        final var own = "^own-" + thread + "-" + (i % 50) + "-[0-9]+$";
                        if (!matchesPattern(own, "own-" + thread + "-" + (i % 50) + "-7")
                                || matchesPattern(own, "own-" + thread + "-x")) {
                            mismatches++;
                        }
                    }
                    return mismatches;
                }));
            }
            start.countDown();
            for (final var result : results) {
                assertEquals(0, result.get(60, TimeUnit.SECONDS));
            }
        }
        final var cache = compiledPatterns();
        var weights = 0L;
        for (final var cached : cache.values()) {
            weights += weightOf(cached);
        }
        assertEquals(weights, cachedBytes());
        assertTrue(cachedBytes() <= 64 * 1024, "cached " + cachedBytes());
        assertTrue(cache.size() <= 4096);
    }
}
