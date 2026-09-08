package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.regex.RegexMatcher;

// The regex v (unicodeSets) flag translates set notation into the RxNode AST RegexMatcher executes.
public class RegexTranslatorVFlagTest {
    private static boolean find(String source, String probe) {
        return findFlags(source, "v", probe);
    }

    private static boolean findFlags(String source, String flags, String probe) {
        return RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), probe, 0, false) != null;
    }

    // subtraction A--B keeps members of A that are not in B
    @Test
    public void test_subtraction() {
        assertTrue(find("[\\p{L}--[a-z]]", "Q"));
        assertFalse(find("[\\p{L}--[a-z]]", "q"));
    }

    // intersection A&&B keeps members in both classes
    @Test
    public void test_intersection() {
        assertTrue(find("[\\p{Lu}&&\\p{L}]", "Q"));
        assertFalse(find("[\\p{Lu}&&\\p{L}]", "q"));
    }

    // a nested class is a union of its members
    @Test
    public void test_nested_union() {
        assertTrue(find("[[a-z][A-Z]]", "M"));
        assertFalse(find("[[a-z][A-Z]]", "5"));
    }

    // single-character \q{} string alternatives fold into the class
    @Test
    public void test_string_literal_single_char() {
        assertTrue(find("[\\q{a|b}]", "b"));
        assertFalse(find("[\\q{a|b}]", "c"));
    }

    // a multi-character \q{} string literal becomes an alternation branch
    @Test
    public void test_string_literal_multi_char_matches() {
        assertTrue(find("[\\q{ab}]", "ab"));
        assertFalse(find("^[\\q{ab}]$", "a"));
    }

    // property escapes are still translated inside v-mode classes
    @Test
    public void test_property_escape_in_class() {
        assertTrue(find("[\\p{Nd}]", "7"));
    }

    // u and v together remain mutually exclusive
    @Test
    public void test_u_and_v_together_rejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("a", "uv"));
    }

    // an unterminated v-mode class is a syntax error
    @Test
    public void test_unterminated_class_rejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[a-z", "v"));
    }

    // mixing set operators within one class is rejected
    @Test
    public void test_mixed_operators_rejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[\\p{L}&&[a-z]--[b]]", "v"));
    }

    // a negated v-mode class still matches the complement
    @Test
    public void test_negated_class() {
        assertFalse(find("[^[a-z][A-Z]]", "M"));
        assertTrue(find("[^[a-z][A-Z]]", "5"));
    }

    // \p{ASCII} is translated to the java.util.regex property of the same name
    @Test
    public void test_ascii_binary_property() {
        assertTrue(findFlags("\\p{ASCII}", "u", "A"));
        assertFalse(findFlags("\\p{ASCII}", "u", "é"));
        assertTrue(findFlags("\\P{ASCII}", "u", "é"));
    }

    // \p{ASCII} composes with v-mode set subtraction
    @Test
    public void test_ascii_in_set_subtraction() {
        assertTrue(find("[\\p{ASCII}--[a-z]]", "A"));
        assertFalse(find("[\\p{ASCII}--[a-z]]", "a"));
    }

    // \p{Any} matches every code point
    @Test
    public void test_any_binary_property() {
        assertTrue(findFlags("\\p{Any}", "u", "é"));
    }

    // an unsupported property name is still a SyntaxError
    @Test
    public void test_unsupported_property_still_throws() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("\\p{Emoji}", "u"));
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("\\p{Nope}", "u"));
    }

    // the existing category and script forms stay green
    @Test
    public void test_existing_properties_unchanged() {
        assertTrue(findFlags("\\p{Lu}", "u", "A"));
        assertTrue(findFlags("\\p{Script=Greek}", "u", "α"));
    }

    // ASCII_Hex_Digit is the ASCII-only subset [0-9A-Fa-f], in both u-mode and v-mode
    @Test
    public void test_ascii_hex_digit_binary_property() {
        assertTrue(findFlags("\\p{ASCII_Hex_Digit}", "u", "f"));
        assertFalse(findFlags("\\p{ASCII_Hex_Digit}", "u", "g"));
        assertTrue(findFlags("\\P{ASCII_Hex_Digit}", "u", "g"));
        assertTrue(find("[\\p{ASCII_Hex_Digit}]", "A"));
        assertFalse(find("[\\p{ASCII_Hex_Digit}]", "G"));
    }
}
