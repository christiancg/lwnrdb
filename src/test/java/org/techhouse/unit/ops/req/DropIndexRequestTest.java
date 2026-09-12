package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DropIndexRequest;

public class DropIndexRequestTest {
    @Test
    public void test_create_drop_index_request_with_valid_params() {
        String dbName = "testDb";
        String collectionName = "testCollection";
        String fieldName = "testField";

        DropIndexRequest request = new DropIndexRequest(dbName, collectionName, fieldName);

        assertEquals(OperationType.DROP_INDEX, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());
    }

    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collectionName = "testCollection";
        String fieldName = "testField";

        DropIndexRequest request = new DropIndexRequest(dbName, collectionName, fieldName);

        assertEquals(OperationType.DROP_INDEX, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());

        String newFieldName = "newTestField";
        request.setFieldName(newFieldName);
        assertEquals(newFieldName, request.getFieldName());
    }
}
