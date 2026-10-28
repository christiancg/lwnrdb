package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;

public class ListenRequestParserTest {

    @Test
    public void parseRequest_listen_withFilterStep_parsedCorrectly() {
        final var json = """
                {"type":"LISTEN","databaseName":"testDb","collectionName":"testColl",
                 "aggregationSteps":[{"type":"FILTER","operator":{"fieldOperatorType":"EQUALS",
                 "field":"status","value":{"$string":"active"}}}]}""";

        final var result = RequestParser.parseRequest(json);

        assertInstanceOf(ListenRequest.class, result);
        assertEquals(OperationType.LISTEN, result.getType());
        assertEquals("testDb", result.getDatabaseName());
        assertEquals("testColl", result.getCollectionName());
        assertFalse(((ListenRequest) result).getAggregationSteps().isEmpty());
    }

    @Test
    public void parseRequest_listen_emptySteps_parsedCorrectly() {
        final var json = "{\"type\":\"LISTEN\",\"databaseName\":\"db\",\"collectionName\":\"coll\",\"aggregationSteps\":[]}";

        final var result = RequestParser.parseRequest(json);

        assertInstanceOf(ListenRequest.class, result);
        assertNotNull(((ListenRequest) result).getAggregationSteps());
        assertTrue(((ListenRequest) result).getAggregationSteps().isEmpty());
    }

    @Test
    public void parseRequest_stopListen_parsedCorrectly() {
        final var id = UUID.randomUUID().toString();
        final var json = "{\"type\":\"STOP_LISTEN\",\"listenId\":\"" + id + "\"}";

        final var result = RequestParser.parseRequest(json);

        assertInstanceOf(StopListenRequest.class, result);
        assertEquals(OperationType.STOP_LISTEN, result.getType());
        assertEquals(id, ((StopListenRequest) result).getListenId());
    }

    @Test
    public void test_a_listen_without_steps_still_parses() {
        final var json = "{\"type\":\"LISTEN\",\"databaseName\":\"db\",\"collectionName\":\"coll\"}";

        final var result = (ListenRequest) RequestParser.parseRequest(json);

        assertNull(result.getAggregationSteps());
        assertEquals("coll", result.getCollectionName());
    }

    @Test
    public void test_listen_steps_bind_once() {
        final var json = """
                {"type":"LISTEN","databaseName":"db","collectionName":"coll",
                 "aggregationSteps":[{"type":"LIMIT","limit":3},{"type":"COUNT"}]}""";

        final var result = (ListenRequest) RequestParser.parseRequest(json);

        assertEquals(2, result.getAggregationSteps().size());
        assertInstanceOf(LimitAggregationStep.class, result.getAggregationSteps().get(0));
        assertInstanceOf(CountAggregationStep.class, result.getAggregationSteps().get(1));
    }
}
