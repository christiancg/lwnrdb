package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.DeleteSchemaResponse;

public class DeleteSchemaResponseTest {
    // Response carries OK status and message
    @Test
    public void test_response() {
        final var response = new DeleteSchemaResponse("deleted");
        assertEquals(OperationType.DELETE_SCHEMA, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("deleted", response.getMessage());
    }
}
