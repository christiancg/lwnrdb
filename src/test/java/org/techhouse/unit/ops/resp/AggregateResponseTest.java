package org.techhouse.unit.ops.resp;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.AggregateResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AggregateResponseTest {
    // Create AggregateResponse with valid results list and OK status
    @Test
    public void test_create_aggregate_response_with_valid_results() {
        List<JsonObject> results = new ArrayList<>();
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field1", "value1");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field2", 123);
        results.add(obj1);
        results.add(obj2);

        AggregateResponse response = new AggregateResponse(OperationStatus.OK, "Success", results);

        assertEquals(OperationType.AGGREGATE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Success", response.getMessage());
        assertEquals(2, response.getResults().size());
        assertEquals(results, response.getResults());
    }

    // Create AggregateResponse with null results list
    @Test
    public void test_create_aggregate_response_with_null_results() {
        AggregateResponse response = new AggregateResponse(OperationStatus.ERROR, "No results found", null);

        assertEquals(OperationType.AGGREGATE, response.getType());
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("No results found", response.getMessage());
        assertNull(response.getResults());
    }

    // Create AggregateResponse with empty results list and OK status
    @Test
    public void test_create_with_empty_results_and_ok_status() {
        List<JsonObject> emptyResults = new ArrayList<>();
        AggregateResponse response = new AggregateResponse(OperationStatus.OK, "Success", emptyResults);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Success", response.getMessage());
        assertTrue(response.getResults().isEmpty());
    }

    // Verify OperationType is always AGGREGATE
    @Test
    public void test_operation_type_is_aggregate() {
        List<JsonObject> results = new ArrayList<>();
        AggregateResponse response = new AggregateResponse(OperationStatus.OK, "Success", results);
        assertEquals(OperationType.AGGREGATE, response.getType());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        List<JsonObject> results = new ArrayList<>();
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field1", "value1");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field2", 123);
        results.add(obj1);
        results.add(obj2);

        AggregateResponse response = new AggregateResponse(OperationStatus.OK, "Success", results);

        // Test getters
        assertEquals(OperationType.AGGREGATE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Success", response.getMessage());
        assertEquals(results, response.getResults());

        // Test setters
        response.setResults(new ArrayList<>());
        assertEquals(0, response.getResults().size());
    }
}