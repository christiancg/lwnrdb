package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;
import org.techhouse.ejson.internal.Lexer;
import org.techhouse.ioc.IocContainer;

public class LexerScanTest {
    private static List<JsonBaseElement> lex(String input) {
        return Lexer.lex(input);
    }

    private static char syntaxChar(JsonSyntaxToken token) {
        if (token.equals(JsonSyntaxToken.LEFT_BRACE)) {
            return '{';
        }
        if (token.equals(JsonSyntaxToken.RIGHT_BRACE)) {
            return '}';
        }
        if (token.equals(JsonSyntaxToken.LEFT_BRACKET)) {
            return '[';
        }
        if (token.equals(JsonSyntaxToken.RIGHT_BRACKET)) {
            return ']';
        }
        return token.equals(JsonSyntaxToken.COMMA) ? ',' : ':';
    }

    private static String describe(List<JsonBaseElement> tokens) {
        final var sb = new StringBuilder();
        for (final var token : tokens) {
            if (!sb.isEmpty()) {
                sb.append('|');
            }
            switch (token) {
                case JsonCustom<?> custom -> sb.append("custom:").append(custom.getValue());
                case JsonString string -> sb.append("str:").append(string.getValue());
                case JsonNumber number -> sb.append("num:").append(number.getValue());
                case JsonBoolean bool -> sb.append("bool:").append(bool.getValue());
                case JsonNull ignored -> sb.append("null");
                case JsonSyntaxToken syntax -> sb.append("syn:").append(syntaxChar(syntax));
                default -> sb.append('?');
            }
        }
        return sb.toString();
    }

    @Test
    public void test_object_with_every_value_kind() {
        final var tokens = lex("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":false,\"e\":null}");
        assertEquals("syn:{|str:a|syn::|num:1|syn:,|str:b|syn::|str:x|syn:,|str:c|syn::|bool:true|syn:,"
                + "|str:d|syn::|bool:false|syn:,|str:e|syn::|null|syn:}", describe(tokens));
    }

    @Test
    public void test_string_advance_lands_exactly_after_the_closing_quote() {
        assertEquals("str:a|syn:,|str:b", describe(lex("\"a\",\"b\"")));
        assertEquals("syn:[|str:|syn:,|str:|syn:]", describe(lex("[\"\",\"\"]")));
    }

    @Test
    public void test_number_advance_lands_exactly_after_the_number() {
        assertEquals("num:1|syn:,|num:2", describe(lex("1,2")));
        assertEquals("syn:[|num:-1.5|syn:,|num:0|syn:]", describe(lex("[-1.5,0]")));
    }

    @Test
    public void test_exponent_forms() {
        assertEquals("num:100000", describe(lex("1e5")));
        assertEquals("num:100000", describe(lex("1E5")));
        assertEquals("num:100000", describe(lex("1e+5")));
        assertEquals("num:1.0E-5", describe(lex("1e-5")));
        assertEquals("num:1500", describe(lex("1.5e3")));
    }

    @Test
    public void test_exponent_marker_without_digits_is_not_consumed() {
        final var thrown = assertThrows(UnexpectedCharacterException.class, () -> lex("1e"));
        assertNotNull(thrown);
    }

    @Test
    public void test_exponent_sign_without_digits_is_not_consumed() {
        assertThrows(UnexpectedCharacterException.class, () -> lex("1e+"));
    }

    @Test
    public void test_escapes_are_decoded() {
        assertEquals("str:a\"b", describe(lex("\"a\\\"b\"")));
        assertEquals("str:a\\b", describe(lex("\"a\\\\b\"")));
        assertEquals("str:a\nb", describe(lex("\"a\\nb\"")));
        assertEquals("str:a\tb", describe(lex("\"a\\tb\"")));
        assertEquals("str:a/b", describe(lex("\"a\\/b\"")));
    }

    @Test
    public void test_unicode_escape_is_decoded() {
        assertEquals("str:café", describe(lex("\"caf\\u00e9\"")));
        assertEquals("str:日", describe(lex("\"\\u65e5\"")));
    }

    @Test
    public void test_truncated_unicode_escape_is_kept_verbatim() {
        assertEquals("str:\\u00", describe(lex("\"\\u00\"")));
    }

    @Test
    public void test_invalid_unicode_digits_are_kept_verbatim() {
        assertEquals("str:\\uzzzz", describe(lex("\"\\uzzzz\"")));
    }

    @Test
    public void test_unknown_escape_is_kept_verbatim() {
        assertEquals("str:\\q", describe(lex("\"\\q\"")));
    }

    @Test
    public void test_custom_type_token_is_recognised() {
        IocContainer.get(EJson.class);
        assertEquals("custom:#geo(40.0,-74.0)", describe(lex("\"#geo(40.0,-74.0)\"")));
    }

    @Test
    public void test_string_that_merely_starts_with_hash_is_a_plain_string() {
        assertEquals("str:#notacustomtype", describe(lex("\"#notacustomtype\"")));
    }

    @Test
    public void test_whitespace_between_tokens_is_skipped() {
        assertEquals("syn:{|str:a|syn::|num:1|syn:}", describe(lex("{ \"a\" :\t1\n}")));
        assertEquals("syn:[|num:1|syn:]", describe(lex("[\b1\r]")));
    }

    @Test
    public void test_unterminated_string_throws() {
        assertThrows(MissingEndOfStringException.class, () -> lex("\"abc"));
    }

    @Test
    public void test_unexpected_character_throws() {
        assertThrows(UnexpectedCharacterException.class, () -> lex("@"));
    }

    @Test
    public void test_empty_input_produces_no_tokens() {
        assertTrue(lex("").isEmpty());
    }

    @Test
    public void test_nested_structures_scan_linearly() {
        final var nested = "{\"a\":[{\"b\":[1,2,{\"c\":\"d\"}]}]}";
        assertEquals("syn:{|str:a|syn::|syn:[|syn:{|str:b|syn::|syn:[|num:1|syn:,|num:2|syn:,|syn:{|str:c"
                + "|syn::|str:d|syn:}|syn:]|syn:}|syn:]|syn:}", describe(lex(nested)));
    }

    @Test
    public void test_large_payload_scans_without_quadratic_blowup() {
        final var builder = new StringBuilder("{");
        for (var i = 0; i < 20000; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append("field").append(i).append("\":").append(i);
        }
        builder.append('}');
        final var payload = builder.toString();

        final var tokens = lex(payload);

        assertEquals(20000 * 4 + 1, tokens.size());
        assertInstanceOf(JsonSyntaxToken.class, tokens.getFirst());
        assertInstanceOf(JsonSyntaxToken.class, tokens.getLast());
    }
}
