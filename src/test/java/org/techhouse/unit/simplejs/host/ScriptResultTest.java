package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.host.ScriptResult;

public class ScriptResultTest {
    // A value result carries the EJson value and is not an error
    @Test
    public void test_value_result() {
        final var result = ScriptResult.value(new JsonString("hello"));
        assertFalse(result.isError());
        assertEquals("hello", result.getValue().asJsonString().getValue());
        assertNull(result.getErrorName());
        assertNull(result.getErrorMessage());
    }

    // An error result carries name + message and no value
    @Test
    public void test_error_result() {
        final var result = ScriptResult.error("TypeError", "boom");
        assertTrue(result.isError());
        assertNull(result.getValue());
        assertEquals("TypeError", result.getErrorName());
        assertEquals("boom", result.getErrorMessage());
    }
}
