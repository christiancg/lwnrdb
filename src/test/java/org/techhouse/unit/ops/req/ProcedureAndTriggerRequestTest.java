package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.resp.CallProcedureResponse;
import org.techhouse.ops.resp.DeleteProcedureResponse;
import org.techhouse.ops.resp.DeleteTriggerResponse;
import org.techhouse.ops.resp.ListProceduresResponse;
import org.techhouse.ops.resp.ListTriggersResponse;
import org.techhouse.ops.resp.SaveProcedureResponse;
import org.techhouse.ops.resp.SaveTriggerResponse;

public class ProcedureAndTriggerRequestTest {
    @Test
    public void test_save_procedure_request_accessors() {
        final var request = new SaveProcedureRequest();
        assertEquals(OperationType.SAVE_PROCEDURE, request.getType());
        request.setName("p");
        request.setScript("return 1;");
        request.setDescription("does a thing");
        request.setIfVersion(4L);
        request.setStampedVersion(5L);
        request.setStampedUpdatedAt(6L);
        request.setStampedUpdatedBy("alice");
        assertEquals("p", request.getName());
        assertEquals("return 1;", request.getScript());
        assertEquals("does a thing", request.getDescription());
        assertEquals(4L, request.getIfVersion());
        assertEquals(5L, request.getStampedVersion());
        assertEquals(6L, request.getStampedUpdatedAt());
        assertEquals("alice", request.getStampedUpdatedBy());
        // Absent enabled reads as enabled
        assertTrue(request.isEnabled());
        request.setEnabled(false);
        assertFalse(request.isEnabled());
    }

    @Test
    public void test_delete_and_list_procedure_request_accessors() {
        final var delete = new DeleteProcedureRequest();
        assertEquals(OperationType.DELETE_PROCEDURE, delete.getType());
        delete.setName("p");
        assertEquals("p", delete.getName());

        final var list = new ListProceduresRequest();
        assertEquals(OperationType.LIST_PROCEDURES, list.getType());
        assertFalse(list.isIncludeSource());
        list.setIncludeSource(true);
        assertTrue(list.isIncludeSource());
    }

    @Test
    public void test_call_procedure_request_accessors() {
        final var request = new CallProcedureRequest();
        assertEquals(OperationType.CALL_PROCEDURE, request.getType());
        // Absent args read as an empty object rather than null
        assertTrue(request.getArgs().entrySet().isEmpty());
        request.setProcedureName("p");
        final var args = new JsonObject();
        args.add("k", new JsonString("v"));
        request.setArgs(args);
        assertEquals("p", request.getProcedureName());
        assertEquals("v", request.getArgs().get("k").asJsonString().getValue());
    }

    @Test
    public void test_save_trigger_request_accessors() {
        final var request = new SaveTriggerRequest();
        assertEquals(OperationType.SAVE_TRIGGER, request.getType());
        assertTrue(request.getEvents().isEmpty());
        request.setName("t");
        request.setEvents(List.of("CREATED", "DELETED"));
        request.setProcedureName("p");
        request.setMode("batch");
        request.setIfVersion(2L);
        request.setStampedVersion(3L);
        request.setStampedUpdatedAt(4L);
        request.setStampedUpdatedBy("bob");
        request.setStampedDefiner("owner");
        assertEquals("t", request.getName());
        assertEquals(List.of("CREATED", "DELETED"), request.getEvents());
        assertEquals("p", request.getProcedureName());
        assertEquals("batch", request.getMode());
        assertEquals(2L, request.getIfVersion());
        assertEquals(3L, request.getStampedVersion());
        assertEquals(4L, request.getStampedUpdatedAt());
        assertEquals("bob", request.getStampedUpdatedBy());
        assertEquals("owner", request.getStampedDefiner());
        // Defaults: cascade off, enabled on
        assertFalse(request.isAllowCascade());
        assertTrue(request.isEnabled());
        request.setAllowCascade(true);
        request.setEnabled(false);
        assertTrue(request.isAllowCascade());
        assertFalse(request.isEnabled());
    }

