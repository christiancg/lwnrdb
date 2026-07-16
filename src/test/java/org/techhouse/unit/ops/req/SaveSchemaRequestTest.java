package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.SaveSchemaRequest;

public class SaveSchemaRequestTest {
    // Constructor sets the SAVE_SCHEMA type and stores the schema
    @Test
    public void test_constructor_and_getters() {
        final var schema = new JsonObject();
        schema.add("type", new org.techhouse.ejson.elements.JsonString("object"));
        final var request = new SaveSchemaRequest("testDb", "testColl", schema);
        assertEquals(OperationType.SAVE_SCHEMA, request.getType());
        assertEquals("testDb", request.getDatabaseName());
        assertEquals("testColl", request.getCollectionName());
        assertEquals(schema, request.getSchema());
    }

    // Setter replaces the schema
    @Test
    public void test_setter() {
        final var request = new SaveSchemaRequest("testDb", "testColl", new JsonObject());
        final var newSchema = new JsonObject();
        newSchema.add("type", new org.techhouse.ejson.elements.JsonString("string"));
        request.setSchema(newSchema);
        assertEquals(newSchema, request.getSchema());
    }
}
