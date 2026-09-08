package org.techhouse.unit.simplejs.internal.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.regex.RegexMatcher;

// Closes coverage gaps in internal/regex left by RegexTranslatorTest/RegexTranslatorVFlagTest/
// RegexBuiltinsTest, which exercise the engine mostly through commonly-used constructs. Each test
// here targets one specific branch identified from the jacoco line-coverage report.
class RegexMatcherCoverageTest {
    private static boolean matches(String source, String flags, String input) {
        return RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), input, 0, false) != null;
    }

    private static boolean fullMatch(String source, String flags, String input) {
        final var match = RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), input, 0, false);
        return match != null && match.start() == 0 && match.end() == input.length();
    }

    // A simple quantifier whose atom matches zero-width once min is already satisfied must stop
    // instead of looping forever (RegexMatcher.matchSimpleQuantifier's zero-progress guard): an
    // unset backreference always matches empty, so quantifying one when its group didn't
    // participate is a zero-width repetition.
    @Test
    void simpleQuantifierStopsOnZeroWidthProgressPastMin() {
        assertTrue(fullMatch("(a)?\\1*b", "", "b"));
    }

    // Every candidate repetition count for a greedy simple quantifier can fail the remainder of the
    // pattern, exhausting the fallback loop.
    @Test
    void simpleQuantifierExhaustsAllCountsAndFails() {
        assertFalse(fullMatch("^a+$", "", "aab"));
    }

    // A quantified non-simple atom (a real capturing group, not foldable to the iterative fast
    // path) with ambiguous-length alternatives forces repeatMatcher to revisit the same (position,
    // min, max) from a different iteration count, hitting the memoized-failure short-circuit.
    @Test
    void repeatMatcherMemoizesAFailedPosition() {
        assertFalse(fullMatch("^(?:a|aa)+z$", "", "aaaa"));
    }

    // A lazy quantifier over a non-simple atom (a real group) that succeeds with zero repetitions
    // immediately (repeatMatcher's `!greedy` branch, k.run(pos) succeeding on the first try).
    @Test
    void lazyQuantifierOverGroupSucceedsWithZeroReps() {
        assertTrue(fullMatch("(a)??b", "", "b"));
    }

    // Same lazy quantifier, but the zero-repetition attempt fails the remainder of the pattern, so
    // it must fall through to actually matching the group once.
    @Test
    void lazyQuantifierOverGroupFallsBackToOneRep() {
        assertTrue(fullMatch("(a)??ba", "", "aba"));
    }

    // A lookbehind under the u flag walks backward across a well-formed surrogate pair as one code
    // point, exercising consumeCharClass's reverse-direction combining branch.
    @Test
    void lookbehindUnicodeCombinesSurrogatePairBackward() {
        final var astral = new String(Character.toChars(0x1D306));
        assertTrue(matches("(?<=.)x", "u", astral + "x"));
    }

    // A v-mode multi-code-point string alternative that only partially matches at the end of input
    // (Literal's forward bounds check failing).
    @Test
    void literalStringAlternativeFailsWhenInputTooShort() {
        assertFalse(fullMatch("[\\q{ab}]", "v", "a"));
    }

    // A backreference compared backward inside a lookbehind, both succeeding (enough room) and
    // failing (not enough room) - Literal/backreference's reverse-direction bounds check and match.
    @Test
    void backreferenceInsideLookbehindBackward() {
        assertTrue(matches("(a)(?<=\\1)", "", "aa"));
        assertFalse(matches("^(a)(?<=\\1\\1)$", "", "aa"));
    }

    // Case-insensitive backreference comparison, both a fold-equivalent match and a real mismatch.
    @Test
    void ignoreCaseBackreferenceComparison() {
        assertTrue(matches("(a)\\1", "i", "aA"));
        assertFalse(matches("^(a)\\1$", "i", "ab"));
    }

    // Atom-position \D/\f/\n/\r/\t escapes, individually - RegexTranslatorTest exercises \d/\w/\s
    // but not their less common siblings at the atom position.
    @Test
    void atomEscapesForDigitNegationAndControlChars() {
        assertTrue(fullMatch("\\D", "", "x"));
        assertFalse(fullMatch("\\D", "", "5"));
        assertTrue(fullMatch("\\f", "", "\f"));
        assertTrue(fullMatch("\\n", "", "\n"));
        assertTrue(fullMatch("\\r", "", "\r"));
        assertTrue(fullMatch("\\t", "", "\t"));
    }

    // \D/\W/\S inside a character class (the "already folded, negated at the escape itself" path).
    @Test
    void classEscapesForNegatedShorthands() {
        assertTrue(fullMatch("[\\D]", "", "x"));
        assertFalse(fullMatch("[\\D]", "", "5"));
        assertTrue(fullMatch("[\\W]", "", "!"));
        assertFalse(fullMatch("[\\W]", "", "a"));
        assertTrue(fullMatch("[\\s]", "", " "));
    }

    // A numeric escape inside a class is always an Annex B octal/legacy digit escape (here, octal
    // \1 = control character 0x01) dispatched through readClassDigitEscape, never a backreference
    // to group 1's captured text.
    @Test
    void classPositionDigitEscapeIsNeverABackreference() {
        assertTrue(fullMatch("(a)[\\1]", "", "a" + (char) 1));
    }

    // A plain pair of 4-hex unicode escapes at the atom position combines into one astral literal
    // when the second half is a well-formed low surrogate.
    @Test
    void adjacentUnicodeEscapesCombineIntoOneAstralLiteral() {
        final var astral = new String(Character.toChars(0x1D306));
        assertTrue(fullMatch("\\uD834\\uDF06", "", astral));
    }

    // Annex B legacy escape fallbacks for a decimal digit past the group count.
    @Test
    void legacyDecimalEscapeFallbacks() {
        assertTrue(fullMatch("\\9", "", "9"));
        assertTrue(fullMatch("\\8", "", "8"));
    }

    // An out-of-order character class range is a SyntaxError, both in a plain class and a v-mode
    // set (appendClassRange and appendSetRange each validate this independently).
    @Test
    void outOfOrderRangeIsRejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[b-a]", ""));
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[b-a]", "v"));
    }

    // An unterminated named group is a SyntaxError.
    @Test
    void unterminatedGroupNameIsRejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("(?<name", ""));
    }

    // A bare literal astral PatternCharacter (not an escape) compiles to a Literal node regardless
    // of the u/v flag, matching correctly in both modes.
    @Test
    void bareAstralLiteralMatchesInEveryMode() {
        final var astral = new String(Character.toChars(0x20BB7));
        assertTrue(fullMatch(astral, "", astral));
        assertTrue(fullMatch(astral, "u", astral));
    }

    @Test
    void groupAliasesOfHelperDelegatesToProgram() {
        final var program = RegexTranslator.compile("(?<a>x)", "").getProgram();
        assertTrue(org.techhouse.simplejs.internal.regex.RegexParser.aliasesOf(program).containsKey("a"));
    }
}
