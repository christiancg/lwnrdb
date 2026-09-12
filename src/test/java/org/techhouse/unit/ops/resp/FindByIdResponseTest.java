package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.FindByIdResponse;

public class FindByIdResponseTest {
    @Test
    public void test_create_find_by_id_response_with_valid_object() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("id", 1);
        jsonObject.addProperty("name", "test");

        FindByIdResponse response = new FindByIdResponse("Found", jsonObject);

        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Found", response.getMessage());
        assertEquals(jsonObject, response.getObject());
        assertTrue(response.getObject().has("id"));
        assertTrue(response.getObject().has("name"));
    }

    @Test
    public void test_create_find_by_id_response_with_null_object() {
        FindByIdResponse response = new FindByIdResponse("Not found", null);

        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Not found", response.getMessage());
        assertNull(response.getObject());
    }

    @Test
    public void test_getters_and_setters() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("id", 1);
        jsonObject.addProperty("name", "test");

        FindByIdResponse response = new FindByIdResponse("Found", jsonObject);

        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Found", response.getMessage());
        assertEquals(jsonObject, response.getObject());

        JsonObject newJsonObject = new JsonObject();
        newJsonObject.addProperty("id", 2);
        newJsonObject.addProperty("name", "updated");

        response.setObject(newJsonObject);
        assertEquals(newJsonObject, response.getObject());
    }
}
