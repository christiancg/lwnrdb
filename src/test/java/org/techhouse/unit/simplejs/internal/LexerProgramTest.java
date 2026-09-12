package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.internal.Lexer;

public class LexerProgramTest {
    private static List<JsType> types(String source) {
        return Lexer.lex(source).stream().map(JsBaseElement::getType).toList();
    }

    @Test
    public void test_lex_full_function_declaration() {
        final var source = "function add(a, b) { const sum = a + b; return sum; }";
        final var types = types(source);
        assertEquals(List.of(JsType.KEYWORD, JsType.IDENTIFIER, JsType.SEPARATOR, JsType.IDENTIFIER, JsType.SEPARATOR,
                JsType.IDENTIFIER, JsType.SEPARATOR, JsType.SEPARATOR, JsType.KEYWORD, JsType.IDENTIFIER,
                JsType.OPERATOR, JsType.IDENTIFIER, JsType.OPERATOR, JsType.IDENTIFIER, JsType.SEPARATOR,
                JsType.KEYWORD, JsType.IDENTIFIER, JsType.SEPARATOR, JsType.SEPARATOR, JsType.EOF), types);
    }

    @Test
    public void test_lex_program_with_comments_template_and_regex() {
        final var source = """
                // greeting builder
                const name = "world";
                const msg = `hello ${name}!`; /* trailing */
                const re = /a.b/g;
                """;
        final var tokens = Lexer.lex(source);
        final var types = tokens.stream().map(JsBaseElement::getType).toList();
        assertTrue(types.contains(JsType.TEMPLATE_STRING));
        assertTrue(types.contains(JsType.REGEX));
        assertFalse(types.contains(null));
        assertInstanceOf(JsEOF.class, tokens.getLast());
    }

    @Test
    public void test_lex_arrow_and_optional_chaining() {
        final var types = types("const f = a => a?.b ?? 0;");
        assertEquals(List.of(JsType.KEYWORD, JsType.IDENTIFIER, JsType.OPERATOR, JsType.IDENTIFIER, JsType.OPERATOR,
                JsType.IDENTIFIER, JsType.OPERATOR, JsType.IDENTIFIER, JsType.OPERATOR, JsType.NUMBER, JsType.SEPARATOR,
                JsType.EOF), types);
    }
}
