package org.techhouse.unit.simplejs.internal.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.internal.regex.RegexParser;
import org.techhouse.simplejs.internal.regex.RegexProgram;
import org.techhouse.simplejs.internal.regex.RegexStepBudget;

public class RegexMatcherMatchesTest {
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

    private static final class ExhaustedBudget extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static RegexProgram compile(String[] pattern) {
        return RegexParser.compile(pattern[0], pattern[1]);
    }

    private static int compareEnds(String pattern, RegexProgram program, String input) {
        var compared = 0;
        for (var from = 0; from <= input.length() + 1; from++) {
            for (final var sticky : new boolean[]{false, true}) {
                final var match = RegexMatcher.exec(program, input, from, sticky);
                final var end = RegexMatcher.matchEnd(program, input, from, sticky, RegexStepBudget.UNLIMITED);
                assertEquals(match == null ? -1 : match.end(), end,
                        pattern + " on " + input + " from " + from + " sticky " + sticky);
                compared++;
            }
        }
        return compared;
    }

    private static void compareSteps(String pattern, RegexProgram program, String input) {
        for (var from = 0; from <= input.length(); from++) {
            for (final var sticky : new boolean[]{false, true}) {
                final var execBudget = new CountingBudget();
                final var testBudget = new CountingBudget();
                RegexMatcher.exec(program, input, from, sticky, execBudget);
                RegexMatcher.matchEnd(program, input, from, sticky, testBudget);
                assertEquals(execBudget.steps, testBudget.steps,
                        pattern + " on " + input + " from " + from + " sticky " + sticky);
            }
        }
        final var execBudget = new CountingBudget();
        final var matchBudget = new CountingBudget();
        RegexMatcher.exec(program, input, 0, false, execBudget);
        RegexMatcher.matches(program, input, matchBudget);
        assertEquals(execBudget.steps, matchBudget.steps, pattern + " on " + input);
    }

    @Test
    public void test_matches_and_match_end_agree_with_exec_on_the_corpus() {
        var compared = 0;
        for (final var pattern : RegexCorpus.PATTERNS) {
            final var program = compile(pattern);
            for (final var input : RegexCorpus.INPUTS) {
                assertEquals(RegexMatcher.exec(program, input, 0, false) != null,
                        RegexMatcher.matches(program, input, RegexStepBudget.UNLIMITED), pattern[0] + " on " + input);
                compared += compareEnds(pattern[0], program, input);
            }
        }
        assertTrue(compared > 5000, "compared " + compared);
    }

    @Test
    public void test_the_boolean_match_charges_exactly_the_steps_exec_charges() {
        for (final var pattern : RegexCorpus.PATTERNS) {
            final var program = compile(pattern);
            for (final var input : RegexCorpus.INPUTS) {
                compareSteps(pattern[0], program, input);
            }
        }
    }

    @Test
    public void test_an_exhausted_budget_stops_the_boolean_match() {
        final var program = RegexParser.compile("(x+x+)+y", "");
        final var input = "x".repeat(200) + "!";
        final var allowed = new long[]{10_000};
        final RegexStepBudget budget = () -> {
            if (--allowed[0] < 0) {
                throw new ExhaustedBudget();
            }
        };
        assertThrows(ExhaustedBudget.class, () -> RegexMatcher.matches(program, input, budget));
    }

    @Test
    public void test_a_capture_reset_between_start_positions_still_applies() {
        final var program = RegexParser.compile("(a)?b\\1", "");
        assertTrue(RegexMatcher.matches(program, "xb", RegexStepBudget.UNLIMITED));
        assertEquals(3, RegexMatcher.matchEnd(program, "aba", 0, false, RegexStepBudget.UNLIMITED));
        assertFalse(RegexMatcher.matches(RegexParser.compile("^a$", ""), "ba", RegexStepBudget.UNLIMITED));
        assertEquals(-1, RegexMatcher.matchEnd(RegexParser.compile("a", ""), "ba", 0, true, RegexStepBudget.UNLIMITED));
        assertSame(null, RegexMatcher.exec(RegexParser.compile("a", ""), "ba", 0, true));
    }
}
