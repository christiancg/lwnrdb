package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.ListenEndedResponse;
import org.techhouse.ops.resp.ListenResponse;

public class ListenResponseTest {
    private final EJson eJson = IocContainer.get(EJson.class);

    @Test
    public void constructor_initialListen_setsFields() {
        final var results = List.<JsonObject>of();
        final var resp = new ListenResponse("some-id", results, "abc123", false);

        assertEquals(OperationType.LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals("Listening for changes", resp.getMessage());
        assertEquals("some-id", resp.getListenId());
        assertSame(results, resp.getResults());
        assertEquals("abc123", resp.getResultHash());
        assertFalse(resp.isUpdate());
    }

    @Test
    public void constructor_updateListen_setsFields() {
        final var results = List.<JsonObject>of();
        final var resp = new ListenResponse("some-id", results, "abc123", true);

        assertEquals(OperationType.LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals("Query results updated", resp.getMessage());
        assertEquals("some-id", resp.getListenId());
        assertSame(results, resp.getResults());
        assertEquals("abc123", resp.getResultHash());
        assertTrue(resp.isUpdate());
    }

    @Test
    public void setters_updateFields() {
        final var resp = new ListenResponse("id", List.of(), "hash", false);
        final var newResults = List.of(new JsonObject());

        resp.setListenId("new-id");
        resp.setResults(newResults);
        resp.setResultHash("new-hash");
        resp.setUpdate(true);

        assertEquals("new-id", resp.getListenId());
        assertSame(newResults, resp.getResults());
        assertEquals("new-hash", resp.getResultHash());
        assertTrue(resp.isUpdate());
    }

    @Test
    public void constructor_carries_the_listen_id_the_code_and_the_reason() {
        final var response = new ListenEndedResponse("abc", "a collection it reads was dropped");

        assertEquals(OperationType.LISTEN, response.getType());
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.LISTEN_ENDED.getCode(), response.getErrorCode());
        assertEquals("The listen ended: a collection it reads was dropped", response.getMessage());
        assertEquals("abc", response.getListenId());
    }

    @Test
    public void serialized_frame_carries_only_the_response_fields_and_the_listen_id() {
        final var response = new ListenEndedResponse("abc", "reason");
        response.setListenId("def");

        final var frame = eJson.fromJson(eJson.toJson(response), JsonObject.class);

        assertEquals(Set.of("type", "status", "message", "errorCode", "listenId"), keysOf(frame));
        assertEquals("def", frame.get("listenId").asJsonString().getValue());
        assertEquals("410-1", frame.get("errorCode").asJsonString().getValue());
    }

    private static Set<String> keysOf(JsonObject object) {
        return object.entrySet().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }
}
