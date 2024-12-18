package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.FindByIdRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FindByIdRequestTest {
    // Constructor correctly sets FIND_BY_ID operation type
    @Test
    public void constructor_sets_find_by_id_operation_type() {
        String dbName = "testDb";
        String collName = "testCollection";
    
        FindByIdRequest request = new FindByIdRequest(dbName, collName);

        assertEquals(OperationType.FIND_BY_ID, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collName = "testCollection";
        String id = "12345";

        FindByIdRequest request = new FindByIdRequest(dbName, collName);
        request.set_id(id);

        assertEquals(id, request.get_id());
        assertEquals(OperationType.FIND_BY_ID, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
    }
}