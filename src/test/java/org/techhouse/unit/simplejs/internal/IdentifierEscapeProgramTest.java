package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;

public class IdentifierEscapeProgramTest {
    private static final char ZWNJ = 0x200C;

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    // constructor is an ordinary identifier, so it binds like any other name
    @Test
    public void test_constructor_is_a_valid_binding_name() {
        assertEquals(1, num("var constructor = 1; constructor"));
    }

    // constructor is accepted as a parameter name
    @Test
    public void test_constructor_is_a_valid_parameter_name() {
        assertEquals(2, num("function f(constructor){ return constructor } f(2)"));
    }

    // constructor works as a shorthand destructuring parameter
    @Test
    public void test_constructor_shorthand_destructuring_param() {
        assertEquals(3, num("(({constructor}) => constructor)({constructor: 3})"));
    }

    // a class constructor still gets its special handling
    @Test
    public void test_class_constructor_still_parses() {
        assertEquals(4, num("class C { constructor(){ this.x = 4 } } new C().x"));
    }

    // an object literal may define a method named constructor
    @Test
    public void test_object_literal_constructor_method() {
        assertEquals(5, num("({ constructor(){ return 5 } }).constructor()"));
    }

    // a four-digit unicode escape names an identifier
    @Test
    public void test_unicode_escape_in_identifier() {
        assertEquals(1, num("var \\u0061 = 1; a"));
    }

    // a braced unicode escape names an identifier and may be followed by plain characters
    @Test
    public void test_braced_unicode_escape_in_identifier() {
        assertEquals(2, num("var \\u{61}bc = 2; abc"));
    }

    // a private name may be written with a unicode escape
    @Test
    public void test_unicode_escape_in_private_name() {
        assertEquals(7, num("class C { #\\u{6F}_ = 7; read(){ return this.#o_ } } new C().read()"));
    }

    // a zero-width non-joiner is a valid identifier part
    @Test
    public void test_zwj_zwnj_in_identifier_part() {
        assertEquals(3, num("var a" + ZWNJ + "b = 3; a" + ZWNJ + "b"));
    }

    // a reserved word written with an escape is a syntax error, not an identifier
    @Test
    public void test_escaped_keyword_is_a_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("\\u0069\\u0066 (true) {}"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("var tr\\u0075e = 1"));
    }

    // an escape decoding to a character that cannot appear in an identifier is rejected
    @Test
    public void test_escape_decoding_an_invalid_identifier_char_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Interpreter.run("var \\u0020x = 1"));
    }

    // a lone or truncated escape is still an unexpected character
    @Test
    public void test_incomplete_escape_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> Interpreter.run("var a\\ = 1"));
        assertThrows(UnexpectedCharacterException.class, () -> Interpreter.run("var a\\u12 = 1"));
        assertThrows(UnexpectedCharacterException.class, () -> Interpreter.run("var a\\u{12 = 1"));
    }

    // an astral-plane identifier is scanned by code point
    @Test
    public void test_astral_identifier() {
        assertEquals(5, num("var \\u{1D45A} = 5; \\u{1D45A}"));
    }
}
