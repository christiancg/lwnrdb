package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.validations.ValidationResult;

public class ValidationResultTest {

    @Test
    public void ok_returnsValidResult() {
        final var result = ValidationResult.ok();
        assertTrue(result.isValid());
        assertNull(result.getErrorMessage());
    }

    @Test
    public void fail_returnsInvalidResultWithMessage() {
        final var result = ValidationResult.fail("something went wrong");
        assertFalse(result.isValid());
        assertEquals("something went wrong", result.getErrorMessage());
    }
}
