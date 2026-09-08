package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.ops.resp.DeleteScheduleResponse;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.SaveScheduleResponse;

public class ScheduleRequestTest {
    @Test
    public void test_save_schedule_request_accessors() {
        final var request = new SaveScheduleRequest();
        assertEquals(OperationType.SAVE_SCHEDULE, request.getType());
        // Absent args read as an empty object rather than null
        assertTrue(request.getArgs().entrySet().isEmpty());
        request.setName("s");
        request.setProcedureName("p");
        request.setCron("0 3 * * *");
        request.setIntervalMs(2000L);
        request.setTimeoutMs(1000L);
        request.setDescription("nightly");
        request.setIfVersion(4L);
        request.setStampedVersion(5L);
        request.setStampedUpdatedAt(6L);
        request.setStampedUpdatedBy("alice");
        request.setStampedDefiner("owner");
        final var args = new JsonObject();
        args.add("k", new JsonString("v"));
        request.setArgs(args);
        assertEquals("s", request.getName());
        assertEquals("p", request.getProcedureName());
        assertEquals("0 3 * * *", request.getCron());
        assertEquals(2000L, request.getIntervalMs());
        assertEquals(1000L, request.getTimeoutMs());
        assertEquals("nightly", request.getDescription());
        assertEquals(4L, request.getIfVersion());
        assertEquals(5L, request.getStampedVersion());
        assertEquals(6L, request.getStampedUpdatedAt());
        assertEquals("alice", request.getStampedUpdatedBy());
        assertEquals("owner", request.getStampedDefiner());
        assertEquals("v", request.getArgs().get("k").asJsonString().getValue());
        // Absent enabled reads as enabled
        assertTrue(request.isEnabled());
        request.setEnabled(false);
        assertFalse(request.isEnabled());
    }

    @Test
    public void test_delete_and_list_schedule_request_accessors() {
        final var delete = new DeleteScheduleRequest();
        assertEquals(OperationType.DELETE_SCHEDULE, delete.getType());
        delete.setName("s");
        assertEquals("s", delete.getName());
        assertEquals("db", new DeleteScheduleRequest("db", "s").getDatabaseName());
        assertEquals(OperationType.LIST_SCHEDULES, new ListSchedulesRequest().getType());
        assertEquals("db", new ListSchedulesRequest("db").getDatabaseName());
    }

    @Test
    public void test_requests_parse_from_the_wire() {
        final var save = (SaveScheduleRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_SCHEDULE\",\"databaseName\":\"db\",\"name\":\"s\",\"procedureName\":\"p\","
                        + "\"cron\":\"0 3 * * *\",\"args\":{\"days\":1},\"timeoutMs\":60000,\"ifVersion\":2}");
        assertEquals("s", save.getName());
        assertEquals("p", save.getProcedureName());
        assertEquals("0 3 * * *", save.getCron());
        assertEquals(60000L, save.getTimeoutMs());
        assertEquals(2L, save.getIfVersion());
        assertEquals(1d, save.getArgs().get("days").asJsonNumber().getValue().doubleValue());

        assertInstanceOf(DeleteScheduleRequest.class,
                RequestParser.parseRequest("{\"type\":\"DELETE_SCHEDULE\",\"databaseName\":\"db\",\"name\":\"s\"}"));
        assertInstanceOf(ListSchedulesRequest.class,
                RequestParser.parseRequest("{\"type\":\"LIST_SCHEDULES\",\"databaseName\":\"db\"}"));
    }

    @Test
    public void test_response_accessors() {
        assertEquals(3L, new SaveScheduleResponse("ok", 3L).getVersion());
        assertEquals(OperationType.DELETE_SCHEDULE, new DeleteScheduleResponse("ok").getType());
        assertTrue(new ListSchedulesResponse("ok", List.of()).getSchedules().isEmpty());
    }

    @Test
    public void test_validation_rejects_bad_names_and_databases() {
        final var request = new SaveScheduleRequest("shop", "nightly", "rollup");
        request.setIntervalMs(1000L);
        assertTrue(RequestValidator.validate(request).isValid());

        assertFalse(RequestValidator.validate(new SaveScheduleRequest(null, "nightly", "rollup")).isValid());
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("admin", "nightly", "rollup")).isValid());
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("shop", "x", "rollup")).isValid());
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("shop", "nightly", "x")).isValid());
        assertFalse(RequestValidator.validate(new DeleteScheduleRequest("shop", "x")).isValid());
        assertTrue(RequestValidator.validate(new DeleteScheduleRequest("shop", "nightly")).isValid());
        assertTrue(RequestValidator.validate(new ListSchedulesRequest("shop")).isValid());
        assertFalse(RequestValidator.validate(new ListSchedulesRequest("ad")).isValid());
    }
}
