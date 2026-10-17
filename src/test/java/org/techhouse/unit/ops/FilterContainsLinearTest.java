package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;

public class FilterContainsLinearTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static boolean contains(String stored, String operand) {
        final var toTest = new JsonObject();
        toTest.add("field", new JsonString(stored));
        final var operator = new FieldOperator(FieldOperatorType.CONTAINS, "field", new JsonString(operand));
        final BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(operator,
                FieldOperatorType.CONTAINS);
        return tester.test(toTest, "field");
    }

    @Test
    public void test_contains_matches_substrings_and_rejects_others() {
        assertTrue(contains("hello world", "o w"));
        assertTrue(contains("hello", "hello"));
        assertTrue(contains("hello", ""));
        assertTrue(contains("ababab", "abab"));
        assertFalse(contains("hello", "World"));
        assertFalse(contains("short", "shorter"));
    }

    @Test
    public void test_contains_is_case_sensitive() {
        assertFalse(contains("Hello", "hello"));
        assertTrue(contains("Hello", "Hell"));
    }

    @Test
    public void test_contains_on_adversarial_operand_stays_linear() {
        final var stored = "a".repeat(200_000);
        final var operand = "a".repeat(100_000) + "b";
        final var start = System.nanoTime();
        final var matched = contains(stored, operand);
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse(matched);
        assertTrue(elapsedMs < 2000, "contains took " + elapsedMs + "ms");
    }
}
