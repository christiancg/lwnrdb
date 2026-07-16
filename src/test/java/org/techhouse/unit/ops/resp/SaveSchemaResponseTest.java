package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.SaveSchemaResponse;

public class SaveSchemaResponseTest {
    // Response carries OK status, message and warnings
    @Test
    public void test_response_with_warnings() {
        final var response = new SaveSchemaResponse("saved", List.of("w1"));
        assertEquals(OperationType.SAVE_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("saved", response.getMessage());
        assertEquals(List.of("w1"), response.getWarnings());
    }

    // Empty warnings list is preserved
    @Test
    public void test_response_without_warnings() {
        final var response = new SaveSchemaResponse("saved", List.of());
        assertTrue(response.getWarnings().isEmpty());
    }
}
