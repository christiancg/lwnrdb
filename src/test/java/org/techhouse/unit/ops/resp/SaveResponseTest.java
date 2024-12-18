package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.SaveResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SaveResponseTest {
    // Create SaveResponse with OK status and valid message and ID
    @Test
    public void test_create_save_response_with_valid_data() {
        String id = "123abc";
        String message = "Document saved successfully";
    
        SaveResponse response = new SaveResponse(OperationStatus.OK, message, id);
    
        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(id, response.get_id());
    }

    // Create SaveResponse with null ID
    @Test
    public void test_create_save_response_with_null_id() {
        String message = "Document saved with null ID";
        String id = null;
    
        SaveResponse response = new SaveResponse(OperationStatus.OK, message, id);
    
        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertNull(response.get_id());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String id = "123abc";
        String message = "Document saved successfully";
        SaveResponse response = new SaveResponse(OperationStatus.OK, message, id);

        // Test getters
        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(id, response.get_id());

        // Test setters
        String newId = "456def";
        response.set_id(newId);

        assertEquals(newId, response.get_id());
    }
}