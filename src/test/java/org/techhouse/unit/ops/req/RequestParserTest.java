package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CloseConnectionRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;

public class RequestParserTest {
    @Test
    public void test_parse_basic_operation_requests() {
        String saveRequest = """
                {
                    "type": "SAVE",
                    "databaseName": "testDb",
                    "collectionName": "testCollection",
                    "object": {
                        "name": "test"
                    }
                }""";

        String deleteRequest = """
                {
                    "type": "DELETE",
                    "databaseName": "testDb",
                    "collectionName": "testCollection"
                }""";

        String createDbRequest = """
                {
                    "type": "CREATE_DATABASE",
                    "databaseName": "newDb"
                }""";

        OperationRequest saveResult = RequestParser.parseRequest(saveRequest);
        OperationRequest deleteResult = RequestParser.parseRequest(deleteRequest);
        OperationRequest createDbResult = RequestParser.parseRequest(createDbRequest);

        assertInstanceOf(SaveRequest.class, saveResult);
        assertEquals(OperationType.SAVE, saveResult.getType());
        assertEquals("testDb", saveResult.getDatabaseName());

        assertInstanceOf(DeleteRequest.class, deleteResult);
        assertEquals(OperationType.DELETE, deleteResult.getType());
        assertEquals("testDb", deleteResult.getDatabaseName());

        assertInstanceOf(CreateDatabaseRequest.class, createDbResult);
        assertEquals(OperationType.CREATE_DATABASE, createDbResult.getType());
        assertEquals("newDb", createDbResult.getDatabaseName());
    }

    @Test
    public void test_parse_list_databases_request() {
        String listDatabasesRequest = """
                {
                    "type": "LIST_DATABASES"
                }""";

        OperationRequest result = RequestParser.parseRequest(listDatabasesRequest);

        assertInstanceOf(ListDatabasesRequest.class, result);
        assertEquals(OperationType.LIST_DATABASES, result.getType());
        assertNull(result.getDatabaseName());
        assertNull(result.getCollectionName());
    }

    @Test
    public void test_handle_null_json_fields() {
        String requestWithNulls = """
                {
                    "type": "SAVE",
                    "databaseName": "testDb",
                    "collectionName": "testCollection",
                    "object": {
                        "name": null,
                        "age": null,
                        "address": null
                    }
                }""";

        OperationRequest result = RequestParser.parseRequest(requestWithNulls);

        assertInstanceOf(SaveRequest.class, result);
        SaveRequest saveRequest = (SaveRequest) result;
        JsonObject obj = saveRequest.getObject();

        assertTrue(obj.get("name").isJsonNull());
        assertTrue(obj.get("age").isJsonNull());
        assertTrue(obj.get("address").isJsonNull());
    }

    @Test
    public void test_process_requests_with_primary_key_field() {
        String jsonMessage = """
                    {
                        "type": "SAVE",
                        "databaseName": "testDB",
                        "collectionName": "testCollection",
                        "object": {
                            "_id": "12345",
                            "name": "test"
                        }
                    }
                """;
        OperationRequest request = RequestParser.parseRequest(jsonMessage);
        assertInstanceOf(SaveRequest.class, request);
        SaveRequest saveRequest = (SaveRequest) request;
        assertEquals("12345", saveRequest.get_id());
        JsonObject object = saveRequest.getObject();
        assertTrue(object.has("_id"));
        assertEquals("12345", object.get("_id").asJsonString().getValue());
    }

