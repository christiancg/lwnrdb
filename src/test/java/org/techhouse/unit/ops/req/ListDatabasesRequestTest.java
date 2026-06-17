package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.ListDatabasesRequest;

public class ListDatabasesRequestTest {
    // Constructor creates object with LIST_DATABASES operation type
    @Test
    public void test_constructor_sets_list_databases_operation_type() {
        ListDatabasesRequest request = new ListDatabasesRequest();

        assertEquals(OperationType.LIST_DATABASES, request.getType());
        assertNull(request.getDatabaseName());
        assertNull(request.getCollectionName());
    }
}
