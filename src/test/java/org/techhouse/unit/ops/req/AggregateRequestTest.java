package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class AggregateRequestTest {
    // Create AggregateRequest with valid database and collection names
    @Test
    public void test_create_aggregate_request_with_valid_names() {
        String dbName = "testDb";
        String collName = "testCollection";
    
        AggregateRequest request = new AggregateRequest(dbName, collName);
    
        assertEquals(OperationType.AGGREGATE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertNull(request.getAggregationSteps());
    }

    // Create AggregateRequest with empty database name
    @Test
    public void test_create_aggregate_request_with_empty_db_name() {
        String dbName = "";
        String collName = "testCollection";
    
        AggregateRequest request = new AggregateRequest(dbName, collName);
    
        assertEquals(OperationType.AGGREGATE, request.getType());
        assertEquals("", request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertNull(request.getAggregationSteps());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collName = "testCollection";
        List<BaseAggregationStep> steps = new ArrayList<>();

        AggregateRequest request = new AggregateRequest(dbName, collName);
        request.setAggregationSteps(steps);

        assertEquals(OperationType.AGGREGATE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(steps, request.getAggregationSteps());
    }
}