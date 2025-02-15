package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.DeleteResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DeleteResponseTest {
    // Create DeleteResponse with OK status and success message
    @Test
    public void test_create_delete_response_with_ok_status_and_message() {
        String successMessage = "Successfully deleted";
        DeleteResponse response = new DeleteResponse(OperationStatus.OK, successMessage);

        assertEquals(OperationType.DELETE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    // Create DeleteResponse with null message
    @Test
    public void test_create_delete_response_with_null_message() {
        DeleteResponse response = new DeleteResponse(OperationStatus.OK, null);

        assertEquals(OperationType.DELETE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }
}