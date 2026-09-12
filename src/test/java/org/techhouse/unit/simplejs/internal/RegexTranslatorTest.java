package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.regex.RegexMatcher;

// The literal escapes below are deliberate: these cases turn on invisible or homoglyph code points
// (U+FE0F, U+20E3, U+017F, U+212A), which an editor or a merge would otherwise silently normalise.
@SuppressWarnings("UnnecessaryUnicodeEscape")
public class RegexTranslatorTest {
    private static boolean matches(String source, String flags, String input) {
        return RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), input, 0, false) != null;
    }

    private static boolean fullMatch(String source, String flags, String input) {
        final var match = RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), input, 0, false);
        return match != null && match.start() == 0 && match.end() == input.length();
    }

    private static void rejects(String source, String flags) {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile(source, flags));
    }

    @Test
    public void acceptsUnsupportedFlagsOnlyFromTheValidSet() {
        rejects("a", "q");
        rejects("a", "gg");
        rejects("a", "uv");
        assertEquals("dgimsy", RegexTranslator.compile("a", "dgimsy").getFlags());
    }

    @Test
    public void translatesMultiCodePointStringAlternativesToAnAlternation() {
        assertTrue(fullMatch("[\\q{a|abc|ab}]", "v", "abc"));
        assertTrue(fullMatch("[\\q{a|abc|ab}]", "v", "ab"));
        assertTrue(fullMatch("[\\q{a|abc|ab}]", "v", "a"));
        assertFalse(fullMatch("[\\q{a|abc|ab}]", "v", "ax"));
    }

    @Test
    public void appliesSetOperationsToStringAlternatives() {
        assertTrue(fullMatch("^[\\q{0|2|ab}--_]+$", "v", "ab"));
        assertTrue(fullMatch("^[\\q{0|2|ab}&&\\q{0|2|ab}]+$", "v", "ab"));
        assertFalse(fullMatch("^[\\q{ab}--\\q{ab}]+$", "v", "ab"));
    }

    @Test
    public void rejectsANegatedClassContainingStrings() {
        rejects("[^\\q{ab}]", "v");
    }

    @Test
    public void rejectsReservedDoublePunctuators() {
        for (final var reserved : new String[]{"!!", "##", "$$", "%%", "**", "++", ",,", "..", "::", ";;", "<<", "==",
                ">>", "??", "@@", "``", "~~", "^^^", "_^^"}) {
            rejects("[" + reserved + "]", "v");
        }
    }

    @Test
    public void rejectsEmptyAndMixedSetOperands() {
        rejects("[&&]", "v");
        rejects("[a&&b--c]", "v");
        rejects("[a--]", "v");
    }

    @Test
    public void acceptsPropertiesOfStringsInsideAClass() {
        assertTrue(fullMatch("[\\p{RGI_Emoji}]", "v", new String(new int[]{0x1F600}, 0, 1)));
        assertTrue(fullMatch("[\\p{Emoji_Keycap_Sequence}]", "v", "9️⃣"));
    }

    @Test
    public void translatesModifierGroups() {
        assertTrue(matches("(?i:a)b", "", "Ab"));
        assertFalse(matches("(?i:a)b", "", "AB"));
        assertTrue(matches("(?-i:a)b", "i", "aB"));
        assertFalse(matches("(?-i:a)b", "i", "AB"));
        assertTrue(matches("(?s:.)", "", "\n"));
        assertFalse(matches("(?-s:.)", "s", "\n"));
    }

    @Test
    public void rejectsRepeatedConflictingAndUnknownModifiers() {
        for (final var modifier : new String[]{"(?ii:a)", "(?imsi:a)", "(?d:a)", "(?u:a)", "(?i-i:a)", "(?m-m:a)",
                "(?ims-m:a)", "(?-:a)", "(?-ii:a)", "(?-imsi:a)", "(?-d:a)", "(?ii-:a)", "(?d-:a)", "(?ms-i)", "(?-s)",
                "(?i-)"}) {
            rejects(modifier, "");
        }
    }

    @Test
    public void anchorsMeanStartAndEndOfInputWithoutTheMultilineFlag() {
        assertFalse(matches("a$", "", "a\n"));
        assertTrue(matches("a$", "m", "a\nb"));
        assertTrue(matches("^b", "m", "a\nb"));
        assertFalse(matches("^b", "", "a\nb"));
    }

    @Test
    public void rejectsQuantifiersWithNothingToRepeat() {
        rejects("{2}", "");
        rejects("{2,}", "");
        rejects("{2,3}", "");
        rejects(".(?<=.)?", "");
        rejects(".(?<=.){2,3}", "");
        rejects("*a", "");
    }

    @Test
    public void clampsAnOversizedRepetitionInsteadOfFailingToCompile() {
        assertEquals("b{9007199254740991}", RegexTranslator.compile("b{9007199254740991}", "").getSource());
        assertFalse(matches("b{9007199254740991}", "", "bbb"));
    }

    @Test
    public void renamesGroupNamesJavaWouldReject() {
        final var regexp = RegexTranslator.compile("(?<_>a)(?<π>b)", "");
        assertEquals(2, regexp.getGroupAliases().size());
        assertTrue(regexp.getGroupAliases().containsKey("_"));
        assertTrue(regexp.getGroupAliases().containsKey("π"));
        assertTrue(matches("(?<__proto__>.)", "", "a"));
        assertTrue(matches("(?<𝓑𝓻>a)", "", "a"));
    }

    @Test
    public void rejectsAnInvalidGroupName() {
        rejects("(?<1a>x)", "");
        rejects("(?<>x)", "");
        rejects("(?<a>x)(?<a>y)", "");
    }

    @Test
    public void allowsDuplicateGroupNamesInDifferentAlternatives() {
        final var regexp = RegexTranslator.compile("(?<a>x)|(?<a>y)", "");
        assertEquals(2, regexp.getGroupAliases().get("a").size());
        assertTrue(matches("(?<a>x)|(?<a>y)", "", "y"));
    }

    @Test
    public void treatsAForwardReferenceAsTheEmptyString() {
        assertTrue(matches("\\k<a>(?<a>x)", "", "x"));
        assertTrue(matches("\\1(A)", "", "AA"));
    }

    // A numbered backreference inside its own group's body is a forward reference too and must match the empty
    // string: the "opened" count only increments after a group's body is parsed.
    @Test
    public void treatsASelfReferentialBackreferenceInsideItsOwnGroupAsTheEmptyString() {
        assertTrue(fullMatch("(abc\\1)", "", "abc"));
        assertFalse(fullMatch("(abc\\1)", "", "abcabc"));
        assertTrue(fullMatch("(a)(b\\1)", "", "aba"));
        assertTrue(fullMatch("(a(b\\1))", "", "ab"));
    }

    @Test
    public void rejectsAReferenceToAnUndeclaredGroupName() {
        rejects("(?<a>x)\\k<b>", "");
        rejects("\\k<a>", "u");
    }

    @Test
    public void rejectsUnterminatedConstructs() {
        rejects("[a", "");
        rejects("(a", "");
        rejects("a)", "");
        rejects("\\", "");
        rejects("[\\q{ab]", "v");
        rejects("\\p{L", "u");
    }

    @Test
    public void translatesLazyAndBoundedQuantifiers() {
        assertEquals("b", firstMatch("b"));
        assertEquals("aab", firstMatch("aab"));
        assertTrue(fullMatch("a{2}", "", "aa"));
        assertTrue(fullMatch("a{2,}", "", "aaa"));
        assertTrue(fullMatch("a{1,2}", "", "aa"));
        assertTrue(fullMatch("a{2,x}", "", "a{2,x}"));
        assertFalse(matches("a{99999999999999999999}", "", "aaa"));
    }

    private static String firstMatch(String input) {
        final var match = RegexMatcher.exec(RegexTranslator.compile("a*?b", "").getProgram(), input, 0, false);
        return match == null ? null : match.group(0);
    }

    @Test
    public void tracksMultilineAndDotAllThroughModifierGroups() {
        assertTrue(matches("(?m:^b)", "", "a\nb"));
        assertFalse(matches("(?-m:a$)", "m", "a\nb"));
        assertTrue(matches("(?-s:a)(?s:.)", "", "a\n"));
        assertTrue(matches("(?i:k)", "u", "K"));
    }

    @Test
    public void aBackreferenceToADuplicatedNameTriesEveryAlias() {
        assertTrue(matches("(?:(?<a>x)|(?<a>y))\\k<a>", "", "yy"));
        assertTrue(matches("(?:(?<a>x)|(?<a>y))\\k<a>", "", "xx"));
        assertFalse(matches("(?:(?<a>x)|(?<a>y))\\k<a>", "", "xy"));
    }

    @Test
    public void rejectsAMalformedNamedBackreference() {
        rejects("(?<a>x)\\k", "");
        rejects("(?<a>x)\\k<a", "");
        rejects("\\k", "u");
    }

    @Test
    public void rejectsAMalformedGroupName() {
        rejects("(?<a", "");
        rejects("(?<\\u{41>x)", "");
        rejects("(?<\\u{110000}>x)", "");
        rejects("(?<\\uZZZZ>x)", "");
        rejects("(?<\\u41>x)", "");
    }

    @Test
    public void translatesModifierGroupsWithAnEmptyRemoveList() {
        assertTrue(matches("(?i-:a)", "", "A"));
        assertTrue(matches("(?m:^b)", "", "a\nb"));
    }

    @Test
    public void treatsASelfReferenceAsAForwardReference() {
        assertTrue(matches("(?<a>\\k<a>\\w)..", "", "bab"));
        assertTrue(matches("\\k<a>(?<a>b)\\w\\k<a>", "", "bab"));
    }

    // Canonicalize(rer, ch) folds the candidate character before testing set membership, not the compiled
    // CharSet; java's UNICODE_CASE only folds the positive set, so (?iu:\P{Lu}) still rejects "A".
    @Test
    public void ignoreCaseFoldsTheCandidateForANegatedCaseCategory() {
        assertTrue(fullMatch("(?i:\\P{Lu})", "u", "A"));
        assertTrue(fullMatch("(?i:\\P{Lu})", "u", "a"));
        assertTrue(fullMatch("(?i:\\P{Lu})", "u", "Z"));
        assertTrue(fullMatch("(?i:\\P{Lu})", "u", "z"));
        assertTrue(fullMatch("(?i:\\P{Lu})", "u", "0"));
    }

    // A few Unicode CaseFolding.txt simple/common pairs have no ordinary upper/lowercase relationship, so
    // java's UNICODE_CASE never joins them and the match has to spell the pair explicitly.
    @Test
    public void ignoreCaseJoinsExtraCaseFoldPairsWithoutOrdinaryCasing() {
        assertTrue(fullMatch("[\\u0390]", "ui", "\u1fd3"));
        assertTrue(fullMatch("[\\u1fd3]", "ui", "\u0390"));
        assertTrue(fullMatch("[\\u03b0]", "ui", "\u1fe3"));
        assertTrue(fullMatch("[\\u1fe3]", "ui", "\u03b0"));
        assertTrue(fullMatch("[\\ufb05]", "ui", "\ufb06"));
        assertTrue(fullMatch("[\\ufb06]", "ui", "\ufb05"));
        assertTrue(fullMatch("\\u0390", "ui", "\u1fd3"));
    }

    @Test
    public void extraCaseFoldPairsAreInertWithoutIgnoreCase() {
        assertFalse(fullMatch("[\\u0390]", "u", "\u1fd3"));
        assertTrue(fullMatch("[\\u0390]", "u", "\u0390"));
    }
}
