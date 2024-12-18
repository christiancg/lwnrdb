package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateCollectionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CreateCollectionRequestTest {
    // Constructor correctly sets OperationType.CREATE_COLLECTION as type
    @Test
    public void test_constructor_sets_create_collection_type() {
        CreateCollectionRequest request = new CreateCollectionRequest("testDb", "testCollection");
    
        assertEquals(OperationType.CREATE_COLLECTION, request.getType());
        assertEquals("testDb", request.getDatabaseName());
        assertEquals("testCollection", request.getCollectionName());
    }

    // Constructor handles empty string for databaseName
    @Test
    public void test_constructor_accepts_empty_database_name() {
        CreateCollectionRequest request = new CreateCollectionRequest("", "testCollection");
    
        assertEquals(OperationType.CREATE_COLLECTION, request.getType());
        assertEquals("", request.getDatabaseName());
        assertEquals("testCollection", request.getCollectionName());
    }
}