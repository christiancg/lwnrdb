package org.techhouse.unit.ex;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.InvalidPortException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InvalidPortExceptionTest {
    // Create exception with valid port string and cause exception
    @Test
    public void test_create_exception_with_port_and_cause() {
        String invalidPort = "65536";
        NumberFormatException cause = new NumberFormatException("Port out of range");
    
        InvalidPortException exception = new InvalidPortException(invalidPort, cause);
    
        assertEquals("Invalid port: 65536", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // Create exception with null port string
    @Test
    public void test_create_exception_with_null_port() {
        IllegalArgumentException cause = new IllegalArgumentException("Port cannot be null");
    
        InvalidPortException exception = new InvalidPortException(null, cause);
    
        assertEquals("Invalid port: null", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}