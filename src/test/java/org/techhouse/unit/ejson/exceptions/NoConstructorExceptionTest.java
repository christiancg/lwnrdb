package org.techhouse.unit.ejson.exceptions;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.NoConstructorException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NoConstructorExceptionTest {
    // Create exception with valid class parameter
    @Test
    public void test_create_exception_with_valid_class() {
        NoConstructorException exception = new NoConstructorException(String.class);

        assertEquals("Couldn't find a suitable constructor for class java.lang.String", 
            exception.getMessage());
    }

    // Create exception with null class parameter
    @Test
    public void test_create_exception_with_null_class() {
        assertThrows(NullPointerException.class, () -> new NoConstructorException(null));
    }
}