package org.techhouse.unit.ex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.DependencyInjectionFailed;

public class DependencyInjectionFailedTest {
    @Test
    public void test_exception_with_cause_has_correct_message() {
        Exception cause = new RuntimeException("Some error");

        DependencyInjectionFailed exception = new DependencyInjectionFailed(cause);

        assertEquals("The dependency injection failed", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    public void test_exception_with_null_cause() {
        DependencyInjectionFailed exception = new DependencyInjectionFailed(null);

        assertEquals("The dependency injection failed", exception.getMessage());
        assertNull(exception.getCause());
    }
}
