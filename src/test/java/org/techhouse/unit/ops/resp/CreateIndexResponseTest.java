package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.CreateIndexResponse;

public class CreateIndexResponseTest {
    // Create index response with OK status and success message
    @Test
    public void test_create_index_response_with_ok_status() {
        String successMessage = "Index created successfully";
        CreateIndexResponse response = new CreateIndexResponse(successMessage);

        assertEquals(OperationType.CREATE_INDEX, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    // Create response with null message
    @Test
    public void test_create_index_response_with_null_message() {
        CreateIndexResponse response = new CreateIndexResponse(null);

        assertEquals(OperationType.CREATE_INDEX, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }
}
