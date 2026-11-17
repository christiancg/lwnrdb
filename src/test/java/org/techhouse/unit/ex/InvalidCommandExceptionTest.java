package org.techhouse.unit.ex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.InvalidCommandException;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;

public class InvalidCommandExceptionTest {
    @Test
    public void test_create_with_cause_exception() {
        Exception cause = new RuntimeException("Original error");

        InvalidCommandException exception = new InvalidCommandException(cause);

        assertEquals("The command is not valid", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    public void test_create_with_null_cause() {
        InvalidCommandException exception = new InvalidCommandException(null);

        assertEquals("The command is not valid", exception.getMessage());
        assertNull(exception.getCause());
        assertNull(exception.getRequestType());
    }

    @Test
    public void test_the_message_is_the_error_code_message_and_the_type_is_kept() {
        final var exception = new InvalidCommandException(OperationType.SAVE, new RuntimeException("bad"));

        assertEquals(ErrorCode.INVALID_COMMAND.getDefaultMessage(), exception.getMessage());
        assertEquals(OperationType.SAVE, exception.getRequestType());
    }
}
