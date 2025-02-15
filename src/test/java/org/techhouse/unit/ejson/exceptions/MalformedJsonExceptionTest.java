package org.techhouse.unit.ejson.exceptions;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.MalformedJsonException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MalformedJsonExceptionTest {
    // Create exception with error message and verify message is accessible
    @Test
    public void test_exception_message_is_accessible() {
        String errorMessage = "Invalid JSON format";
    
        MalformedJsonException exception = new MalformedJsonException(errorMessage);
    
        assertEquals(errorMessage, exception.getMessage());
    }

    // Create exception with null error message
    @Test
    public void test_exception_with_null_message() {
        String errorMessage = null;
    
        MalformedJsonException exception = new MalformedJsonException(errorMessage);
    
        assertNull(exception.getMessage());
    }
}