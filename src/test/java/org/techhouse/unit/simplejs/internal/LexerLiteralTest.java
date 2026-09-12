package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;
import org.techhouse.simplejs.internal.Lexer;

public class LexerLiteralTest {
    @Test
    public void test_lex_empty_string() {
        final List<JsBaseElement> tokens = Lexer.lex("");
        assertEquals(1, tokens.size());
        assertInstanceOf(JsEOF.class, tokens.getFirst());
    }

    @Test
    public void test_lex_radix_numbers() {
        assertEquals(31.0, ((JsNumber) Lexer.lex("0x1F").getFirst()).getValue());
        assertEquals(15.0, ((JsNumber) Lexer.lex("0o17").getFirst()).getValue());
        assertEquals(5.0, ((JsNumber) Lexer.lex("0b101").getFirst()).getValue());
    }

    @Test
    public void test_lex_number_followed_by_identifier() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("3in"));
    }

    @Test
    public void test_lex_numeric_separators() {
        assertEquals(1000000.0, ((JsNumber) Lexer.lex("1_000_000").getFirst()).getValue());
        assertEquals(1000.0005, ((JsNumber) Lexer.lex("1_000.000_5").getFirst()).getValue());
        assertEquals(1e11, ((JsNumber) Lexer.lex("1_0e1_0").getFirst()).getValue());
        assertEquals(65535.0, ((JsNumber) Lexer.lex("0xFF_FF").getFirst()).getValue());
        assertEquals(170.0, ((JsNumber) Lexer.lex("0b1010_1010").getFirst()).getValue());
    }

    @Test
    public void test_lex_misplaced_separator_stops_number() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("1__0"));
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("1_"));
    }

    @Test
    public void test_lex_bigint() {
        assertEquals(new BigInteger("123"), ((JsBigInt) Lexer.lex("123n").getFirst()).getValue());
        assertEquals(new BigInteger("255"), ((JsBigInt) Lexer.lex("0xFFn").getFirst()).getValue());
        assertEquals(BigInteger.TEN, ((JsBigInt) Lexer.lex("0b1010n").getFirst()).getValue());
        assertEquals(new BigInteger("1000"), ((JsBigInt) Lexer.lex("1_000n").getFirst()).getValue());
    }

    @Test
    public void test_lex_identifier_with_unicode_escape() {
        final List<JsBaseElement> tokens = Lexer.lex("\\u0061\\u{62}c");
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("abc", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_private_identifier_with_unicode_escape() {
        final List<JsBaseElement> tokens = Lexer.lex("#\\u{6F}_");
        assertInstanceOf(JsPrivateIdentifier.class, tokens.getFirst());
        assertEquals("o_", ((JsPrivateIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_escaped_keyword_is_an_escaped_identifier() {
        final var tokens = Lexer.lex("\\u0069\\u0066");
        final var identifier = (org.techhouse.simplejs.elements.JsIdentifier) tokens.getFirst();
        assertEquals("if", identifier.getValue());
        assertTrue(identifier.isEscaped());
    }

    @Test
    public void test_lex_double_quoted_string() {
        assertEquals("hello", ((JsString) Lexer.lex("\"hello\"").getFirst()).getValue());
    }

    @Test
    public void test_lex_single_quoted_string() {
        assertEquals("hello", ((JsString) Lexer.lex("'hello'").getFirst()).getValue());
    }

    @Test
    public void test_lex_string_with_escapes() {
        assertEquals("a\nb\tc\"d\\e", ((JsString) Lexer.lex("\"a\\nb\\tc\\\"d\\\\e\"").getFirst()).getValue());
    }

    @Test
    public void test_lex_string_unicode_and_hex_escapes() {
        assertEquals("A", ((JsString) Lexer.lex("\"\\u0041\"").getFirst()).getValue());
        assertEquals("A", ((JsString) Lexer.lex("\"\\x41\"").getFirst()).getValue());
        assertEquals("😀", ((JsString) Lexer.lex("\"\\u{1F600}\"").getFirst()).getValue());
    }

    @Test
    public void test_lex_string_line_continuation() {
        assertEquals("ab", ((JsString) Lexer.lex("\"a\\\nb\"").getFirst()).getValue());
    }

    @Test
    public void test_lex_unterminated_string_throws() {
        assertThrows(UnterminatedStringException.class, () -> Lexer.lex("\"unclosed"));
    }

    @Test
    public void test_lex_regex_after_operator() {
        final List<JsBaseElement> tokens = Lexer.lex("x = /ab+c/");
        assertInstanceOf(JsRegex.class, tokens.get(2));
        assertEquals("ab+c", ((JsRegex) tokens.get(2)).getPattern());
    }

    @Test
    public void test_lex_regex_with_char_class_and_flags() {
        final List<JsBaseElement> tokens = Lexer.lex("var r = /[/a]b/gi");
        final var regex = (JsRegex) tokens.get(3);
        assertEquals("[/a]b", regex.getPattern());
        assertEquals("gi", regex.getFlags());
    }

    @Test
    public void test_lex_regex_with_escape() {
        final var regex = (JsRegex) Lexer.lex("= /a\\/b/").get(1);
        assertEquals("a\\/b", regex.getPattern());
    }

    @Test
    public void test_lex_unterminated_regex_throws() {
        assertThrows(UnterminatedRegexException.class, () -> Lexer.lex("= /abc"));
    }

    @Test
    public void test_lex_regex_newline_throws() {
        assertThrows(UnterminatedRegexException.class, () -> Lexer.lex("= /abc\n/"));
    }

    @Test
    public void test_lex_no_substitution_template() {
        final var template = (JsTemplateString) Lexer.lex("`hello world`").getFirst();
        assertEquals(List.of("hello world"), template.getQuasis());
        assertTrue(template.getExpressions().isEmpty());
    }

    @Test
    public void test_lex_template_with_interpolation() {
        final var template = (JsTemplateString) Lexer.lex("`a${1 + 2}b`").getFirst();
        assertEquals(List.of("a", "b"), template.getQuasis());
        assertEquals(1, template.getExpressions().size());
        final var expr = template.getExpressions().getFirst();
        assertInstanceOf(JsNumber.class, expr.get(0));
        assertInstanceOf(JsOperator.class, expr.get(1));
        assertInstanceOf(JsNumber.class, expr.get(2));
    }

    @Test
    public void test_lex_template_captures_raw_quasis() {
        final var template = (JsTemplateString) Lexer.lex("`a\\n${x}b`").getFirst();
        assertEquals(List.of("a\n", "b"), template.getQuasis());
        assertEquals(List.of("a\\n", "b"), template.getRawQuasis());
    }

    @Test
    public void test_lex_template_raw_matches_cooked_when_no_escapes() {
        final var template = (JsTemplateString) Lexer.lex("`hello world`").getFirst();
        assertEquals(template.getQuasis(), template.getRawQuasis());
    }

    // A NotEscapeSequence nulls the quasi's cooked value rather than throwing; rejecting an untagged
    // template is the Parser's job. The raw text is captured verbatim regardless.
    @Test
    public void test_lex_template_invalid_octal_escape_nulls_cooked() {
        var template = (JsTemplateString) Lexer.lex("`\\01`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\01", template.getRawQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\1`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\1", template.getRawQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\8`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\8", template.getRawQuasis().getFirst());
    }

    @Test
    public void test_lex_template_invalid_hex_escape_nulls_cooked() {
        var template = (JsTemplateString) Lexer.lex("`\\xg`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\xg", template.getRawQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\xAg`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\xAg", template.getRawQuasis().getFirst());
    }

    @Test
    public void test_lex_template_valid_hex_escape_is_cooked() {
        assertEquals(List.of("A"), ((JsTemplateString) Lexer.lex("`\\x41`").getFirst()).getQuasis());
    }

    @Test
    public void test_lex_template_invalid_unicode_escape_nulls_cooked() {
        var template = (JsTemplateString) Lexer.lex("`\\u0`").getFirst();
        assertNull(template.getQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\u0g`").getFirst();
        assertNull(template.getQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\u00g`").getFirst();
        assertNull(template.getQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\u000g`").getFirst();
        assertNull(template.getQuasis().getFirst());
    }

    @Test
    public void test_lex_template_valid_unicode_escape_is_cooked() {
        assertEquals(List.of("A"), ((JsTemplateString) Lexer.lex("`\\u0041`").getFirst()).getQuasis());
    }

    @Test
    public void test_lex_template_invalid_braced_unicode_escape_nulls_cooked() {
        var template = (JsTemplateString) Lexer.lex("`\\u{g`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\u{g", template.getRawQuasis().getFirst());

        template = (JsTemplateString) Lexer.lex("`\\u{0`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\u{0", template.getRawQuasis().getFirst());

        // syntactically well-formed (a real closing brace) but out of the Unicode code point range
        template = (JsTemplateString) Lexer.lex("`\\u{10FFFFF}`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\\u{10FFFFF}", template.getRawQuasis().getFirst());
    }

    @Test
    public void test_lex_template_valid_braced_unicode_escape_is_cooked() {
        assertEquals(List.of("A"), ((JsTemplateString) Lexer.lex("`\\u{41}`").getFirst()).getQuasis());
    }

    @Test
    public void test_lex_template_invalid_escape_is_scoped_to_its_own_quasi() {
        final var template = (JsTemplateString) Lexer.lex("`\\1${1}\\n`").getFirst();
        assertNull(template.getQuasis().getFirst());
        assertEquals("\n", template.getQuasis().get(1));
    }

    @Test
    public void test_lex_template_nested_braces() {
        final var template = (JsTemplateString) Lexer.lex("`${ {a:1}.a }`").getFirst();
        assertEquals(List.of("", ""), template.getQuasis());
        assertEquals(1, template.getExpressions().size());
    }

    @Test
    public void test_lex_template_interpolation_with_string_brace() {
        final var template = (JsTemplateString) Lexer.lex("`${ \"}\" }`").getFirst();
        assertEquals(1, template.getExpressions().size());
        assertInstanceOf(JsString.class, template.getExpressions().getFirst().getFirst());
    }

    @Test
    public void test_lex_template_nested_template() {
        final var template = (JsTemplateString) Lexer.lex("`${`x${1}y`}`").getFirst();
        assertEquals(1, template.getExpressions().size());
        assertInstanceOf(JsTemplateString.class, template.getExpressions().getFirst().getFirst());
    }

    @Test
    public void test_lex_template_interpolation_with_regex_quote() {
        final var template = (JsTemplateString) Lexer.lex("`${k.replace(/'/g, \"x\")}`").getFirst();
        assertEquals(List.of("", ""), template.getQuasis());
        final var expression = template.getExpressions().getFirst();
        final var regex = (JsRegex) expression.get(4);
        assertEquals("'", regex.getPattern());
        assertEquals("g", regex.getFlags());
    }

    @Test
    public void test_lex_template_interpolation_with_regex_backtick() {
        final var template = (JsTemplateString) Lexer.lex("`${/`/.test(s)}`").getFirst();
        final var regex = (JsRegex) template.getExpressions().getFirst().getFirst();
        assertEquals("`", regex.getPattern());
    }

    @Test
    public void test_lex_template_nested_template_with_regex() {
        final var template = (JsTemplateString) Lexer.lex("`${`a${/}/.source}b`}`").getFirst();
        final var nested = (JsTemplateString) template.getExpressions().getFirst().getFirst();
        assertEquals(List.of("a", "b"), nested.getQuasis());
        assertEquals("}", ((JsRegex) nested.getExpressions().getFirst().getFirst()).getPattern());
    }

    @Test
    public void test_lex_template_interpolation_division_not_regex() {
        for (final var source : List.of("`${(a)/b/c}`", "`${x[0]/2}`", "`${a/b}`")) {
            final var template = (JsTemplateString) Lexer.lex(source).getFirst();
            final var expression = template.getExpressions().getFirst();
            assertTrue(expression.stream().noneMatch(JsRegex.class::isInstance), source);
            assertTrue(expression.stream().anyMatch(
                    token -> token instanceof JsOperator operator && "/".equals(operator.getValue())), source);
        }
    }

    @Test
    public void test_lex_template_interpolation_unterminated_string_throws() {
        assertThrows(UnterminatedStringException.class, () -> Lexer.lex("`${'abc}`"));
    }

    @Test
    public void test_lex_unterminated_template_throws() {
        assertThrows(UnterminatedTemplateException.class, () -> Lexer.lex("`abc"));
    }

    @Test
    public void test_lex_unterminated_template_interpolation_throws() {
        assertThrows(UnterminatedTemplateException.class, () -> Lexer.lex("`a${1 + 2"));
    }

    @Test
    public void test_legacy_octal_literal_rejected() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("0755"));
    }

    @Test
    public void test_non_octal_decimal_literal_rejected() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("08"));
    }

    @Test
    public void test_zero_and_prefixed_literals_still_valid() {
        assertDoesNotThrow(() -> Lexer.lex("0"));
        assertDoesNotThrow(() -> Lexer.lex("0.5"));
        assertDoesNotThrow(() -> Lexer.lex("0n"));
        assertDoesNotThrow(() -> Lexer.lex("0x1F"));
        assertDoesNotThrow(() -> Lexer.lex("0o17"));
        assertDoesNotThrow(() -> Lexer.lex("0b10"));
    }

    @Test
    public void test_octal_string_escape_rejected() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("'\\07'"));
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("'\\1'"));
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("'\\8'"));
    }

    @Test
    public void test_null_escape_still_valid() {
        assertDoesNotThrow(() -> Lexer.lex("'\\0'"));
        assertDoesNotThrow(() -> Lexer.lex("'\\0a'"));
    }
}
