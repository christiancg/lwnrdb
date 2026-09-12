package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class LexicalFormsProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_control_escapes() {
        assertEquals(3, num("'\\f\\v\\0'.length"));
        assertEquals(0, num("'\\0'.charCodeAt(0)"));
        assertEquals(3, num("'a\\bb'.length"));
    }

    @Test
    public void test_hex_escape() {
        assertEquals("A", str("'\\x41'"));
    }

    @Test
    public void test_unicode_escape() {
        assertEquals("A", str("'\\u0041'"));
    }

    @Test
    public void test_braced_unicode_escape() {
        assertEquals(2, num("'\\u{1F600}'.length"));
    }

    @Test
    public void test_malformed_unicode_escape() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("'\\uZZZZ'"));
    }

    @Test
    public void test_out_of_range_code_point() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("'\\u{110000}'"));
    }

    @Test
    public void test_malformed_hex_escape() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("'\\xZZ'"));
    }

    @Test
    public void test_unrecognised_escape() {
        assertEquals("q", str("'\\q'"));
    }

    @Test
    public void test_line_continuation() {
        assertEquals("ab", str("'a\\\nb'"));
    }

    @Test
    public void test_line_continuation_over_crlf() {
        assertEquals("ab", str("'a\\\r\nb'"));
    }

    @Test
    public void test_unterminated_string() {
        assertThrows(UnterminatedStringException.class, () -> Interpreter.run("'abc"));
    }

    @Test
    public void test_unterminated_template() {
        assertThrows(UnterminatedTemplateException.class, () -> Interpreter.run("`abc"));
    }

    @Test
    public void test_template_line_endings() {
        assertEquals(3, num("String.raw`a\r\nb`.length"));
        assertEquals(3, num("`a\nb`.length"));
    }

    @Test
    public void test_nested_template() {
        assertEquals("ab1c", str("`a${`b${1}`}c`"));
    }

    @Test
    public void test_numeric_separators() {
        assertEquals("1000:16:2:10.5", str("1_000 + ':' + 0x1_0 + ':' + 0b1_0 + ':' + 1_0.5"));
    }

    @Test
    public void test_bigint_separator() {
        assertEquals("10", str("String(1_0n)"));
    }

    @Test
    public void test_legacy_octal_literal() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("01"));
    }

    @Test
    public void test_octal_escape_sequence() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("'\\01'"));
    }

    @Test
    public void test_hashbang() {
        assertEquals(2, num("#!/usr/bin/env node\n1 + 1"));
    }

    @Test
    public void test_identifier_escapes() {
        assertEquals(5, num("let \\u0061 = 5; a"));
        assertEquals(6, num("let \\u{62} = 6; b"));
    }

    @Test
    public void test_escaped_keyword() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("let \\u0069f = 1;"));
    }

    @Test
    public void test_stray_backslash() {
        assertThrows(UnexpectedCharacterException.class, () -> Interpreter.run("let a = 1; a \\ 2"));
    }

    @Test
    public void test_regex_after_a_keyword() {
        assertEquals("object", str("typeof /a/"));
    }

    @Test
    public void test_regex_after_a_block() {
        assertEquals("a", str("{} /a/.source"));
    }

    @Test
    public void test_regex_after_return() {
        assertEquals("a", str("function f() { return /a/.source; } f()"));
    }

    @Test
    public void test_division_after_a_value() {
        assertEquals(2, num("(4) / 2"));
        assertEquals(2, num("[4][0] / 2"));
        assertEquals(2, num("const x = 4; x / 2"));
    }

    @Test
    public void test_comment_forms() {
        assertEquals(3, num("/* a */ 1 // b\n + 2"));
    }
}
