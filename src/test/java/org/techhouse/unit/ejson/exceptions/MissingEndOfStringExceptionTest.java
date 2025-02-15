package org.techhouse.unit.ejson.exceptions;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MissingEndOfStringExceptionTest {
    // Verify exception is thrown with correct default message
    @Test
    public void test_default_message() {
        MissingEndOfStringException exception = new MissingEndOfStringException();

        assertEquals("Missing end of string on json", exception.getMessage());
    }

    // Test exception handling when nested inside other exceptions
    @Test
    public void test_nested_exception() {
        MissingEndOfStringException innerException = new MissingEndOfStringException();
        RuntimeException outerException = new RuntimeException("Outer exception", innerException);

        assertEquals(innerException, outerException.getCause());
        assertEquals("Missing end of string on json", outerException.getCause().getMessage());
    }
}