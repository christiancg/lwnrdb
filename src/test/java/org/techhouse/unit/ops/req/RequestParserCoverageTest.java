package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.ArmFaultRequest;
import org.techhouse.ops.req.AwaitFaultRequest;
import org.techhouse.ops.req.GetDatabaseStatsRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.ReleaseFaultRequest;
import org.techhouse.ops.req.RequestParser;

public class RequestParserCoverageTest {

    @Test
    public void test_parse_get_database_stats() {
        final var result = RequestParser.parseRequest("{\"type\":\"GET_DATABASE_STATS\"}");
        assertInstanceOf(GetDatabaseStatsRequest.class, result);
    }

    @Test
    public void test_parse_list_users_with_aggregation_steps() {
        final var json = """
                {"type":"LIST_USERS","aggregationSteps":[{"type":"FILTER","operator":{
                 "fieldOperatorType":"EQUALS","field":"admin","value":{"$boolean":true}}}]}""";
        final var result = RequestParser.parseRequest(json);
        assertInstanceOf(ListUsersRequest.class, result);
    }

    @Test
    public void test_parse_cast_to_json_custom() {
        final var json = """
                {"type":"AGGREGATE","databaseName":"testDb","collectionName":"testColl",
                 "aggregationSteps":[{"type":"MAP","operators":[{"fieldName":"casted","condition":null,
                 "operator":{"type":"CAST","fieldName":"loc","toType":"JSON_CUSTOM","customTypeName":"Geo"}}]}]}""";
        final var result = RequestParser.parseRequest(json);
        assertInstanceOf(AggregateRequest.class, result);
    }

    @Test
    public void test_parse_conjunction_with_malformed_nested_operator_throws() {
        final var json = """
                {"type":"AGGREGATE","databaseName":"testDb","collectionName":"testColl",
                 "aggregationSteps":[{"type":"FILTER","operator":{"conjunctionType":"AND",
                 "operators":[{"garbage":true}]}}]}""";
        assertThrows(Exception.class, () -> RequestParser.parseRequest(json));
    }

    @Test
    public void test_parse_the_fault_operations() {
        final var arm = assertInstanceOf(ArmFaultRequest.class,
                RequestParser.parseRequest(
                        "{\"type\":\"ARM_FAULT\",\"point\":\"ANTI_ENTROPY_SNAPSHOTTED\",\"action\":\"PAUSE\","
                                + "\"match\":\"db|coll\"}"));
        assertEquals("ANTI_ENTROPY_SNAPSHOTTED", arm.getPoint());
        assertEquals("PAUSE", arm.getAction());
        assertEquals("db|coll", arm.getMatch());
        final var await = assertInstanceOf(AwaitFaultRequest.class,
                RequestParser.parseRequest("{\"type\":\"AWAIT_FAULT\",\"point\":\"PEER_REPLY\",\"timeoutMs\":1000}"));
        assertEquals("PEER_REPLY", await.getPoint());
        assertEquals(1000L, await.getTimeoutMs());
        final var release = assertInstanceOf(ReleaseFaultRequest.class,
                RequestParser.parseRequest("{\"type\":\"RELEASE_FAULT\",\"point\":\"PEER_REPLY\"}"));
        assertEquals("PEER_REPLY", release.getPoint());
    }
}
