package org.techhouse.unit.ex;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ex.TransactionOpFailedException;

public class TransactionOpFailedExceptionTest {
    @Test
    public void test_carries_the_error_code_of_the_failed_operation() {
        final var exception = new TransactionOpFailedException("SAVE", "400-2", "Entry size exceeds maximum");
        assertEquals("400-2", exception.getErrorCode());
    }

    @Test
    public void test_message_names_the_operation_and_the_detail() {
        final var exception = new TransactionOpFailedException("BULK_SAVE", "409-1", "Duplicate _id");
        assertTrue(exception.getMessage().contains("BULK_SAVE"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Duplicate _id"), exception.getMessage());
    }

    @Test
    public void test_a_null_error_code_is_tolerated() {
        final var exception = new TransactionOpFailedException("DELETE", null, "no code");
        assertNull(exception.getErrorCode());
    }
}
