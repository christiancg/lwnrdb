package org.techhouse.unit.ejson.validate;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.validate.SchemaValidationResult;

public class SchemaValidationResultTest {
    // valid() reports success with no errors
    @Test
    public void test_valid_has_no_errors() {
        final var result = SchemaValidationResult.valid();
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    // invalid(List) keeps the supplied error messages and reports failure
    @Test
    public void test_invalid_with_list_keeps_errors() {
        final var result = SchemaValidationResult.invalid(List.of("a", "b"));
        assertFalse(result.isValid());
        assertEquals(List.of("a", "b"), result.getErrors());
    }

    // invalid(String) wraps a single message
    @Test
    public void test_invalid_with_single_message() {
        final var result = SchemaValidationResult.invalid("boom");
        assertFalse(result.isValid());
        assertEquals(List.of("boom"), result.getErrors());
    }

    // invalid(List) defensively copies its input, so later mutations don't leak in
    @Test
    public void test_invalid_defensively_copies_errors() {
        final var source = new ArrayList<>(List.of("a"));
        final var result = SchemaValidationResult.invalid(source);
        source.add("b");
        assertEquals(List.of("a"), result.getErrors());
    }

    // valid() carries no errors and no warnings
    @Test
    public void test_valid_has_no_warnings() {
        assertTrue(SchemaValidationResult.valid().getWarnings().isEmpty());
    }

    // of() with no errors and no warnings is valid
    @Test
    public void test_of_empty_is_valid() {
        final var result = SchemaValidationResult.of(List.of(), List.of());
        assertTrue(result.isValid());
        assertTrue(result.getWarnings().isEmpty());
    }

    // of() with only warnings is still valid but keeps the warnings
    @Test
    public void test_of_warnings_only_is_valid() {
        final var result = SchemaValidationResult.of(List.of(), List.of("w1"));
        assertTrue(result.isValid());
        assertEquals(List.of("w1"), result.getWarnings());
    }

    // of() with errors is invalid regardless of warnings
    @Test
    public void test_of_with_errors_is_invalid() {
        final var result = SchemaValidationResult.of(List.of("e1"), List.of("w1"));
        assertFalse(result.isValid());
        assertEquals(List.of("e1"), result.getErrors());
        assertEquals(List.of("w1"), result.getWarnings());
    }

    // of() defensively copies both input lists, so later mutations don't leak in
    @Test
    public void test_of_defensively_copies_inputs() {
        final var errorsSource = new ArrayList<>(List.of("e1"));
        final var warningsSource = new ArrayList<>(List.of("w1"));
        final var result = SchemaValidationResult.of(errorsSource, warningsSource);
        errorsSource.add("e2");
        warningsSource.add("w2");
        assertEquals(List.of("e1"), result.getErrors());
        assertEquals(List.of("w1"), result.getWarnings());
    }
}
