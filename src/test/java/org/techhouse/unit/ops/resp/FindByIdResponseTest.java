package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.FindByIdResponse;

import static org.junit.jupiter.api.Assertions.*;

public class FindByIdResponseTest {
    // Create FindByIdResponse with valid JsonObject and OK status
    @Test
    public void test_create_find_by_id_response_with_valid_object() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("id", 1);
        jsonObject.addProperty("name", "test");

        FindByIdResponse response = new FindByIdResponse(OperationStatus.OK, "Found", jsonObject);

        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Found", response.getMessage());
        assertEquals(jsonObject, response.getObject());
        assertTrue(response.getObject().has("id"));
        assertTrue(response.getObject().has("name"));
    }

    // Create FindByIdResponse with null JsonObject
    @Test
    public void test_create_find_by_id_response_with_null_object() {
        FindByIdResponse response = new FindByIdResponse(OperationStatus.NOT_FOUND, "Not found", null);

        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("Not found", response.getMessage());
        assertNull(response.getObject());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("id", 1);
        jsonObject.addProperty("name", "test");

        FindByIdResponse response = new FindByIdResponse(OperationStatus.OK, "Found", jsonObject);

        // Test getters
        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Found", response.getMessage());
        assertEquals(jsonObject, response.getObject());

        // Test setters
        JsonObject newJsonObject = new JsonObject();
        newJsonObject.addProperty("id", 2);
        newJsonObject.addProperty("name", "updated");

        response.setObject(newJsonObject);
        assertEquals(newJsonObject, response.getObject());
    }
}