package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.ReindexResponse;

public class ReindexResponseTest {
    @Test
    public void test_constructor_sets_reindex_operation_type() {
        ReindexResponse response = new ReindexResponse("ok", List.of("field1"));
        assertEquals(OperationType.REINDEX, response.getType());
    }

    @Test
    public void test_constructor_sets_status_and_message() {
        ReindexResponse response = new ReindexResponse("Rebuilt 1 index(es)", List.of("email"));
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Rebuilt 1 index(es)", response.getMessage());
    }

    @Test
    public void test_rebuilt_fields_is_set_correctly() {
        List<String> fields = List.of("email", "status");
        ReindexResponse response = new ReindexResponse("ok", fields);
        assertEquals(fields, response.getRebuiltFields());
        assertEquals(fields, response.rebuiltFields);
    }

    @Test
    public void test_null_rebuilt_fields_is_allowed() {
        ReindexResponse response = new ReindexResponse("msg", null);
        assertNull(response.getRebuiltFields());
    }

    @Test
    public void test_empty_rebuilt_fields() {
        ReindexResponse response = new ReindexResponse("No indexes to rebuild", List.of());
        assertTrue(response.getRebuiltFields().isEmpty());
    }

    @Test
    public void test_setter_updates_rebuilt_fields() {
        ReindexResponse response = new ReindexResponse("ok", List.of("a"));
        response.setRebuiltFields(List.of("b", "c"));
        assertEquals(List.of("b", "c"), response.getRebuiltFields());
    }
}
