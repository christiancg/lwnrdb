package org.techhouse.unit.ex;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.DependencyInjectionFailed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DependencyInjectionFailedTest {
    // Create exception with cause and verify message is 'The dependency injection failed'
    @Test
    public void test_exception_with_cause_has_correct_message() {
        Exception cause = new RuntimeException("Some error");
    
        DependencyInjectionFailed exception = new DependencyInjectionFailed(cause);
    
        assertEquals("The dependency injection failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // Create exception with null cause
    @Test
    public void test_exception_with_null_cause() {
        DependencyInjectionFailed exception = new DependencyInjectionFailed(null);
    
        assertEquals("The dependency injection failed", exception.getMessage());
        assertNull(exception.getCause());
    }
}