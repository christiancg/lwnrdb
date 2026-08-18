package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;

// The literal escapes below are deliberate: these cases turn on invisible or homoglyph code
// points (U+FE0F variation selector, U+20E3 combining keycap, U+017F long s, U+212A Kelvin sign,
// which renders identically to ASCII K). Spelling them as characters would make the assertions
// unreadable and let an editor or a merge silently normalise them.
@SuppressWarnings("UnnecessaryUnicodeEscape")
public class RegexTranslatorTest {
    private static boolean matches(String source, String flags, String input) {
        return RegexTranslator.compile(source, flags).getPattern().matcher(input).find();
    }

    private static boolean fullMatch(String source, String flags, String input) {
        return RegexTranslator.compile(source, flags).getPattern().matcher(input).matches();
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

    // \q{} alternatives longer than one code point become an alternation, ordered longest-first so
    // the longest alternative wins exactly as a character class would.
    @Test
    public void translatesMultiCodePointStringAlternativesToAnAlternation() {
        final var pattern = RegexTranslator.compile("[\\q{a|abc|ab}]", "v").getPattern().pattern();
        final var first = pattern.indexOf("\\x{61}\\x{62}\\x{63}");
        final var second = pattern.indexOf("\\x{61}\\x{62}|");
        assertTrue(first >= 0 && second > first, pattern);
    }

    @Test
    public void singleCodePointStringAlternativesJoinTheCharacterSet() {
        assertTrue(fullMatch("[\\q{0|2|4}]", "v", "0"));
        assertTrue(fullMatch("[\\q{0|2|4}]", "v", "4"));
        assertFalse(fullMatch("[\\q{0|2|4}]", "v", "7"));
    }

    // The alternatives are ClassSetCharacters, so their escapes have to be decoded before their
    // length can be judged: \q{9️⃣} is one three-code-point string, not twelve characters.
    @Test
    public void decodesEscapesInsideStringAlternatives() {
        assertTrue(fullMatch("^[\\q{0|2|4|9\\uFE0F\\u20E3}_]+$", "v", "9️⃣"));
        assertTrue(fullMatch("^[\\q{0|2|4|9\\uFE0F\\u20E3}_]+$", "v", "024_"));
        assertFalse(fullMatch("^[\\q{0|2|4|9\\uFE0F\\u20E3}_]+$", "v", "7"));
        assertTrue(fullMatch("[\\q{\\u{1D306}\\u{1D307}}]", "v", new String(new int[]{0x1D306, 0x1D307}, 0, 2)));
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

    // v-mode makes the ClassSetSyntaxCharacters and the reserved double punctuators early errors.
    @Test
    public void rejectsUnescapedSetSyntaxCharacters() {
        for (final var syntax : new String[]{"(", ")", "{", "}", "/", "-", "|"}) {
            rejects("[" + syntax + "]", "v");
        }
        assertTrue(fullMatch("[\\-]", "v", "-"));
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

    // Modifier groups translate to java inline flag groups; the early errors are ours to raise.
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
    public void translatesTheEmptyAndAnyCharacterClasses() {
        assertFalse(matches("[]a", "", "a"));
        assertTrue(matches("[^]", "", "\n"));
        assertTrue(matches("a[]*b", "", "ab"));
    }

    @Test
    public void translatesEscapesJavaSpellsDifferently() {
        assertTrue(fullMatch("[\\b]", "", "\b"));
        assertTrue(fullMatch("\\0", "", "\0"));
        assertTrue(fullMatch("\\u{61}", "u", "a"));
        assertTrue(fullMatch("\\v", "", ""));
        assertTrue(fullMatch("\\s", "", " "));
        assertFalse(matches("\\S", "", " "));
    }

    // \d, \w, \s and \b must stay ASCII in u-mode: UNICODE_CHARACTER_CLASS is deliberately not set.
    @Test
    public void keepsClassEscapesAsciiInUnicodeMode() {
        assertFalse(matches("^\\d$", "u", "٠"));
        assertFalse(matches("^\\w$", "u", "é"));
    }

    @Test
    public void anchorsMeanStartAndEndOfInputWithoutTheMultilineFlag() {
        assertFalse(matches("a$", "", "a\n"));
        assertTrue(matches("a$", "m", "a\nb"));
        assertTrue(matches("^b", "m", "a\nb"));
        assertFalse(matches("^b", "", "a\nb"));
    }

    @Test
    public void appliesUnicodeCaseFoldingOnlyWithTheUnicodeFlag() {
        assertFalse(matches("\\u212a", "i", "k"));
        assertTrue(matches("\\u212a", "iu", "k"));
    }

    @Test
    public void rejectsTheUnicodeModeEarlyErrors() {
        rejects("\\c0", "u");
        rejects("\\1", "u");
        rejects("\\8", "u");
        rejects("[\\d-a]", "u");
        rejects("(?=.)?", "u");
        rejects("(?=.){2,3}", "u");
        rejects("]", "u");
        rejects("{1}", "u");
        rejects("\\A", "u");
        rejects("(?<a>\\a)", "u");
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
    public void allowsAQuantifiedLookaheadOnlyOutsideUnicodeMode() {
        assertTrue(matches(".(?=.)?", "", "ab"));
        rejects(".(?=.)?", "u");
    }

    @Test
    public void treatsALoneBraceAsALiteralOutsideUnicodeMode() {
        assertTrue(fullMatch("a{b", "", "a{b"));
        assertTrue(fullMatch("a{,2}", "", "a{,2}"));
        rejects("a{2,1}", "");
    }

    @Test
    public void clampsAnOversizedRepetitionInsteadOfFailingToCompile() {
        assertEquals("b{9007199254740991}", RegexTranslator.compile("b{9007199254740991}", "").getSource());
        assertFalse(matches("b{9007199254740991}", "", "bbb"));
    }

    // java only accepts [A-Za-z][A-Za-z0-9]* group names, so every named group is renamed and the
    // original name is kept as its alias.
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
    public void decodesUnicodeEscapesInGroupNames() {
        final var regexp = RegexTranslator.compile("(?<\\u03C0>a)\\k<\\u{3C0}>", "");
        assertTrue(regexp.getGroupAliases().containsKey("π"));
        assertTrue(matches("(?<\\u03C0>a)\\k<\\u{3C0}>", "", "aa"));
    }

    @Test
    public void rejectsAnInvalidGroupName() {
        rejects("(?<1a>x)", "");
        rejects("(?<>x)", "");
        rejects("(?<a>x)(?<a>y)", "");
    }

    // A duplicate name in a different alternative is legal from ES2025 on; both aliases are kept, so
    // a backreference can match whichever one participated.
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

    @Test
    public void rejectsAReferenceToAnUndeclaredGroupName() {
        rejects("(?<a>x)\\k<b>", "");
        rejects("\\k<a>", "u");
    }

    @Test
    public void treatsBackslashKAsALiteralWhenNoGroupIsNamed() {
        assertTrue(matches("\\k", "", "k"));
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
    public void translatesUnicodePropertyEscapes() {
        assertTrue(matches("\\p{Lu}", "u", "A"));
        assertTrue(matches("\\p{Uppercase_Letter}", "u", "A"));
        assertTrue(matches("\\p{gc=Lu}", "u", "A"));
        assertTrue(matches("\\p{Script=Greek}", "u", "π"));
        assertTrue(matches("\\p{ASCII}", "u", "a"));
        assertTrue(matches("\\p{ASCII_Hex_Digit}", "u", "f"));
        rejects("\\p{Nope}", "u");
        rejects("\\p{nope=Lu}", "u");
        rejects("\\p{gc=Nope}", "u");
    }

    @Test
    public void treatsPropertyEscapesAsLiteralsOutsideUnicodeMode() {
        assertTrue(fullMatch("\\p", "", "p"));
        assertTrue(fullMatch("[\\p]", "", "p"));
        rejects("\\pL", "u");
        rejects("[\\pL]", "u");
        rejects("[\\p{L", "u");
    }

    @Test
    public void translatesControlEscapes() {
        assertTrue(fullMatch("\\cA", "", ""));
        assertTrue(fullMatch("[\\cA]", "", ""));
        assertTrue(fullMatch("[\\c1]", "", ""));
        assertTrue(fullMatch("\\c1", "", "\\c1"));
        rejects("\\c1", "u");
    }

    // Annex B's ControlLetter accepts either case, but java.util.regex's own `\cX` syntax only
    // recognises an uppercase letter, so a lowercase one must be translated by computed value
    // (`letter % 32`) rather than passed through as `\cx`.
    @Test
    public void translatesLowercaseControlEscapes() {
        assertTrue(fullMatch("\\ca", "", ""));
        assertTrue(fullMatch("[\\ca]", "", ""));
        assertTrue(fullMatch("\\cz", "", ""));
    }

    @Test
    public void translatesHexAndUnicodeEscapes() {
        assertTrue(fullMatch("\\x41", "", "A"));
        assertTrue(fullMatch("\\xZ", "", "xZ"));
        rejects("\\xZ", "u");
        assertTrue(fullMatch("\\u0041", "", "A"));
        assertTrue(fullMatch("\\uZZZZ", "", "uZZZZ"));
        rejects("\\uZZZZ", "u");
        rejects("\\u{}", "u");
        rejects("\\u{ZZ}", "u");
        rejects("\\u{110000}", "u");
    }

    @Test
    public void translatesLegacyOctalAndDecimalEscapes() {
        assertTrue(fullMatch("\\01", "", ""));
        rejects("\\01", "u");
        assertTrue(fullMatch("\\8", "", "8"));
        assertTrue(fullMatch("\\101", "", "A"));
        assertTrue(matches("(a)\\1", "", "aa"));
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
        final var matcher = RegexTranslator.compile("a*?b", "").getPattern().matcher(input);
        return matcher.find() ? matcher.group() : null;
    }

    @Test
    public void tracksMultilineAndDotAllThroughModifierGroups() {
        assertTrue(matches("(?m:^b)", "", "a\nb"));
        assertFalse(matches("(?-m:a$)", "m", "a\nb"));
        assertTrue(matches("(?-s:a)(?s:.)", "", "a\n"));
        assertTrue(matches("(?i:k)", "u", "K"));
    }

    @Test
    public void allowsAClassEscapeAsARangeEndpointOutsideUnicodeMode() {
        assertTrue(fullMatch("[\\d-a]", "", "-"));
        assertTrue(fullMatch("[a-c]", "", "b"));
        assertTrue(fullMatch("[\\S]", "", "a"));
        assertTrue(fullMatch("[\\w]", "", "a"));
        assertTrue(fullMatch("[\\p{Nd}]", "u", "7"));
        rejects("[a", "");
        rejects("[a\\", "");
    }

    @Test
    public void rejectsAnInvalidRangeInsideAUnicodeSetsClass() {
        rejects("[\\d-a]", "v");
        assertTrue(fullMatch("[a-c]", "v", "b"));
    }

    @Test
    public void rejectsAMalformedStringAlternativeEscape() {
        rejects("[\\qab]", "v");
        rejects("[\\q{\\z}]", "v");
        rejects("[\\q{\\xZ}]", "v");
        rejects("[\\q{\\u{110000}}]", "v");
        rejects("[\\q{\\c1}]", "v");
        rejects("[\\q{a", "v");
    }

    @Test
    public void decodesEveryClassSetCharacterFormInsideStringAlternatives() {
        assertTrue(fullMatch("[\\q{\\n\\r\\t\\f\\v\\b\\0}]", "v", "\n\r\t\f\b\0"));
        assertTrue(fullMatch("[\\q{\\x41\\x42}]", "v", "AB"));
        assertTrue(fullMatch("[\\q{\\cA\\cB}]", "v", ""));
        assertTrue(fullMatch("[\\q{\\|\\-}]", "v", "|-"));
        assertTrue(fullMatch("[\\q{\\uD834x}]", "v", "\uD834x"));
    }

    // A backreference to a duplicated name compiles to an alternation of its aliases, so whichever
    // one participated is the one that matches.
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
    public void decodesAPartlyEscapedGroupName() {
        final var regexp = RegexTranslator.compile("(?<a\\u0062>x)", "");
        assertTrue(regexp.getGroupAliases().containsKey("ab"));
        assertTrue(matches("(?<a\\u200C>x)", "", "x"));
    }

    // The seven properties of strings are sets of sequences, so each renders as an alternation
    // (ordered longest-first) rather than a character class, and only under the v flag.
    @Test
    public void translatesEachPropertyOfStringsToAnAlternation() {
        assertTrue(fullMatch("\\p{Emoji_Keycap_Sequence}", "v", "9\ufe0f\u20e3"));
        assertFalse(matches("^\\p{Emoji_Keycap_Sequence}$", "v", "9\ufe0f"));
        assertTrue(fullMatch("\\p{Basic_Emoji}", "v", "\u231a"));
        assertTrue(fullMatch("\\p{Basic_Emoji}", "v", "\u00a9\ufe0f"));
        assertFalse(matches("^\\p{Basic_Emoji}$", "v", "\u00a9"));
        assertTrue(fullMatch("\\p{RGI_Emoji_Flag_Sequence}", "v", new String(new int[]{0x1F1E6, 0x1F1E9}, 0, 2)));
        assertTrue(fullMatch("\\p{RGI_Emoji_Tag_Sequence}", "v",
                new String(new int[]{0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F}, 0, 7)));
        assertTrue(fullMatch("\\p{RGI_Emoji_Modifier_Sequence}", "v", new String(new int[]{0x1F44D, 0x1F3FD}, 0, 2)));
        assertTrue(fullMatch("\\p{RGI_Emoji_ZWJ_Sequence}", "v",
                new String(new int[]{0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F466}, 0, 5)));
        assertTrue(fullMatch("\\p{RGI_Emoji}", "v", "9\ufe0f\u20e3"));
        assertTrue(fullMatch("\\p{RGI_Emoji}", "v", new String(new int[]{0x1F600}, 0, 1)));
        assertFalse(matches("^\\p{RGI_Emoji}$", "v", "a"));
    }

    // Longest-first: the keycap sequence must win over its own leading digit, which the same set
    // also contains through Basic_Emoji.
    @Test
    public void prefersTheLongestPropertyOfStringsAlternative() {
        assertTrue(fullMatch("^\\p{RGI_Emoji}$", "v", "9\ufe0f\u20e3"));
    }

    @Test
    public void rejectsAPropertyOfStringsOutsideItsAllowedPositions() {
        rejects("\\p{RGI_Emoji}", "u");
        rejects("\\p{Basic_Emoji}", "u");
        rejects("\\P{Basic_Emoji}", "v");
        rejects("[^\\p{Basic_Emoji}]", "v");
        rejects("[\\P{Basic_Emoji}]", "v");
        rejects("[\\p{Basic_Emoji}]", "u");
        rejects("[\\p{Basic_Emoji}-a]", "v");
    }

    @Test
    public void combinesAPropertyOfStringsWithTheOtherSetOperands() {
        assertTrue(fullMatch("[\\p{Emoji_Keycap_Sequence}a]", "v", "a"));
        assertTrue(fullMatch("[\\p{Emoji_Keycap_Sequence}a]", "v", "9\ufe0f\u20e3"));
        assertFalse(matches("^[\\p{RGI_Emoji}--\\p{Emoji_Keycap_Sequence}]$", "v", "9\ufe0f\u20e3"));
        assertTrue(fullMatch("[[\\p{Emoji_Keycap_Sequence}]]", "v", "9\ufe0f\u20e3"));
        assertTrue(fullMatch("[\\p{Emoji_Keycap_Sequence}\\q{ab}]", "v", "ab"));
    }

    // ECMA-262 forbids UAX #44 loose matching: a property name differing by whitespace or case is
    // not the same property, it is an early error.
    @Test
    public void rejectsLooselyMatchedPropertyNames() {
        rejects("\\p{ General_Category=Uppercase_Letter }", "u");
        rejects("\\p{General_Category = Uppercase_Letter}", "u");
        rejects("\\P{ General_Category=Uppercase_Letter }", "u");
        rejects("\\p{script=Greek}", "u");
        assertTrue(matches("\\p{Hex}", "u", "f"));
        assertTrue(matches("[\\p{Hex}\\P{Hex}]", "u", "\u2603"));
    }

    // An empty remove list is legal: RegularExpressionFlags may be empty on either side of the dash.
    @Test
    public void translatesModifierGroupsWithAnEmptyRemoveList() {
        assertTrue(matches("(?i-:a)", "", "A"));
        assertTrue(matches("(?m:^b)", "", "a\nb"));
    }

    // GetWordCharacters: under ignoreCase in unicode mode the two code points that case-fold into
    // the ASCII word set join it, which java's own \w and \b never do.
    @Test
    public void widensTheWordCharacterSetUnderIgnoreCase() {
        assertTrue(matches("(?i:\\w)", "u", "\u017f"));
        assertTrue(matches("(?i:\\w)", "u", "\u212a"));
        assertTrue(matches("(?i:\\w)", "u", "A"));
        assertFalse(matches("(?i:\\W)", "u", "\u017f"));
        assertTrue(matches("(?i:\\b)", "u", "\u017f"));
        assertTrue(matches("(?i:\\B)", "u", "Z\u017f"));
        assertFalse(matches("\\w", "u", "\u017f"));
        assertFalse(matches("(?i:\\w)", "", "\u017f"));
    }

    // Without u or v a pattern matches code units, so a supplementary code point is two of them and
    // a dotAll `.` may not swallow it whole.
    @Test
    public void matchesCodeUnitsWithoutUnicodeMode() {
        final var astral = new String(new int[]{0x10300}, 0, 1);
        assertFalse(fullMatch(".", "s", astral));
        assertFalse(fullMatch("(?s:.)", "", astral));
        assertTrue(fullMatch(".", "su", astral));
        assertTrue(fullMatch(".", "s", "a"));
        assertTrue(fullMatch(".", "s", "\n"));
        assertFalse(fullMatch(".", "", "\n"));
        assertTrue(fullMatch(".", "s", "\ud800"));
    }

    @Test
    public void rejectsADashIdentityEscapeOutsideAClassInUnicodeMode() {
        rejects("\\-", "u");
        assertTrue(fullMatch("[\\-]", "u", "-"));
        assertTrue(fullMatch("\\-", "", "-"));
    }

    // A reference to the group it sits inside cannot have participated, so it matches the empty
    // string rather than failing the whole alternative.
    @Test
    public void treatsASelfReferenceAsAForwardReference() {
        assertTrue(matches("(?<a>\\k<a>\\w)..", "", "bab"));
        assertTrue(matches("\\k<a>(?<a>b)\\w\\k<a>", "", "bab"));
    }

    @Test
    public void rendersNestedAndNegatedUnicodeSetsClasses() {
        assertTrue(fullMatch("[[a-c][0-9]]", "v", "b"));
        assertTrue(fullMatch("[[a-c][0-9]]", "v", "5"));
        assertTrue(fullMatch("[^a]", "v", "b"));
        assertTrue(fullMatch("[^]", "v", "\n"));
        assertFalse(matches("[]", "v", "a"));
    }
}
