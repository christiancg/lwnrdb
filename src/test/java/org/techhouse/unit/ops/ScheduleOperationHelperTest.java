package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.SaveScheduleResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScheduleOperationHelperTest {
    private static final String ACTOR = "alice";
    private static final String PROCEDURE = "rollup";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerDatabase", 100);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        for (final var name : fs.listScheduleNames(TestGlobals.DB)) {
            fs.deleteSchedule(TestGlobals.DB, name);
        }
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        cache.removeProceduresForDatabase(TestGlobals.DB);
        registry.clear();
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, PROCEDURE, "return 1;"), ACTOR);
    }

    private static SaveScheduleRequest intervalRequest(String name) {
        final var request = new SaveScheduleRequest(TestGlobals.DB, name, PROCEDURE);
        request.setIntervalMs(2000L);
        return request;
    }

    private static SaveScheduleRequest cronRequest(String name, String cron) {
        final var request = new SaveScheduleRequest(TestGlobals.DB, name, PROCEDURE);
        request.setCron(cron);
        return request;
    }

    private SaveScheduleResponse save(SaveScheduleRequest request) throws Exception {
        final var response = ScheduleOperationHelper.executeSave(request, ACTOR);
        assertInstanceOf(SaveScheduleResponse.class, response, response.getMessage());
        return (SaveScheduleResponse) response;
    }

    @Test
    public void test_save_persists_and_caches() throws Exception {
        final var args = new JsonObject();
        args.add("days", new JsonNumber(1));
        final var request = cronRequest("nightly", "0 3 * * *");
        request.setArgs(args);
        request.setDescription("daily rollup");
        assertEquals(1L, save(request).getVersion());

        final var stored = cache.getSchedule(TestGlobals.DB, "nightly");
        assertNotNull(stored);
        assertEquals(PROCEDURE, stored.getProcedureName());
        assertEquals("0 3 * * *", stored.getCron());
        assertEquals(ACTOR, stored.getDefiner());
        assertEquals("daily rollup", stored.getDescription());
        assertEquals(1, stored.getArgs().get("days").asJsonNumber().getValue().intValue());
        assertNotNull(registry.get(TestGlobals.DB, "nightly"));
    }

    @Test
    public void test_save_bumps_the_version() throws Exception {
        assertEquals(1L, save(intervalRequest("s")).getVersion());
        assertEquals(2L, save(intervalRequest("s")).getVersion());
        assertEquals(2L, cache.getSchedule(TestGlobals.DB, "s").getVersion());
    }

    // Re-execution on a peer must write a byte-identical file, so every derived field is stamped once.
    @Test
    public void test_save_stamps_derived_fields_onto_the_request() throws Exception {
        final var request = intervalRequest("s");
        save(request);
        assertEquals(1L, request.getStampedVersion());
        assertTrue(request.getStampedUpdatedAt() > 0);
        assertEquals(ACTOR, request.getStampedUpdatedBy());
        assertEquals(ACTOR, request.getStampedDefiner());

        fs.deleteSchedule(TestGlobals.DB, "s");
        cache.removeSchedule(TestGlobals.DB, "s");
        final var replayed = ScheduleOperationHelper.executeSave(request, "somebody-else");
        assertEquals(1L, ((SaveScheduleResponse) replayed).getVersion());
        assertEquals(ACTOR, cache.getSchedule(TestGlobals.DB, "s").getDefiner());
    }

    @Test
    public void test_save_is_rejected_when_schedules_are_disabled() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        final var response = ScheduleOperationHelper.executeSave(intervalRequest("s"), ACTOR);
        assertEquals(OperationStatus.FORBIDDEN, response.getStatus());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), response.getErrorCode());
        assertEquals(OperationType.SAVE_SCHEDULE, response.getType());
    }

    @Test
    public void test_save_rejects_an_unknown_database() throws Exception {
        final var request = new SaveScheduleRequest("missingDb", "s", PROCEDURE);
        request.setIntervalMs(1000L);
        final var response = ScheduleOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_save_rejects_an_unknown_procedure() throws Exception {
        final var request = new SaveScheduleRequest(TestGlobals.DB, "s", "nope");
        request.setIntervalMs(1000L);
        final var response = ScheduleOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_save_rejects_both_cron_and_interval() throws Exception {
        final var request = cronRequest("s", "0 3 * * *");
        request.setIntervalMs(1000L);
        final var response = ScheduleOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_SCHEDULE.getCode(), response.getErrorCode());
    }

    @Test
    public void test_save_rejects_neither_cron_nor_interval() throws Exception {
        final var response = ScheduleOperationHelper
                .executeSave(new SaveScheduleRequest(TestGlobals.DB, "s", PROCEDURE), ACTOR);
        assertEquals(ErrorCode.INVALID_SCHEDULE.getCode(), response.getErrorCode());
    }

    @Test
    public void test_save_rejects_a_malformed_cron() throws Exception {
        final var response = ScheduleOperationHelper.executeSave(cronRequest("s", "not a cron"), ACTOR);
        assertEquals(ErrorCode.INVALID_SCHEDULE.getCode(), response.getErrorCode());
        assertNull(cache.getSchedule(TestGlobals.DB, "s"));
    }

    @Test
    public void test_save_rejects_a_negative_timeout() throws Exception {
        final var request = intervalRequest("s");
        request.setTimeoutMs(-1L);
        assertEquals(ErrorCode.INVALID_SCHEDULE.getCode(),
                ScheduleOperationHelper.executeSave(request, ACTOR).getErrorCode());
    }

    @Test
    public void test_save_rejects_past_the_database_cap() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleMaxPerDatabase", 1);
        save(intervalRequest("one"));
        final var response = ScheduleOperationHelper.executeSave(intervalRequest("two"), ACTOR);
        assertEquals(ErrorCode.TOO_MANY_SCHEDULES.getCode(), response.getErrorCode());
        // The cap bounds new schedules only; editing one that already exists still works.
        assertEquals(2L, save(intervalRequest("one")).getVersion());
    }

    @Test
    public void test_save_honours_if_version() throws Exception {
        save(intervalRequest("s"));
        final var stale = intervalRequest("s");
        stale.setIfVersion(5L);
        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(),
                ScheduleOperationHelper.executeSave(stale, ACTOR).getErrorCode());
        final var current = intervalRequest("s");
        current.setIfVersion(1L);
        assertEquals(2L, save(current).getVersion());
    }

    @Test
    public void test_delete_is_idempotent_and_refreshes_the_registry() throws Exception {
        save(intervalRequest("s"));
        assertNotNull(registry.get(TestGlobals.DB, "s"));
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "s")).getStatus());
        assertNull(cache.getSchedule(TestGlobals.DB, "s"));
        assertNull(registry.get(TestGlobals.DB, "s"));
        assertEquals(OperationStatus.OK,
                ScheduleOperationHelper.executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "s")).getStatus());
    }

    @Test
    public void test_delete_rejects_an_unknown_database_and_a_disabled_feature() throws Exception {
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(),
                ScheduleOperationHelper.executeDelete(new DeleteScheduleRequest("missingDb", "s")).getErrorCode());
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                ScheduleOperationHelper.executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "s")).getErrorCode());
    }

    @Test
    public void test_deleting_a_procedure_referenced_by_a_schedule_is_refused() throws Exception {
        save(intervalRequest("s"));
        final var response = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, PROCEDURE));
        assertEquals(ErrorCode.INVALID_SCHEDULE.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("'s'"), response.getMessage());
        assertNotNull(cache.getProcedure(TestGlobals.DB, PROCEDURE));

        ScheduleOperationHelper.executeDelete(new DeleteScheduleRequest(TestGlobals.DB, "s"));
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.DB, PROCEDURE)).getStatus());
    }

    @Test
    public void test_list_returns_summaries_with_next_run_at() throws Exception {
        save(cronRequest("nightly", "0 3 * * *"));
        final var response = ScheduleOperationHelper.executeList(new ListSchedulesRequest(TestGlobals.DB));
        assertInstanceOf(ListSchedulesResponse.class, response);
        final var schedules = ((ListSchedulesResponse) response).getSchedules();
        assertEquals(1, schedules.size());
        final var summary = schedules.getFirst();
        assertFalse(summary.has("args"));
        assertEquals("nightly", summary.get("name").asJsonString().getValue());
        assertTrue(summary.get("nextRunAt").asJsonNumber().getValue().longValue() > System.currentTimeMillis());
    }

    @Test
    public void test_list_on_an_unknown_database_and_a_disabled_feature() throws Exception {
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(),
                ScheduleOperationHelper.executeList(new ListSchedulesRequest("missingDb")).getErrorCode());
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                ScheduleOperationHelper.executeList(new ListSchedulesRequest(TestGlobals.DB)).getErrorCode());
    }
}
