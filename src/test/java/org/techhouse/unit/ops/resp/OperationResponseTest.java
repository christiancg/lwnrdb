package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OperationResponseTest {
    // Create OperationResponse with valid type, status and message
    @Test
    public void create_operation_response_with_valid_parameters() {
        OperationType type = OperationType.SAVE;
        OperationStatus status = OperationStatus.OK;
        String message = "Operation completed successfully";

        OperationResponse response = new OperationResponse(type, status, message);

        assertEquals(type, response.getType());
        assertEquals(status, response.getStatus());
        assertEquals(message, response.getMessage());
    }

    // Create OperationResponse with null message
    @Test
    public void create_operation_response_with_null_message() {
        OperationType type = OperationType.DELETE;
        OperationStatus status = OperationStatus.OK;
        String message = null;

        OperationResponse response = new OperationResponse(type, status, message);

        assertEquals(type, response.getType());
        assertEquals(status, response.getStatus());
        assertNull(response.getMessage());
    }
}