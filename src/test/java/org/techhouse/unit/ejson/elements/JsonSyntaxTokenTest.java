package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;

import static org.junit.jupiter.api.Assertions.*;

public class JsonSyntaxTokenTest {
    // Verify fromChar() correctly maps each valid character to its corresponding JsonSyntaxToken
    @Test
    public void test_valid_chars_map_to_correct_tokens() {
        assertEquals(JsonSyntaxToken.LEFT_BRACE, JsonSyntaxToken.fromChar('{'));
        assertEquals(JsonSyntaxToken.RIGHT_BRACE, JsonSyntaxToken.fromChar('}'));
        assertEquals(JsonSyntaxToken.LEFT_BRACKET, JsonSyntaxToken.fromChar('['));
        assertEquals(JsonSyntaxToken.RIGHT_BRACKET, JsonSyntaxToken.fromChar(']'));
        assertEquals(JsonSyntaxToken.COMMA, JsonSyntaxToken.fromChar(','));
        assertEquals(JsonSyntaxToken.COLON, JsonSyntaxToken.fromChar(':'));
    }

    // Test fromChar() with invalid/unsupported characters throws UnexpectedCharacterException
    @Test
    public void test_invalid_chars_throw_exception() {
        char invalidChar = 'x';
        UnexpectedCharacterException exception = assertThrows(
            UnexpectedCharacterException.class,
            () -> JsonSyntaxToken.fromChar(invalidChar)
        );
        assertEquals("Unexpected character x at position: 0", exception.getMessage());
    }

    // Return consistent hash codes for the same syntax token value
    @Test
    public void test_same_token_returns_same_hashcode() {
        JsonSyntaxToken token1 = JsonSyntaxToken.fromChar('{');
        JsonSyntaxToken token2 = JsonSyntaxToken.fromChar('{');

        int hashCode1 = token1.hashCode();
        int hashCode2 = token2.hashCode();

        assertEquals(hashCode1, hashCode2);
    }

    // Verify hash code consistency when using predefined token constants
    @Test
    public void test_predefined_token_constant_hashcode_consistency() {
        JsonSyntaxToken leftBrace1 = JsonSyntaxToken.LEFT_BRACE;
        JsonSyntaxToken leftBrace2 = JsonSyntaxToken.LEFT_BRACE;
        JsonSyntaxToken rightBrace = JsonSyntaxToken.RIGHT_BRACE;

        assertEquals(leftBrace1.hashCode(), leftBrace2.hashCode());
        Assertions.assertNotEquals(leftBrace1.hashCode(), rightBrace.hashCode());
    }

    // Compare two JsonSyntaxToken instances with same SyntaxToken value returns true
    @Test
    public void test_equals_with_same_syntax_token_returns_true() {
        JsonSyntaxToken token1 = JsonSyntaxToken.LEFT_BRACE;
        JsonSyntaxToken token2 = JsonSyntaxToken.LEFT_BRACE;

        boolean result = token1.equals(token2);

        assertTrue(result);
    }

    // Compare JsonSyntaxToken with null returns false
    @Test
    public void test_equals_with_null_returns_false() {
        JsonSyntaxToken token = JsonSyntaxToken.COLON;

        boolean result = token.equals(null);

        assertFalse(result);
    }

    // Verify deepCopy returns a new JsonSyntaxToken instance with same value as original
    @Test
    public void test_deep_copy_returns_equal_token() {
        JsonSyntaxToken original = JsonSyntaxToken.LEFT_BRACE;

        JsonBaseElement copy = original.deepCopy();

        assertNotSame(original, copy);
        assertEquals(original, copy);
    }

    // Test deepCopy with null value (should not be possible due to constructor)
    @Test
    public void test_deep_copy_null_value_not_possible() {
        JsonSyntaxToken token = JsonSyntaxToken.fromChar('{');

        JsonBaseElement copy = token.deepCopy();

        assertNotNull(copy);
        assertInstanceOf(JsonSyntaxToken.class, copy);
        assertEquals(token, copy);
    }
}