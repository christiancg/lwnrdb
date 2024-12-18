package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.BulkSaveResponse;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BulkSaveResponseTest {
    // Create BulkSaveResponse with OK status and non-empty inserted/updated lists
    @Test
    public void test_bulk_save_response_with_non_empty_lists() {
        List<String> inserted = Arrays.asList("doc1", "doc2");
        List<String> updated = Arrays.asList("doc3", "doc4");
        String message = "Successfully saved documents";

        BulkSaveResponse response = new BulkSaveResponse(OperationStatus.OK, message, inserted, updated);

        assertEquals(OperationType.BULK_SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(inserted, response.getInserted());
        assertEquals(updated, response.getUpdated());
    }

    // Create BulkSaveResponse with null inserted list
    @Test
    public void test_bulk_save_response_with_null_inserted_list() {
        List<String> updated = List.of("doc1");
        String message = "Partially saved documents";

        BulkSaveResponse response = new BulkSaveResponse(OperationStatus.OK, message, null, updated);

        assertEquals(OperationType.BULK_SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertNull(response.getInserted());
        assertEquals(updated, response.getUpdated());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_bulk_save_response_getters_and_setters() {
        List<String> inserted = Arrays.asList("item1", "item2");
        List<String> updated = Arrays.asList("item3", "item4");
        String message = "Operation completed";

        BulkSaveResponse response = new BulkSaveResponse(OperationStatus.OK, message, inserted, updated);

        assertEquals(OperationType.BULK_SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(inserted, response.getInserted());
        assertEquals(updated, response.getUpdated());

        List<String> newInserted = List.of("item5");
        List<String> newUpdated = List.of("item6");
        response.setInserted(newInserted);
        response.setUpdated(newUpdated);

        assertEquals(newInserted, response.getInserted());
        assertEquals(newUpdated, response.getUpdated());
    }
}