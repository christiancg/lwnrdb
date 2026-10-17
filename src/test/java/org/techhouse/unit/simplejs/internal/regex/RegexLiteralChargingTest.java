package org.techhouse.unit.simplejs.internal.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.SchemaBudgetExceededException;
import org.techhouse.ejson.validate.SchemaBudget;
import org.techhouse.simplejs.internal.regex.RegexMatch;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;

public class RegexLiteralChargingTest {
    private static final Duration QUICKLY = Duration.ofSeconds(5);
    private static final long SCHEMA_BUDGET = 5_000_000;
    private static final int COMPARED_CHARS_PER_STEP = 64;

    private static final class CountingBudget implements RegexStepBudget {
        private long steps;

        @Override
        public void step() {
            steps++;
        }
    }

    private static RegexMatch exec(String source, String flags, String input, RegexStepBudget budget) {
        return RegexMatcher.exec(RegexParser.compile(source, flags), input, 0, false, budget);
    }

    private static long stepsFor(String source, String input) {
        final var budget = new CountingBudget();
        assertNotNull(exec(source, "u", input, budget));
        return budget.steps;
    }

    @Test
    public void test_a_long_literal_after_a_star_exhausts_the_budget_quickly() {
        final var pattern = "a*" + "a".repeat(4000) + "b";
        final var input = "a".repeat(20_000);
        assertTimeoutPreemptively(QUICKLY, () -> assertThrows(SchemaBudgetExceededException.class,
                () -> exec(pattern, "u", input, new SchemaBudget(SCHEMA_BUDGET))));
    }

    @Test
    public void test_backreference_compares_are_charged() {
        final var input = "a".repeat(100_000);
        assertTimeoutPreemptively(QUICKLY, () -> assertThrows(SchemaBudgetExceededException.class,
                () -> exec("(a*)\\1b", "u", input, new SchemaBudget(SCHEMA_BUDGET))));
    }

    @Test
    public void test_a_literal_is_charged_by_its_length() {
        final var length = 6400;
        final var text = "a".repeat(length);
        assertTrue(stepsFor(text, text) >= length / COMPARED_CHARS_PER_STEP,
                "steps for a " + length + "-char literal: " + stepsFor(text, text));
    }

    @Test
    public void test_a_backreference_is_charged_by_the_captured_length() {
        final var length = 6400;
        final var steps = stepsFor("^(a{" + length + "})\\1$", "a".repeat(2 * length));
        assertTrue(steps >= 2L * length / COMPARED_CHARS_PER_STEP, "steps: " + steps);
    }

    @Test
    public void test_every_simple_term_of_a_sequence_costs_a_step() {
        final var shortSequence = stepsFor("^a.b$", "axb");
        final var longSequence = stepsFor("^a.b.c.d.e.f$", "axbxcxdxexf");
        assertTrue(longSequence - shortSequence >= 8, shortSequence + " vs " + longSequence);
    }

    @Test
    public void test_case_insensitive_backreference_still_matches() {
        final var match = exec("(ab)\\1", "i", "xabABy", RegexStepBudget.UNLIMITED);
        assertNotNull(match);
        assertEquals(1, match.start());
        assertEquals(5, match.end());
        assertNull(exec("(ab)\\1", "", "abAB", RegexStepBudget.UNLIMITED));
        assertNotNull(exec("(k)\\1", "iu", "kK", RegexStepBudget.UNLIMITED));
        assertNull(exec("(k)\\1", "i", "kK", RegexStepBudget.UNLIMITED));
    }

    @Test
    public void test_backward_literals_and_backreferences_compare_in_place() {
        assertNotNull(exec("(?<=abc)d", "", "abcd", RegexStepBudget.UNLIMITED));
        assertNull(exec("(?<=abc)d", "", "abxd", RegexStepBudget.UNLIMITED));
        assertNotNull(exec("(?<=ABC)d", "i", "abcd", RegexStepBudget.UNLIMITED));
        assertNotNull(exec("(?<=\\1(ab))c", "", "ababc", RegexStepBudget.UNLIMITED));
        assertNull(exec("(?<=abc)d", "", "cd", RegexStepBudget.UNLIMITED));
    }
}