    @Test
    public void test_delete_and_list_trigger_request_accessors() {
        final var delete = new DeleteTriggerRequest();
        assertEquals(OperationType.DELETE_TRIGGER, delete.getType());
        delete.setName("t");
        assertEquals("t", delete.getName());
        assertEquals(OperationType.LIST_TRIGGERS, new ListTriggersRequest().getType());
        assertEquals("coll", new ListTriggersRequest("db", "coll").getCollectionName());
    }

    @Test
    public void test_requests_parse_from_the_wire() {
        final var save = (SaveProcedureRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_PROCEDURE\",\"databaseName\":\"db\",\"name\":\"p\",\"script\":\"return 1;\","
                        + "\"ifVersion\":3,\"enabled\":false}");
        assertEquals("p", save.getName());
        assertEquals(3L, save.getIfVersion());
        assertFalse(save.isEnabled());

        final var call = (CallProcedureRequest) RequestParser.parseRequest(
                "{\"type\":\"CALL_PROCEDURE\",\"databaseName\":\"db\",\"procedureName\":\"p\",\"args\":{\"n\":1}}");
        assertEquals("p", call.getProcedureName());
        assertEquals(1d, call.getArgs().get("n").asJsonNumber().getValue().doubleValue());

        final var trigger = (SaveTriggerRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_TRIGGER\",\"databaseName\":\"db\",\"collectionName\":\"coll\",\"name\":\"t\","
                        + "\"events\":[\"CREATED\"],\"procedureName\":\"p\",\"allowCascade\":true}");
        assertEquals(List.of("CREATED"), trigger.getEvents());
        assertTrue(trigger.isAllowCascade());

        assertInstanceOf(DeleteProcedureRequest.class,
                RequestParser.parseRequest("{\"type\":\"DELETE_PROCEDURE\",\"databaseName\":\"db\",\"name\":\"p\"}"));
        assertInstanceOf(ListProceduresRequest.class,
                RequestParser.parseRequest("{\"type\":\"LIST_PROCEDURES\",\"databaseName\":\"db\"}"));
        assertInstanceOf(DeleteTriggerRequest.class, RequestParser.parseRequest(
                "{\"type\":\"DELETE_TRIGGER\",\"databaseName\":\"db\",\"collectionName\":\"c\",\"name\":\"t\"}"));
        assertInstanceOf(ListTriggersRequest.class,
                RequestParser.parseRequest("{\"type\":\"LIST_TRIGGERS\",\"databaseName\":\"db\"}"));
    }

    @Test
    public void test_response_accessors() {
        assertEquals(3L, new SaveProcedureResponse("ok", 3L).getVersion());
        assertEquals(OperationType.DELETE_PROCEDURE, new DeleteProcedureResponse("ok").getType());
        assertTrue(new ListProceduresResponse("ok", List.of()).getProcedures().isEmpty());
        final var saveTrigger = new SaveTriggerResponse("ok", 2L, "owner");
        assertEquals(2L, saveTrigger.getVersion());
        assertEquals("owner", saveTrigger.getDefiner());
        assertEquals(OperationType.DELETE_TRIGGER, new DeleteTriggerResponse("ok").getType());
        assertTrue(new ListTriggersResponse("ok", List.of()).getTriggers().isEmpty());
    }

    @Test
    public void test_call_procedure_response_carries_logs_on_both_outcomes() {
        final var ok = new CallProcedureResponse("ok", new JsonString("v"), List.of("a log"), false, "run-1");
        assertEquals("v", ok.getResult().asJsonString().getValue());
        assertEquals(List.of("a log"), ok.getLogs());
        assertFalse(ok.isLogsTruncated());
        ok.setResult(new JsonString("w"));
        ok.setLogs(List.of("another"));
        ok.setLogsTruncated(true);
        assertEquals("w", ok.getResult().asJsonString().getValue());
        assertEquals(List.of("another"), ok.getLogs());
        assertTrue(ok.isLogsTruncated());

        final var failed = new CallProcedureResponse("boom", ErrorCode.SCRIPT_FAILED, List.of("before"), true, "run-1");
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), failed.getErrorCode());
        assertEquals(List.of("before"), failed.getLogs());
        assertTrue(failed.isLogsTruncated());
        assertNull(failed.getResult());
    }
}
