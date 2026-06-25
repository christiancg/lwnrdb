package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.DropIndexResponse;

public class DropIndexResponseTest {
    // Create DropIndexResponse with OK status and success message
    @Test
    public void test_create_drop_index_response_with_ok_status() {
        String successMessage = "Index dropped successfully";

        DropIndexResponse response = new DropIndexResponse(successMessage);

        assertEquals(OperationType.DROP_INDEX, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    // Create DropIndexResponse with null message
    @Test
    public void test_create_drop_index_response_with_null_message() {
        DropIndexResponse response = new DropIndexResponse(null);

        assertEquals(OperationType.DROP_INDEX, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }
}
