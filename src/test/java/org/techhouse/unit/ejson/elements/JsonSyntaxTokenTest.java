package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;

public class JsonSyntaxTokenTest {
    @Test
    public void test_valid_chars_map_to_correct_tokens() {
        assertEquals(JsonSyntaxToken.LEFT_BRACE, JsonSyntaxToken.fromChar('{'));
        assertEquals(JsonSyntaxToken.RIGHT_BRACE, JsonSyntaxToken.fromChar('}'));
        assertEquals(JsonSyntaxToken.LEFT_BRACKET, JsonSyntaxToken.fromChar('['));
        assertEquals(JsonSyntaxToken.RIGHT_BRACKET, JsonSyntaxToken.fromChar(']'));
        assertEquals(JsonSyntaxToken.COMMA, JsonSyntaxToken.fromChar(','));
        assertEquals(JsonSyntaxToken.COLON, JsonSyntaxToken.fromChar(':'));
    }

    @Test
    public void test_invalid_chars_throw_exception() {
        char invalidChar = 'x';
        UnexpectedCharacterException exception = assertThrows(UnexpectedCharacterException.class,
                () -> JsonSyntaxToken.fromChar(invalidChar));
        assertEquals("Unexpected character x at position: 0", exception.getMessage());
    }

    @Test
    public void test_same_token_returns_same_hashcode() {
        JsonSyntaxToken token1 = JsonSyntaxToken.fromChar('{');
        JsonSyntaxToken token2 = JsonSyntaxToken.fromChar('{');

        int hashCode1 = token1.hashCode();
        int hashCode2 = token2.hashCode();

        assertEquals(hashCode1, hashCode2);
    }

    @Test
    public void test_predefined_token_constant_hashcode_consistency() {
        JsonSyntaxToken leftBrace1 = JsonSyntaxToken.LEFT_BRACE;
        JsonSyntaxToken leftBrace2 = JsonSyntaxToken.LEFT_BRACE;
        JsonSyntaxToken rightBrace = JsonSyntaxToken.RIGHT_BRACE;

        assertEquals(leftBrace1.hashCode(), leftBrace2.hashCode());
        Assertions.assertNotEquals(leftBrace1.hashCode(), rightBrace.hashCode());
    }

    @Test
    public void test_equals_with_same_syntax_token_returns_true() {
        JsonSyntaxToken token1 = JsonSyntaxToken.LEFT_BRACE;
        JsonSyntaxToken token2 = JsonSyntaxToken.LEFT_BRACE;

        boolean result = token1.equals(token2);

        assertTrue(result);
    }

    @Test
    public void test_equals_with_null_returns_false() {
        JsonSyntaxToken token = JsonSyntaxToken.COLON;

        boolean result = token.equals(null); // NOPMD - intentional equals(null) contract test

        assertFalse(result);
    }

    @Test
    public void test_deep_copy_returns_equal_token() {
        JsonSyntaxToken original = JsonSyntaxToken.LEFT_BRACE;

        JsonBaseElement copy = original.deepCopy();

        assertNotSame(original, copy);
        assertEquals(original, copy);
    }

    @Test
    public void test_deep_copy_null_value_not_possible() {
        JsonSyntaxToken token = JsonSyntaxToken.fromChar('{');

        JsonBaseElement copy = token.deepCopy();

        assertNotNull(copy);
        assertInstanceOf(JsonSyntaxToken.class, copy);
        assertEquals(token, copy);
    }
}
