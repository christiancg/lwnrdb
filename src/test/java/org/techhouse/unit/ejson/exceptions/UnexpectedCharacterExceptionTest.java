package org.techhouse.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;

public class UnexpectedCharacterExceptionTest {
    @Test
    public void test_create_exception_with_valid_character_and_position() {
        char testChar = '$';
        int position = 5;

        UnexpectedCharacterException exception = new UnexpectedCharacterException(testChar, position);

        assertEquals("Unexpected character $ at position: 5", exception.getMessage());
    }

    @Test
    public void test_create_exception_with_null_character() {
        int position = 10;

        UnexpectedCharacterException exception = new UnexpectedCharacterException(null, position);

        assertEquals("Unexpected character null at position: 10", exception.getMessage());
    }
}
