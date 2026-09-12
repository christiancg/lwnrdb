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
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsUndefined;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.internal.Lexer;

public class LexerTest {
    @Test
    public void test_lex_whitespace_only() {
        final List<JsBaseElement> tokens = Lexer.lex("  \t\n\r ");
        assertEquals(1, tokens.size());
        assertInstanceOf(JsEOF.class, tokens.getFirst());
    }

    @Test
    public void test_lex_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("foo");
        assertEquals(2, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("foo", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_identifier_with_dollar_and_underscore() {
        final List<JsBaseElement> tokens = Lexer.lex("$my_var2");
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("$my_var2", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_keyword() {
        for (final var kw : List.of("function", "return", "const", "typeof", "instanceof", "else")) {
            final List<JsBaseElement> tokens = Lexer.lex(kw);
            assertInstanceOf(JsKeyword.class, tokens.getFirst(), kw);
            assertEquals(kw, ((JsKeyword) tokens.getFirst()).getValue());
        }
    }

    @Test
    public void test_lex_boolean() {
        assertTrue(((JsBoolean) Lexer.lex("true").getFirst()).getValue());
        assertFalse(((JsBoolean) Lexer.lex("false").getFirst()).getValue());
    }

    @Test
    public void test_lex_null() {
        final var first = Lexer.lex("null").getFirst();
        assertInstanceOf(JsNull.class, first);
        assertSame(JsNull.getInstance(), first);
    }

    @Test
    public void test_lex_undefined() {
        final var first = Lexer.lex("undefined").getFirst();
        assertInstanceOf(JsUndefined.class, first);
        assertSame(JsUndefined.getInstance(), first);
    }

    @Test
    public void test_lex_integer() {
        assertEquals(12345.0, ((JsNumber) Lexer.lex("12345").getFirst()).getValue());
    }

    @Test
    public void test_lex_float() {
        assertEquals(3.14, ((JsNumber) Lexer.lex("3.14").getFirst()).getValue());
    }

    @Test
    public void test_lex_leading_dot_float() {
        assertEquals(0.5, ((JsNumber) Lexer.lex(".5").getFirst()).getValue());
    }

    @Test
    public void test_lex_exponent() {
        assertEquals(1.5e-3, ((JsNumber) Lexer.lex("1.5e-3").getFirst()).getValue());
        assertEquals(2e10, ((JsNumber) Lexer.lex("2E10").getFirst()).getValue());
    }

    @Test
    public void test_lex_hashbang_skipped_at_start() {
        final List<JsBaseElement> tokens = Lexer.lex("#!/usr/bin/env node\nfoo");
        assertEquals(2, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("foo", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_hash_not_at_start_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Lexer.lex("foo\n#!bar"));
    }

    @Test
    public void test_lex_private_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("#field");
        assertInstanceOf(JsPrivateIdentifier.class, tokens.getFirst());
        assertEquals("field", ((JsPrivateIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_constructor_is_an_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("constructor");
        assertInstanceOf(JsIdentifier.class, tokens.getFirst());
        assertEquals("constructor", ((JsIdentifier) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_lone_hash_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Lexer.lex("a # b"));
    }

    @Test
    public void test_lex_line_comment_skipped() {
        final List<JsBaseElement> tokens = Lexer.lex("a // comment\nb");
        assertEquals(3, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.get(0));
        assertInstanceOf(JsIdentifier.class, tokens.get(1));
        assertInstanceOf(JsEOF.class, tokens.get(2));
    }

    @Test
    public void test_lex_block_comment_skipped() {
        final List<JsBaseElement> tokens = Lexer.lex("a /* c\nd */ b");
        assertEquals(3, tokens.size());
        assertInstanceOf(JsIdentifier.class, tokens.get(0));
        assertInstanceOf(JsIdentifier.class, tokens.get(1));
    }

    @Test
    public void test_lex_unterminated_block_comment_throws() {
        assertThrows(UnterminatedCommentException.class, () -> Lexer.lex("a /* unterminated"));
    }

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

    @Test
    public void test_lex_separators() {
        final List<JsBaseElement> tokens = Lexer.lex("(){}[];,");
        assertEquals(9, tokens.size());
        for (var i = 0; i < 8; i++) {
            assertInstanceOf(JsSeparator.class, tokens.get(i));
        }
        assertEquals('(', ((JsSeparator) tokens.getFirst()).getValue());
    }

    @Test
    public void test_lex_division_after_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("a / b");
        assertInstanceOf(JsOperator.class, tokens.get(1));
        assertEquals("/", ((JsOperator) tokens.get(1)).getValue());
    }

    @Test
    public void test_lex_divide_assign_after_identifier() {
        final List<JsBaseElement> tokens = Lexer.lex("a /= 2");
        assertInstanceOf(JsOperator.class, tokens.get(1));
        assertEquals("/=", ((JsOperator) tokens.get(1)).getValue());
    }

    @Test
    public void test_lex_unexpected_character_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Lexer.lex("@"));
    }

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

    @Test
    public void test_lex_with_positions_parallel_to_tokens() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a + b");
        assertEquals("a + b", result.source());
        assertEquals(result.tokens().size(), result.positions().size());
    }

    @Test
    public void test_lex_with_positions_offsets_and_line_column() {
        final Lexer.LexResult result = Lexer.lexWithPositions("let x =\n  42;");
        final List<SourcePosition> positions = result.positions();
        assertEquals(0, positions.getFirst().offset());
        assertEquals(3, positions.getFirst().length());
        assertEquals(1, positions.get(0).line());
        assertEquals(1, positions.get(0).column());
        assertEquals(6, positions.get(2).offset());
        assertEquals(1, positions.get(2).line());
        assertEquals(7, positions.get(2).column());
        assertEquals(10, positions.get(3).offset());
        assertEquals(2, positions.get(3).length());
        assertEquals(2, positions.get(3).line());
        assertEquals(3, positions.get(3).column());
        final SourcePosition eof = positions.getLast();
        assertEquals(13, eof.offset());
        assertEquals(0, eof.length());
        assertEquals(2, eof.line());
    }

    @Test
    public void test_newline_before_flag_across_line_break() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a\nb c");
        assertFalse(result.newlineBefore().get(0));
        assertTrue(result.newlineBefore().get(1));
        assertFalse(result.newlineBefore().get(2));
    }

    @Test
    public void test_newline_before_flag_multiline_block_comment() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a /*\n*/ b");
        assertTrue(result.newlineBefore().get(1));
    }

    @Test
    public void test_newline_before_flag_single_line_block_comment() {
        final Lexer.LexResult result = Lexer.lexWithPositions("a /* x */ b");
        assertFalse(result.newlineBefore().get(1));
    }
}
