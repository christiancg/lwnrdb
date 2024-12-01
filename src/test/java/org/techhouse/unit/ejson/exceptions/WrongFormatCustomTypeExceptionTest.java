package org.techhouse.unit.ejson.exceptions;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WrongFormatCustomTypeExceptionTest {
    // Create exception with className and cause exception parameters
    @Test
    public void test_create_exception_with_classname_and_cause() {
        String className = "TestClass";
        Exception cause = new RuntimeException("Test cause");

        WrongFormatCustomTypeException exception = new WrongFormatCustomTypeException(className, cause);

        assertEquals("The format for the custom type with class name TestClass is wrong.", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // Pass null as className parameter
    @Test
    public void test_create_exception_with_null_classname() {
        String className = null;

        WrongFormatCustomTypeException exception = new WrongFormatCustomTypeException(className);

        assertEquals("The format for the custom type with class name null is wrong.", exception.getMessage());
        assertNull(exception.getCause());
    }
}