package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNull;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.JsUndefined;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;
import org.techhouse.simplejs.internal.Lexer;

public class LexerTest {
    // Empty input produces only the EOF token
    @Test
    public void test_lex_empty_string() {
        final List<JsBaseElement> tokens = Lexer.lex("");
        assertEquals(1, tokens.size());
        assertInstanceOf(JsEOF.class, tokens.getFirst());
    }

    // Whitespace-only input produces only the EOF token
    @Test
    public void test_lex_whitespace_only() {
        final List<JsBaseElement> tokens = Lexer.lex("  \t\n\r ");
        assertEquals(1, tokens.size());
        assertInstanceOf(JsEOF.class, tokens.getFirst());
    }

    // A plain identifier is lexed as a JsIdentifier
    @Test
    public void test_lex_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("foo");
        assertEquals(2, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("foo", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    // Identifiers may contain $ and _
    @Test
    public void test_lex_identifier_with_dollar_and_underscore() {
        final List<JsBaseElement> tokens = Lexer.lex("$my_var2");
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("$my_var2", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    // Reserved words are lexed as keywords
    @Test
    public void test_lex_keyword() {
        for (final var kw : List.of("function", "return", "const", "typeof", "instanceof", "else")) {
            final List<JsBaseElement> tokens = Lexer.lex(kw);
            assertInstanceOf(JsKeyword.class, tokens.getFirst(), kw);
            assertEquals(kw, ((JsKeyword) tokens.getFirst()).getValue());
        }
    }

    // true / false are lexed as booleans
    @Test
    public void test_lex_boolean() {
        assertTrue(((JsBoolean) Lexer.lex("true").getFirst()).getValue());
        assertFalse(((JsBoolean) Lexer.lex("false").getFirst()).getValue());
    }

    // null is lexed as the JsNull singleton
    @Test
    public void test_lex_null() {
        final var first = Lexer.lex("null").getFirst();
        assertInstanceOf(JsNull.class, first);
        assertSame(JsNull.getInstance(), first);
    }

    // undefined is lexed as the JsUndefined singleton
    @Test
    public void test_lex_undefined() {
        final var first = Lexer.lex("undefined").getFirst();
        assertInstanceOf(JsUndefined.class, first);
        assertSame(JsUndefined.getInstance(), first);
    }

    // Integer literal
    @Test
    public void test_lex_integer() {
        assertEquals(12345.0, ((JsNumber) Lexer.lex("12345").getFirst()).getValue());
    }

    // Floating point literal
    @Test
    public void test_lex_float() {
        assertEquals(3.14, ((JsNumber) Lexer.lex("3.14").getFirst()).getValue());
    }

    // Leading-dot float literal
    @Test
    public void test_lex_leading_dot_float() {
        assertEquals(0.5, ((JsNumber) Lexer.lex(".5").getFirst()).getValue());
    }

    // Exponent literal with sign
    @Test
    public void test_lex_exponent() {
        assertEquals(1.5e-3, ((JsNumber) Lexer.lex("1.5e-3").getFirst()).getValue());
        assertEquals(2e10, ((JsNumber) Lexer.lex("2E10").getFirst()).getValue());
    }

    // Hex / octal / binary radix literals
    @Test
    public void test_lex_radix_numbers() {
        assertEquals(31.0, ((JsNumber) Lexer.lex("0x1F").getFirst()).getValue());
        assertEquals(15.0, ((JsNumber) Lexer.lex("0o17").getFirst()).getValue());
        assertEquals(5.0, ((JsNumber) Lexer.lex("0b101").getFirst()).getValue());
    }

    // A number immediately followed by an identifier does not crash
    @Test
    public void test_lex_number_followed_by_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("3in");
        assertInstanceOf(JsNumber.class, tokens.get(0));
        assertInstanceOf(JsKeyword.class, tokens.get(1));
    }

    // Double-quoted string
    @Test
    public void test_lex_double_quoted_string() {
        assertEquals("hello", ((JsString) Lexer.lex("\"hello\"").getFirst()).getValue());
    }

    // Single-quoted string
    @Test
    public void test_lex_single_quoted_string() {
        assertEquals("hello", ((JsString) Lexer.lex("'hello'").getFirst()).getValue());
    }

    // String escape sequences are cooked
    @Test
    public void test_lex_string_with_escapes() {
        assertEquals("a\nb\tc\"d\\e", ((JsString) Lexer.lex("\"a\\nb\\tc\\\"d\\\\e\"").getFirst()).getValue());
    }

    // Unicode and hex escapes
    @Test
    public void test_lex_string_unicode_and_hex_escapes() {
        assertEquals("A", ((JsString) Lexer.lex("\"\\u0041\"").getFirst()).getValue());
        assertEquals("A", ((JsString) Lexer.lex("\"\\x41\"").getFirst()).getValue());
        assertEquals("😀", ((JsString) Lexer.lex("\"\\u{1F600}\"").getFirst()).getValue());
    }

    // Line continuation inside a string is dropped
    @Test
    public void test_lex_string_line_continuation() {
        assertEquals("ab", ((JsString) Lexer.lex("\"a\\\nb\"").getFirst()).getValue());
    }

    // Unterminated string throws
    @Test
    public void test_lex_unterminated_string_throws() {
        assertThrows(UnterminatedStringException.class, () -> Lexer.lex("\"unclosed"));
    }

    // Line comments are skipped
    @Test
    public void test_lex_line_comment_skipped() {
        final List<JsBaseElement> tokens = Lexer.lex("a // comment\nb");
        assertEquals(3, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.get(0));
        assertInstanceOf(JsIdentifier.class, tokens.get(1));
        assertInstanceOf(JsEOF.class, tokens.get(2));
    }

    // Block comments are skipped
    @Test
    public void test_lex_block_comment_skipped() {
        final List<JsBaseElement> tokens = Lexer.lex("a /* c\nd */ b");
        assertEquals(3, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.get(0));
        assertInstanceOf(JsIdentifier.class, tokens.get(1));
    }

    // Unterminated block comment throws
    @Test
    public void test_lex_unterminated_block_comment_throws() {
        assertThrows(UnterminatedCommentException.class, () -> Lexer.lex("a /* unterminated"));
    }

    // Multi-character operators are matched longest-first
    @Test
    public void test_lex_multi_char_operators() {
        assertOperator(">>>=", "a >>>= b");
        assertOperator("===", "a === b");
        assertOperator("!==", "a !== b");
        assertOperator("=>", "a => b");
        assertOperator("&&", "a && b");
        assertOperator("??", "a ?? b");
        assertOperator("?.", "a ?. b");
        assertOperator("...", "f(...a)");
        assertOperator("**", "a ** b");
    }

    private static void assertOperator(String expected, String source) {
        final var found = Lexer.lex(source).stream()
                .anyMatch(t -> t instanceof JsOperator op && op.getValue().equals(expected));
        assertTrue(found, expected);
    }

    // Separators
    @Test
    public void test_lex_separators() {
        final List<JsBaseElement> tokens = Lexer.lex("(){}[];,");
        assertEquals(9, tokens.size());
        for (var i = 0; i < 8; i++) {
            assertInstanceOf(JsSeparator.class, tokens.get(i));
        }
        assertEquals('(', ((JsSeparator) tokens.getFirst()).getValue());
    }

    // A slash after an operator is a regex
    @Test
    public void test_lex_regex_after_operator() {
        final List<JsBaseElement> tokens = Lexer.lex("x = /ab+c/");
        assertInstanceOf(JsRegex.class, tokens.get(2));
        assertEquals("ab+c", ((JsRegex) tokens.get(2)).getPattern());
    }

    // A slash after an identifier is division
    @Test
    public void test_lex_division_after_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("a / b");
        assertInstanceOf(JsOperator.class, tokens.get(1));
        assertEquals("/", ((JsOperator) tokens.get(1)).getValue());
    }

    // /= after an identifier is the divide-assign operator, not a regex
    @Test
    public void test_lex_divide_assign_after_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("a /= 2");
        assertInstanceOf(JsOperator.class, tokens.get(1));
        assertEquals("/=", ((JsOperator) tokens.get(1)).getValue());
    }

    // Regex with a character class containing a slash and flags
    @Test
    public void test_lex_regex_with_char_class_and_flags() {
        final List<JsBaseElement> tokens = Lexer.lex("var r = /[/a]b/gi");
        final var regex = (JsRegex) tokens.get(3);
        assertEquals("[/a]b", regex.getPattern());
        assertEquals("gi", regex.getFlags());
    }

    // Regex with an escaped slash
    @Test
    public void test_lex_regex_with_escape() {
        final var regex = (JsRegex) Lexer.lex("= /a\\/b/").get(1);
        assertEquals("a\\/b", regex.getPattern());
    }

    // Unterminated regex throws
    @Test
    public void test_lex_unterminated_regex_throws() {
        assertThrows(UnterminatedRegexException.class, () -> Lexer.lex("= /abc"));
    }

    // Regex terminated by a newline throws
    @Test
    public void test_lex_regex_newline_throws() {
        assertThrows(UnterminatedRegexException.class, () -> Lexer.lex("= /abc\n/"));
    }

    // No-substitution template literal
    @Test
    public void test_lex_no_substitution_template() {
        final var template = (JsTemplateString) Lexer.lex("`hello world`").getFirst();
        assertEquals(List.of("hello world"), template.getQuasis());
        assertTrue(template.getExpressions().isEmpty());
    }

    // Template literal with an interpolation
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

    // Template interpolation containing object braces
    @Test
    public void test_lex_template_nested_braces() {
        final var template = (JsTemplateString) Lexer.lex("`${ {a:1}.a }`").getFirst();
        assertEquals(List.of("", ""), template.getQuasis());
        assertEquals(1, template.getExpressions().size());
    }

    // Template interpolation containing a string with a brace
    @Test
    public void test_lex_template_interpolation_with_string_brace() {
        final var template = (JsTemplateString) Lexer.lex("`${ \"}\" }`").getFirst();
        assertEquals(1, template.getExpressions().size());
        assertInstanceOf(JsString.class, template.getExpressions().getFirst().getFirst());
    }

    // Nested template literal inside an interpolation
    @Test
    public void test_lex_template_nested_template() {
        final var template = (JsTemplateString) Lexer.lex("`${`x${1}y`}`").getFirst();
        assertEquals(1, template.getExpressions().size());
        assertInstanceOf(JsTemplateString.class, template.getExpressions().getFirst().getFirst());
    }

    // Unterminated template throws
    @Test
    public void test_lex_unterminated_template_throws() {
        assertThrows(UnterminatedTemplateException.class, () -> Lexer.lex("`abc"));
    }

    // Unterminated interpolation throws
    @Test
    public void test_lex_unterminated_template_interpolation_throws() {
        assertThrows(UnterminatedTemplateException.class, () -> Lexer.lex("`a${1 + 2"));
    }

    // Unexpected character throws
    @Test
    public void test_lex_unexpected_character_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Lexer.lex("@"));
    }

    // lex yields the same token sequence (by type) as lexWithPositions
    @Test
    public void test_lex_matches_lex_with_positions_tokens() {
        final String source = "let x = 42;";
        final List<JsBaseElement> plain = Lexer.lex(source);
        final List<JsBaseElement> withPositions = Lexer.lexWithPositions(source).tokens();
        assertEquals(withPositions.size(), plain.size());
        for (var i = 0; i < plain.size(); i++) {
            assertEquals(withPositions.get(i).getType(), plain.get(i).getType());
        }
    }

    // lexWithPositions records a parallel position per token, EOF included
    @Test
    public void test_lex_with_positions_parallel_to_tokens() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a + b");
        assertEquals("a + b", result.source());
        assertEquals(result.tokens().size(), result.positions().size());
    }

    // lexWithPositions records offset, length and 1-based line/column, spanning newlines
    @Test
    public void test_lex_with_positions_offsets_and_line_column() {
        // offsets: l0 e1 t2 ' '3 x4 ' '5 =6 \n7 ' '8 ' '9 4:10 2:11 ;12
        final Lexer.LexResult result = Lexer.lexWithPositions("let x =\n  42;");
        final List<SourcePosition> positions = result.positions();
        // 'let' keyword: offset 0, length 3, line 1, column 1
        assertEquals(0, positions.getFirst().getOffset());
        assertEquals(3, positions.getFirst().getLength());
        assertEquals(1, positions.get(0).getLine());
        assertEquals(1, positions.get(0).getColumn());
        // '=' operator: offset 6, line 1, column 7
        assertEquals(6, positions.get(2).getOffset());
        assertEquals(1, positions.get(2).getLine());
        assertEquals(7, positions.get(2).getColumn());
        // '42' number on the second line: offset 10, length 2, line 2, column 3
        assertEquals(10, positions.get(3).getOffset());
        assertEquals(2, positions.get(3).getLength());
        assertEquals(2, positions.get(3).getLine());
        assertEquals(3, positions.get(3).getColumn());
        // trailing EOF: zero length at the end of the source
        final SourcePosition eof = positions.getLast();
        assertEquals(13, eof.getOffset());
        assertEquals(0, eof.getLength());
        assertEquals(2, eof.getLine());
    }
}
