package org.techhouse.unit.ejson.exceptions;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.exceptions.NonRegisteredCustomTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NonRegisteredCustomTypeExceptionTest {
    // Exception is thrown with correct custom type name in message
    @Test
    public void test_exception_message_contains_custom_type_name() {
        String customTypeName = "MyCustomType";
        NonRegisteredCustomTypeException exception = new NonRegisteredCustomTypeException(customTypeName);

        String expectedMessage = "The custom type MyCustomType has not been registered";
        assertEquals(expectedMessage, exception.getMessage());
    }

    // Passing null as custom type name
    @Test
    public void test_exception_message_with_null_type_name() {
        NonRegisteredCustomTypeException exception = new NonRegisteredCustomTypeException(null);

        String expectedMessage = "The custom type null has not been registered";
        assertEquals(expectedMessage, exception.getMessage());
    }
}