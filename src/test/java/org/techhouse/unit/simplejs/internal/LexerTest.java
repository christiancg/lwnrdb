package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNull;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.JsUndefined;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
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

    // Numeric separators are allowed between digits (decimal, fraction, exponent, radix) and stripped
    @Test
    public void test_lex_numeric_separators() {
        assertEquals(1000000.0, ((JsNumber) Lexer.lex("1_000_000").getFirst()).getValue());
        assertEquals(1000.0005, ((JsNumber) Lexer.lex("1_000.000_5").getFirst()).getValue());
        assertEquals(1e11, ((JsNumber) Lexer.lex("1_0e1_0").getFirst()).getValue());
        assertEquals(65535.0, ((JsNumber) Lexer.lex("0xFF_FF").getFirst()).getValue());
        assertEquals(170.0, ((JsNumber) Lexer.lex("0b1010_1010").getFirst()).getValue());
    }

    // A misplaced separator ends the number rather than being consumed
    @Test
    public void test_lex_misplaced_separator_stops_number() {
        final var doubled = Lexer.lex("1__0");
        assertEquals(1.0, ((JsNumber) doubled.get(0)).getValue());
        assertInstanceOf(JsIdentifier.class, doubled.get(1));
        final var trailing = Lexer.lex("1_");
        assertEquals(1.0, ((JsNumber) trailing.get(0)).getValue());
        assertInstanceOf(JsIdentifier.class, trailing.get(1));
    }

    // A trailing n suffix produces a BigInt token in every integer form
    @Test
    public void test_lex_bigint() {
        assertEquals(new BigInteger("123"), ((JsBigInt) Lexer.lex("123n").getFirst()).getValue());
        assertEquals(new BigInteger("255"), ((JsBigInt) Lexer.lex("0xFFn").getFirst()).getValue());
        assertEquals(BigInteger.TEN, ((JsBigInt) Lexer.lex("0b1010n").getFirst()).getValue());
        assertEquals(new BigInteger("1000"), ((JsBigInt) Lexer.lex("1_000n").getFirst()).getValue());
    }

    // A hashbang at offset 0 is skipped as trivia
    @Test
    public void test_lex_hashbang_skipped_at_start() {
        final List<JsBaseElement> tokens = Lexer.lex("#!/usr/bin/env node\nfoo");
        assertEquals(2, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("foo", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    // A # anywhere other than a leading hashbang is an unexpected character
    @Test
    public void test_lex_hash_not_at_start_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Lexer.lex("foo\n#!bar"));
    }

    // A # followed by an identifier lexes as a private identifier (name without the #)
    @Test
    public void test_lex_private_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("#field");
        assertInstanceOf(JsPrivateIdentifier.class, tokens.getFirst());
        assertEquals("field", ((JsPrivateIdentifier) tokens.getFirst()).getValue());
    }

    // An identifier written with unicode escapes lexes to its cooked name
    @Test
    public void test_lex_identifier_with_unicode_escape() {
        final List<JsBaseElement> tokens = Lexer.lex("\\u0061\\u{62}c");
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("abc", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    // A private name may also be written with unicode escapes
    @Test
    public void test_lex_private_identifier_with_unicode_escape() {
        final List<JsBaseElement> tokens = Lexer.lex("#\\u{6F}_");
        assertInstanceOf(JsPrivateIdentifier.class, tokens.getFirst());
        assertEquals("o_", ((JsPrivateIdentifier) tokens.getFirst()).getValue());
    }

    // constructor is no longer a keyword
    @Test
    public void test_lex_constructor_is_an_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("constructor");
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("constructor", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    // A reserved word spelled with an escape is rejected rather than lexed as an identifier
    @Test
    public void test_lex_escaped_keyword_throws() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("\\u0069\\u0066"));
    }

    // A # not followed by an identifier is an unexpected character
    @Test
    public void test_lex_lone_hash_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Lexer.lex("a # b"));
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

    // Raw quasis preserve escape sequences verbatim while cooked quasis interpret them
    @Test
    public void test_lex_template_captures_raw_quasis() {
        final var template = (JsTemplateString) Lexer.lex("`a\\n${x}b`").getFirst();
        assertEquals(List.of("a\n", "b"), template.getQuasis());
        assertEquals(List.of("a\\n", "b"), template.getRawQuasis());
    }

    // With no escapes the raw and cooked quasis are identical
    @Test
    public void test_lex_template_raw_matches_cooked_when_no_escapes() {
        final var template = (JsTemplateString) Lexer.lex("`hello world`").getFirst();
        assertEquals(template.getQuasis(), template.getRawQuasis());
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

    // newlineBefore is true only for a token whose preceding trivia contained a line terminator
    @Test
    public void test_newline_before_flag_across_line_break() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a\nb c");
        // tokens: a(0) b(1) c(2) EOF(3)
        assertFalse(result.newlineBefore().get(0));
        assertTrue(result.newlineBefore().get(1));
        assertFalse(result.newlineBefore().get(2));
    }

    // A multi-line block comment counts as a line terminator between tokens
    @Test
    public void test_newline_before_flag_multiline_block_comment() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a /*\n*/ b");
        assertTrue(result.newlineBefore().get(1));
    }

    // A single-line block comment on one line does not set newlineBefore
    @Test
    public void test_newline_before_flag_single_line_block_comment() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a /* x */ b");
        assertFalse(result.newlineBefore().get(1));
    }

    // Legacy octal integer literals are rejected in strict mode
    @Test
    public void test_legacy_octal_literal_rejected() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("0755"));
    }

    // A leading-zero non-octal decimal (08) is rejected
    @Test
    public void test_non_octal_decimal_literal_rejected() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("08"));
    }

    // 0, 0.5, 0n and the radix-prefixed literals remain valid
    @Test
    public void test_zero_and_prefixed_literals_still_valid() {
        assertDoesNotThrow(() -> Lexer.lex("0"));
        assertDoesNotThrow(() -> Lexer.lex("0.5"));
        assertDoesNotThrow(() -> Lexer.lex("0n"));
        assertDoesNotThrow(() -> Lexer.lex("0x1F"));
        assertDoesNotThrow(() -> Lexer.lex("0o17"));
        assertDoesNotThrow(() -> Lexer.lex("0b10"));
    }

    // Octal escape sequences in string literals are rejected in strict mode
    @Test
    public void test_octal_string_escape_rejected() {
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("'\\07'"));
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("'\\1'"));
        assertThrows(SyntaxErrorException.class, () -> Lexer.lex("'\\8'"));
    }

    // A lone \0 (not followed by a digit) stays valid
    @Test
    public void test_null_escape_still_valid() {
        assertDoesNotThrow(() -> Lexer.lex("'\\0'"));
        assertDoesNotThrow(() -> Lexer.lex("'\\0a'"));
    }
}