    @Test
    public void test_casting_operations() {
        String message = "{ \"type\": \"AGGREGATE\", \"databaseName\": \"testDB\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{\"type\": \"MAP\", \"operators\": [{ \"type\": \"CAST\", \"fieldName\": \"age\", \"toType\": \"STRING\" }]}] }";
        AggregateRequest request = (AggregateRequest) RequestParser.parseRequest(message);
        assertNotNull(request);
        assertEquals(1, request.getAggregationSteps().size());
        BaseAggregationStep step = request.getAggregationSteps().getFirst();
        assertInstanceOf(MapAggregationStep.class, step);
        MapAggregationStep mapStep = (MapAggregationStep) step;
        assertEquals(1, mapStep.getOperators().size());
        MapOperator operator = mapStep.getOperators().getFirst();
        RemoveFieldMapOperator addFieldOperator = (RemoveFieldMapOperator) operator;
        assertEquals("age", addFieldOperator.getFieldName());
    }

    @Test
    public void test_parse_list_collections_request() {
        String listCollectionsRequest = """
                {
                    "type": "LIST_COLLECTIONS",
                    "databaseName": "testDb"
                }""";

        OperationRequest result = RequestParser.parseRequest(listCollectionsRequest);

        assertInstanceOf(ListCollectionsRequest.class, result);
        assertEquals(OperationType.LIST_COLLECTIONS, result.getType());
        assertEquals("testDb", result.getDatabaseName());
        assertNull(result.getCollectionName());
    }

    @Test
    public void test_parse_bulk_save_request() {
        String msg = """
                {"type":"BULK_SAVE","databaseName":"db","collectionName":"coll","objects":[{"name":"a"}]}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(BulkSaveRequest.class, result);
        assertEquals(OperationType.BULK_SAVE, result.getType());
    }

    @Test
    public void test_parse_find_by_id_request() {
        String msg = """
                {"type":"FIND_BY_ID","databaseName":"db","collectionName":"coll","_id":"123"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(FindByIdRequest.class, result);
        assertEquals(OperationType.FIND_BY_ID, result.getType());
    }

    @Test
    public void test_parse_drop_database_request() {
        String msg = """
                {"type":"DROP_DATABASE","databaseName":"myDb"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropDatabaseRequest.class, result);
        assertEquals(OperationType.DROP_DATABASE, result.getType());
        assertEquals("myDb", result.getDatabaseName());
    }

    @Test
    public void test_parse_drop_collection_request() {
        String msg = """
                {"type":"DROP_COLLECTION","databaseName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropCollectionRequest.class, result);
        assertEquals(OperationType.DROP_COLLECTION, result.getType());
    }

    @Test
    public void test_parse_create_index_request() {
        String msg = """
                {"type":"CREATE_INDEX","databaseName":"db","collectionName":"coll","fieldName":"myField"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateIndexRequest.class, result);
        assertEquals(OperationType.CREATE_INDEX, result.getType());
    }

    @Test
    public void test_parse_drop_index_request() {
        String msg = """
                {"type":"DROP_INDEX","databaseName":"db","collectionName":"coll","fieldName":"myField"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropIndexRequest.class, result);
        assertEquals(OperationType.DROP_INDEX, result.getType());
    }

    @Test
    public void test_parse_reindex_request_with_field_names() {
        String msg = """
                {"type":"REINDEX","databaseName":"db","collectionName":"coll","fieldNames":["email","status"]}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(ReindexRequest.class, result);
        assertEquals(OperationType.REINDEX, result.getType());
        assertEquals(List.of("email", "status"), ((ReindexRequest) result).getFieldNames());
    }

    @Test
    public void test_parse_reindex_request_without_field_names() {
        String msg = """
                {"type":"REINDEX","databaseName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(ReindexRequest.class, result);
        assertTrue(((ReindexRequest) result).getFieldNames().isEmpty());
    }

    @Test
    public void test_parse_close_connection_request() {
        String msg = """
                {"type":"CLOSE_CONNECTION"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CloseConnectionRequest.class, result);
        assertEquals(OperationType.CLOSE_CONNECTION, result.getType());
    }

    @Test
    public void test_parse_create_collection_request() {
        String msg = """
                {"type":"CREATE_COLLECTION","databaseName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateCollectionRequest.class, result);
        assertEquals(OperationType.CREATE_COLLECTION, result.getType());
    }
}
