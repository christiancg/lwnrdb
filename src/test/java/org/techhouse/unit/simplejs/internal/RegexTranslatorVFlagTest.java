package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.regex.RegexMatcher;

public class RegexTranslatorVFlagTest {
    private static boolean find(String source, String probe) {
        return findFlags(source, "v", probe);
    }

    private static boolean findFlags(String source, String flags, String probe) {
        return RegexMatcher.exec(RegexTranslator.compile(source, flags).getProgram(), probe, 0, false) != null;
    }

    @Test
    public void test_subtraction() {
        assertTrue(find("[\\p{L}--[a-z]]", "Q"));
        assertFalse(find("[\\p{L}--[a-z]]", "q"));
    }

    @Test
    public void test_intersection() {
        assertTrue(find("[\\p{Lu}&&\\p{L}]", "Q"));
        assertFalse(find("[\\p{Lu}&&\\p{L}]", "q"));
    }

    @Test
    public void test_nested_union() {
        assertTrue(find("[[a-z][A-Z]]", "M"));
        assertFalse(find("[[a-z][A-Z]]", "5"));
    }

    @Test
    public void test_string_literal_single_char() {
        assertTrue(find("[\\q{a|b}]", "b"));
        assertFalse(find("[\\q{a|b}]", "c"));
    }

    @Test
    public void test_string_literal_multi_char_matches() {
        assertTrue(find("[\\q{ab}]", "ab"));
        assertFalse(find("^[\\q{ab}]$", "a"));
    }

    @Test
    public void test_property_escape_in_class() {
        assertTrue(find("[\\p{Nd}]", "7"));
    }

    @Test
    public void test_u_and_v_together_rejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("a", "uv"));
    }

    @Test
    public void test_unterminated_class_rejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[a-z", "v"));
    }

    @Test
    public void test_mixed_operators_rejected() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("[\\p{L}&&[a-z]--[b]]", "v"));
    }

    @Test
    public void test_negated_class() {
        assertFalse(find("[^[a-z][A-Z]]", "M"));
        assertTrue(find("[^[a-z][A-Z]]", "5"));
    }

    @Test
    public void test_ascii_binary_property() {
        assertTrue(findFlags("\\p{ASCII}", "u", "A"));
        assertFalse(findFlags("\\p{ASCII}", "u", "é"));
        assertTrue(findFlags("\\P{ASCII}", "u", "é"));
    }

    @Test
    public void test_ascii_in_set_subtraction() {
        assertTrue(find("[\\p{ASCII}--[a-z]]", "A"));
        assertFalse(find("[\\p{ASCII}--[a-z]]", "a"));
    }

    @Test
    public void test_any_binary_property() {
        assertTrue(findFlags("\\p{Any}", "u", "é"));
    }

    @Test
    public void test_unsupported_property_still_throws() {
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("\\p{Emoji}", "u"));
        assertThrows(SyntaxErrorException.class, () -> RegexTranslator.compile("\\p{Nope}", "u"));
    }

    @Test
    public void test_existing_properties_unchanged() {
        assertTrue(findFlags("\\p{Lu}", "u", "A"));
        assertTrue(findFlags("\\p{Script=Greek}", "u", "α"));
    }

    @Test
    public void test_ascii_hex_digit_binary_property() {
        assertTrue(findFlags("\\p{ASCII_Hex_Digit}", "u", "f"));
        assertFalse(findFlags("\\p{ASCII_Hex_Digit}", "u", "g"));
        assertTrue(findFlags("\\P{ASCII_Hex_Digit}", "u", "g"));
        assertTrue(find("[\\p{ASCII_Hex_Digit}]", "A"));
        assertFalse(find("[\\p{ASCII_Hex_Digit}]", "G"));
    }
}
