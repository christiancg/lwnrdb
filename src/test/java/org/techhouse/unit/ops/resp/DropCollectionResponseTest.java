package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.DropCollectionResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DropCollectionResponseTest {
    // Create DropCollectionResponse with OK status and success message
    @Test
    public void test_create_drop_collection_response_with_ok_status() {
        String successMessage = "Collection dropped successfully";
    
        DropCollectionResponse response = new DropCollectionResponse(OperationStatus.OK, successMessage);
    
        assertEquals(OperationType.DROP_COLLECTION, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }
}