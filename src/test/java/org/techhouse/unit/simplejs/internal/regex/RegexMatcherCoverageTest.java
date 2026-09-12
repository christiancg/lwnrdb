package org.techhouse.unit.simplejs.internal.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.regex.RegexMatcher;

// Each test here targets one specific branch identified from the jacoco line-coverage report.
class RegexMatcherCoverageTest {
    private static boolean matches(String source, String flags, String input) {
        return RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), input, 0, false) != null;
    }

    private static boolean fullMatch(String source, String flags, String input) {
        final var match = RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), input, 0, false);
        return match != null && match.start() == 0 && match.end() == input.length();
    }

    @Test
    void simpleQuantifierStopsOnZeroWidthProgressPastMin() {
        assertTrue(fullMatch("(a)?\\1*b", "", "b"));
    }

    @Test
    void simpleQuantifierExhaustsAllCountsAndFails() {
        assertFalse(fullMatch("^a+$", "", "aab"));
    }

    @Test
    void repeatMatcherMemoizesAFailedPosition() {
        assertFalse(fullMatch("^(?:a|aa)+z$", "", "aaaa"));
    }

    @Test
    void lazyQuantifierOverGroupSucceedsWithZeroReps() {
        assertTrue(fullMatch("(a)??b", "", "b"));
    }

    @Test
    void lazyQuantifierOverGroupFallsBackToOneRep() {
        assertTrue(fullMatch("(a)??ba", "", "aba"));
    }

    @Test
    void lookbehindUnicodeCombinesSurrogatePairBackward() {
        final var astral = new String(Character.toChars(0x1D306));
        assertTrue(matches("(?<=.)x", "u", astral + "x"));
    }

    @Test
    void literalStringAlternativeFailsWhenInputTooShort() {
        assertFalse(fullMatch("[\\q{ab}]", "v", "a"));
    }

    @Test
    void backreferenceInsideLookbehindBackward() {
        assertTrue(matches("(a)(?<=\\1)", "", "aa"));
        assertFalse(matches("^(a)(?<=\\1\\1)$", "", "aa"));
    }

    @Test
    void ignoreCaseBackreferenceComparison() {
        assertTrue(matches("(a)\\1", "i", "aA"));
        assertFalse(matches("^(a)\\1$", "i", "ab"));
    }

    @Test
    void atomEscapesForDigitNegationAndControlChars() {
        assertTrue(fullMatch("\\D", "", "x"));
        assertFalse(fullMatch("\\D", "", "5"));
        assertTrue(fullMatch("\\f", "", "\f"));
        assertTrue(fullMatch("\\n", "", "\n"));
        assertTrue(fullMatch("\\r", "", "\r"));
        assertTrue(fullMatch("\\t", "", "\t"));
    }

    @Test
    void classEscapesForNegatedShorthands() {
        assertTrue(fullMatch("[\\D]", "", "x"));
        assertFalse(fullMatch("[\\D]", "", "5"));
        assertTrue(fullMatch("[\\W]", "", "!"));
        assertFalse(fullMatch("[\\W]", "", "a"));
        assertTrue(fullMatch("[\\s]", "", " "));
    }

    // A numeric escape inside a class is always an Annex B octal escape (here \1 = 0x01), never a backreference.
    @Test
    void classPositionDigitEscapeIsNeverABackreference() {
        assertTrue(fullMatch("(a)[\\1]", "", "a" + (char) 1));
    }

    @Test
    void adjacentUnicodeEscapesCombineIntoOneAstralLiteral() {
        final var astral = new String(Character.toChars(0x1D306));
        assertTrue(fullMatch("\\uD834\\uDF06", "", astral));
    }

    @Test
    void legacyDecimalEscapeFallbacks() {
        assertTrue(fullMatch("\\9", "", "9"));
        assertTrue(fullMatch("\\8", "", "8"));
    }

    @Test
    void outOfOrderRangeIsRejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[b-a]", ""));
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[b-a]", "v"));
    }

    @Test
    void unterminatedGroupNameIsRejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("(?<name", ""));
    }

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
