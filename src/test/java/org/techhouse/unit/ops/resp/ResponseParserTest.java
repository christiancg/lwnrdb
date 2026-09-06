package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.CommitTransactionResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ResponseParser;
import org.techhouse.ops.resp.RollbackTransactionResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.ops.resp.StartTransactionResponse;

public class ResponseParserTest {
    private final EJson eJson = IocContainer.get(EJson.class);

    private OperationResponse roundTrip(OperationResponse response) {
        return ResponseParser.parseResponse(eJson.toJson(response));
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    // A FIND_BY_ID response round-trips with its document intact
    @Test
    public void test_parses_find_by_id_response() {
        final var parsed = roundTrip(new FindByIdResponse("ok", document("a")));
        final var response = assertInstanceOf(FindByIdResponse.class, parsed);
        assertEquals(OperationType.FIND_BY_ID, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("a", response.getObject().get("_id").asJsonString().getValue());
    }

    // A SAVE response round-trips with its generated id
    @Test
    public void test_parses_save_response() {
        final var response = assertInstanceOf(SaveResponse.class, roundTrip(new SaveResponse("saved", "id-1")));
        assertEquals("id-1", response.get_id());
        assertEquals("saved", response.getMessage());
    }

    // A BULK_SAVE response round-trips both id lists
    @Test
    public void test_parses_bulk_save_response() {
        final var parsed = roundTrip(new BulkSaveResponse("ok", List.of("a", "b"), List.of("c")));
        final var response = assertInstanceOf(BulkSaveResponse.class, parsed);
        assertEquals(List.of("a", "b"), response.getInserted());
        assertEquals(List.of("c"), response.getUpdated());
    }

    // An AGGREGATE response round-trips its result documents
    @Test
    public void test_parses_aggregate_response() {
        final var parsed = roundTrip(new AggregateResponse("ok", List.of(document("a"), document("b"))));
        final var response = assertInstanceOf(AggregateResponse.class, parsed);
        assertEquals(2, response.getResults().size());
        assertEquals("b", response.getResults().get(1).get("_id").asJsonString().getValue());
    }

    // A DELETE response round-trips as its own type
    @Test
    public void test_parses_delete_response() {
        final var response = assertInstanceOf(DeleteResponse.class, roundTrip(new DeleteResponse("deleted")));
        assertEquals(OperationType.DELETE, response.getType());
    }

    // A LIST_COLLECTIONS response round-trips its collection names
    @Test
    public void test_parses_list_collections_response() {
        final var parsed = roundTrip(new ListCollectionsResponse("ok", List.of("one", "two")));
        assertEquals(List.of("one", "two"), assertInstanceOf(ListCollectionsResponse.class, parsed).getCollections());
    }

    // A LIST_DATABASES response round-trips its database names
    @Test
    public void test_parses_list_databases_response() {
        final var parsed = roundTrip(new ListDatabasesResponse("ok", List.of("db1")));
        assertEquals(List.of("db1"), assertInstanceOf(ListDatabasesResponse.class, parsed).getDatabases());
    }

    // The three transaction control responses round-trip as their own types
    @Test
    public void test_parses_transaction_control_responses() {
        final var started = roundTrip(new StartTransactionResponse("ok", "tx-1"));
        assertEquals("tx-1", assertInstanceOf(StartTransactionResponse.class, started).getTransactionId());
        assertInstanceOf(CommitTransactionResponse.class, roundTrip(new CommitTransactionResponse("ok")));
        assertInstanceOf(RollbackTransactionResponse.class, roundTrip(new RollbackTransactionResponse("ok")));
    }

    // An error response keeps its status, message and errorCode
    @Test
    public void test_parses_error_response_preserving_error_code() {
        final var source = new OperationResponse(OperationType.SAVE, "nope", ErrorCode.CROSS_OWNER_TRANSACTION);
        final var parsed = roundTrip(source);
        assertEquals(OperationStatus.ERROR, parsed.getStatus());
        assertEquals("nope", parsed.getMessage());
        assertEquals("421-2", parsed.getErrorCode());
    }

    // An errorCode absent from the enum still yields the right status and message
    @Test
    public void test_unknown_error_code_falls_back_to_plain_response() {
        final var json = "{\"type\":\"SAVE\",\"status\":\"ERROR\",\"message\":\"boom\",\"errorCode\":\"999-9\"}";
        final var parsed = ResponseParser.parseResponse(json);
        assertEquals(OperationType.SAVE, parsed.getType());
        assertEquals(OperationStatus.ERROR, parsed.getStatus());
        assertEquals("boom", parsed.getMessage());
        assertNull(parsed.getErrorCode());
    }

    // An error response with no errorCode at all is still parsed
    @Test
    public void test_error_response_without_error_code() {
        final var json = "{\"type\":\"DELETE\",\"status\":\"NOT_FOUND\",\"message\":\"gone\",\"errorCode\":null}";
        final var parsed = ResponseParser.parseResponse(json);
        assertEquals(OperationStatus.NOT_FOUND, parsed.getStatus());
        assertNull(parsed.getErrorCode());
    }

    // An OK response of a type with no dedicated mapping becomes a base OperationResponse
    @Test
    public void test_unmapped_operation_type_yields_base_response() {
        final var json = "{\"type\":\"REINDEX\",\"status\":\"OK\",\"message\":\"done\",\"errorCode\":null}";
        final var parsed = ResponseParser.parseResponse(json);
        assertEquals(OperationResponse.class, parsed.getClass());
        assertEquals(OperationType.REINDEX, parsed.getType());
        assertEquals("done", parsed.getMessage());
    }

    // A missing list field yields an empty list rather than a null
    @Test
    public void test_missing_list_field_yields_empty_list() {
        final var json = "{\"type\":\"LIST_DATABASES\",\"status\":\"OK\",\"message\":\"ok\"}";
        final var parsed = assertInstanceOf(ListDatabasesResponse.class, ResponseParser.parseResponse(json));
        assertTrue(parsed.getDatabases().isEmpty());
    }

    // A null document field yields a null object rather than throwing
    @Test
    public void test_null_object_field_yields_null() {
        final var json = "{\"type\":\"FIND_BY_ID\",\"status\":\"OK\",\"message\":\"ok\",\"object\":null}";
        final var parsed = assertInstanceOf(FindByIdResponse.class, ResponseParser.parseResponse(json));
        assertNull(parsed.getObject());
    }
}
