package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.OperationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OperationRequestTest {
    // Create OperationRequest with valid type, database and collection names
    @Test
    public void test_create_operation_request_with_valid_params() {
        OperationType type = OperationType.SAVE;
        String dbName = "testDb";
        String collName = "testCollection";

        OperationRequest request = new OperationRequest(type, dbName, collName);

        assertEquals(type, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_operation_request_getters_and_setters() {
        OperationType type = OperationType.FIND_BY_ID;
        String dbName = "sampleDb";
        String collName = "sampleCollection";

        OperationRequest request = new OperationRequest(type, dbName, collName);

        assertEquals(type, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
    }
}