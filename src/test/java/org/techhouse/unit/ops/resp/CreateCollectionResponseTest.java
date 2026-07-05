package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.CreateCollectionResponse;

public class CreateCollectionResponseTest {
    // Constructor creates instance with CREATE_COLLECTION type, valid status and message
    @Test
    public void test_constructor_creates_valid_instance() {
        String message = "Collection created successfully";
        CreateCollectionResponse response = new CreateCollectionResponse(message);

        assertEquals(OperationType.CREATE_COLLECTION, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
    }
}
