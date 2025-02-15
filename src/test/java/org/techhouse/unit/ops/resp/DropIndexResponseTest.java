package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.DropIndexResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DropIndexResponseTest {
    // Create DropIndexResponse with OK status and success message
    @Test
    public void test_create_drop_index_response_with_ok_status() {
        String successMessage = "Index dropped successfully";
    
        DropIndexResponse response = new DropIndexResponse(OperationStatus.OK, successMessage);
    
        assertEquals(OperationType.DROP_INDEX, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    // Create DropIndexResponse with null status
    @Test
    public void test_create_drop_index_response_with_null_status() {
        String message = "Some message";
    
        DropIndexResponse response = new DropIndexResponse(null, message);
    
        assertEquals(OperationType.DROP_INDEX, response.getType());
        assertNull(response.getStatus());
        assertEquals(message, response.getMessage());
    }
}