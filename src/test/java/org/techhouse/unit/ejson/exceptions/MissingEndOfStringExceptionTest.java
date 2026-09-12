package org.techhouse.unit.ejson.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;

public class MissingEndOfStringExceptionTest {
    @Test
    public void test_default_message() {
        MissingEndOfStringException exception = new MissingEndOfStringException();

        assertEquals("Missing end of string on json", exception.getMessage());
    }

    @Test
    public void test_nested_exception() {
        MissingEndOfStringException innerException = new MissingEndOfStringException();
        RuntimeException outerException = new RuntimeException("Outer exception", innerException);

        assertEquals(innerException, outerException.getCause());
        assertEquals("Missing end of string on json", outerException.getCause().getMessage());
    }
}
