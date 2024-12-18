package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DropCollectionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DropCollectionRequestTest {
    // Constructor correctly sets OperationType.DROP_COLLECTION as type
    @Test
    public void test_constructor_sets_drop_collection_type() {
        String dbName = "testDb";
        String collName = "testCollection";
    
        DropCollectionRequest request = new DropCollectionRequest(dbName, collName);
    
        assertEquals(OperationType.DROP_COLLECTION, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
    }

    // Constructor handles empty string for database name
    @Test
    public void test_constructor_accepts_empty_database_name() {
        String dbName = "";
        String collName = "testCollection";
    
        DropCollectionRequest request = new DropCollectionRequest(dbName, collName);
    
        assertEquals("", request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(OperationType.DROP_COLLECTION, request.getType());
    }
}