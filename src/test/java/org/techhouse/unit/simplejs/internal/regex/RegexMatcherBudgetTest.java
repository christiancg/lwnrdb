package org.techhouse.unit.simplejs.internal.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.regex.RegexMatch;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;

public class RegexMatcherBudgetTest {
    private static final Duration GENEROUS = Duration.ofSeconds(20);

    private static final class BudgetSpent extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class CountingBudget implements RegexStepBudget {
        private final long limit;
        private long steps;

        private CountingBudget(long limit) {
            this.limit = limit;
        }

        @Override
        public void step() {
            steps++;
            if (steps > limit) {
                throw new BudgetSpent();
            }
        }
    }

    private static RegexMatch exec(String source, String input, RegexStepBudget budget) {
        return RegexMatcher.exec(RegexParser.compile(source, "u"), input, 0, false, budget);
    }

    private static long stepsFor(String source, String input) {
        final var budget = new CountingBudget(Long.MAX_VALUE);
        exec(source, input, budget);
        return budget.steps;
    }

    @Test
    public void test_unlimited_overload_matches_as_before() {
        final var program = RegexParser.compile("b(c+)d", "u");
        final var unlimited = RegexMatcher.exec(program, "abcccde", 0, false);
        final var budgeted = RegexMatcher.exec(program, "abcccde", 0, false, new CountingBudget(Long.MAX_VALUE));
        assertNotNull(unlimited);
        assertNotNull(budgeted);
        assertEquals(unlimited.start(), budgeted.start());
        assertEquals(unlimited.end(), budgeted.end());
        assertEquals(unlimited.group(1), budgeted.group(1));
        assertNull(RegexMatcher.exec(program, "abd", 0, false, RegexStepBudget.UNLIMITED));
    }

    @Test
    public void test_budget_exception_propagates_out_of_a_catastrophic_pattern() {
        final var budget = new CountingBudget(100_000);
        assertTimeoutPreemptively(GENEROUS,
                () -> assertThrows(BudgetSpent.class, () -> exec("(x+x+)+y", "x".repeat(60) + "!", budget)));
        assertEquals(100_001, budget.steps);
    }

    @Test
    public void test_step_count_is_deterministic() {
        final var input = "x".repeat(20) + "!";
        final var first = stepsFor("(x+x+)+y", input);
        assertTrue(first > 1000, "the catastrophic pattern must cost many steps, was " + first);
        assertEquals(first, stepsFor("(x+x+)+y", input));
    }

    @Test
    public void test_budget_is_charged_in_simple_quantifier_backtracking() {
        final var shortRun = stepsFor("a+a+a+b", "a".repeat(10));
        final var longRun = stepsFor("a+a+a+b", "a".repeat(40));
        assertTrue(longRun > shortRun * 64, shortRun + " steps for 10, " + longRun + " for 40");
        assertTimeoutPreemptively(GENEROUS, () -> assertThrows(BudgetSpent.class,
                () -> exec("a+a+a+b", "a".repeat(400), new CountingBudget(1_000_000))));
    }

    @Test
    public void test_lookaround_and_group_paths_charge() {
        final var plain = stepsFor("a", "a");
        assertTrue(stepsFor("(?=a)a", "a") > plain);
        assertTrue(stepsFor("(?<=x)a", "xa") > plain);
        assertTrue(stepsFor("(a)", "a") > plain);
        assertTrue(stepsFor("b|a", "a") > plain);
    }

    @Test
    public void test_every_start_position_costs_a_step() {
        assertTrue(stepsFor("z", "a".repeat(20)) - stepsFor("z", "a".repeat(10)) >= 10);
    }

    @Test
    public void test_surrogate_pairs_match_through_the_budgeted_exec() {
        final var match = exec("^.$", "😀", new CountingBudget(1000));
        assertNotNull(match);
        assertEquals(2, match.end());
    }
}
