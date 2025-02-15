package org.techhouse.unit.ex;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.InvalidCommandException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class InvalidCommandExceptionTest {
    // Create exception with underlying cause exception
    @Test
    public void test_create_with_cause_exception() {
        Exception cause = new RuntimeException("Original error");
    
        InvalidCommandException exception = new InvalidCommandException(cause);
    
        assertEquals("The command is not valid", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // Create exception with null cause exception
    @Test
    public void test_create_with_null_cause() {
        InvalidCommandException exception = new InvalidCommandException(null);
    
        assertEquals("The command is not valid", exception.getMessage());
        assertNull(exception.getCause());
    }
}