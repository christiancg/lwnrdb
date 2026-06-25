package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.ReindexRequest;

public class ReindexRequestTest {
    @Test
    public void test_constructor_sets_reindex_operation_type() {
        ReindexRequest request = new ReindexRequest("testDb", "testColl", List.of("field1"));
        assertEquals(OperationType.REINDEX, request.getType());
    }

    @Test
    public void test_no_arg_constructor_returns_empty_field_names() {
        ReindexRequest request = new ReindexRequest();
        assertEquals(OperationType.REINDEX, request.getType());
        assertTrue(request.getFieldNames().isEmpty());
    }

    @Test
    public void test_full_constructor_sets_field_names() {
        List<String> fields = List.of("email", "status");
        ReindexRequest request = new ReindexRequest("myDb", "myColl", fields);
        assertEquals(fields, request.getFieldNames());
    }

    @Test
    public void test_null_field_names_returns_empty_list() {
        ReindexRequest request = new ReindexRequest("myDb", "myColl", null);
        assertTrue(request.getFieldNames().isEmpty());
    }

    @Test
    public void test_setter_updates_field_names() {
        ReindexRequest request = new ReindexRequest("myDb", "myColl", null);
        List<String> fields = List.of("age");
        request.setFieldNames(fields);
        assertEquals(fields, request.getFieldNames());
    }

    @Test
    public void test_getters_for_db_and_coll_names() {
        ReindexRequest request = new ReindexRequest("myDb", "myColl", List.of());
        assertEquals("myDb", request.getDatabaseName());
        assertEquals("myColl", request.getCollectionName());
    }
}
