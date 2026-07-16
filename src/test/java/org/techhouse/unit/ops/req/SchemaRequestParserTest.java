package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveSchemaRequest;

public class SchemaRequestParserTest {
    // SAVE_SCHEMA parses into a SaveSchemaRequest with its nested schema object
    @Test
    public void test_parse_save_schema() {
        final var message = "{\"type\":\"SAVE_SCHEMA\",\"databaseName\":\"myDb\",\"collectionName\":\"myColl\","
                + "\"schema\":{\"type\":\"object\",\"required\":[\"name\"]}}";
        final var request = RequestParser.parseRequest(message);
        assertInstanceOf(SaveSchemaRequest.class, request);
        final var saveSchema = (SaveSchemaRequest) request;
        assertEquals(OperationType.SAVE_SCHEMA, saveSchema.getType());
        assertEquals("myDb", saveSchema.getDatabaseName());
        assertEquals("myColl", saveSchema.getCollectionName());
        assertTrue(saveSchema.getSchema().has("type"));
        assertTrue(saveSchema.getSchema().has("required"));
    }

    // DELETE_SCHEMA parses into a DeleteSchemaRequest
    @Test
    public void test_parse_delete_schema() {
        final var message = "{\"type\":\"DELETE_SCHEMA\",\"databaseName\":\"myDb\",\"collectionName\":\"myColl\"}";
        final var request = RequestParser.parseRequest(message);
        assertInstanceOf(DeleteSchemaRequest.class, request);
        assertEquals(OperationType.DELETE_SCHEMA, request.getType());
        assertEquals("myColl", request.getCollectionName());
    }
}
