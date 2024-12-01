package org.techhouse.unit.ejson.exceptions;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.BadImplementationCustomTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BadImplementationCustomTypeExceptionTest {
    // Create exception with valid class name and cause exception
    @Test
    public void test_create_exception_with_valid_class_name_and_cause() {
        String className = "MyCustomType";
        Exception cause = new IllegalArgumentException("Invalid argument");

        BadImplementationCustomTypeException exception = new BadImplementationCustomTypeException(className, cause);

        assertEquals("The custom type with class name MyCustomType has not been properly implemented", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    // Create exception with very long class name
    @Test
    public void test_create_exception_with_very_long_class_name() {
        String veryLongClassName = "com.organization.project.module.submodule.implementation.service.handler.processor.CustomTypeImplementation";
        Exception cause = new RuntimeException();

        BadImplementationCustomTypeException exception = new BadImplementationCustomTypeException(veryLongClassName, cause);

        assertEquals("The custom type with class name " + veryLongClassName + " has not been properly implemented", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
}