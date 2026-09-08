package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterAdminHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.SaveScheduleResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class OperationProcessorScheduleTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
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
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
        registry.clear();
        processor.processMessage(new SaveProcedureRequest(TestGlobals.DB, "job", "return 1;"));
    }

    private static SaveScheduleRequest saveRequest() {
        final var request = new SaveScheduleRequest(TestGlobals.DB, "nightly", "job");
        request.setIntervalMs(60_000L);
        return request;
    }

    @Test
    public void test_dispatches_save_list_and_delete() {
        assertInstanceOf(SaveScheduleResponse.class, processor.processMessage(saveRequest()));
        final var listed = (ListSchedulesResponse) processor.processMessage(new ListSchedulesRequest(TestGlobals.DB));
        assertEquals(1, listed.getSchedules().size());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DeleteScheduleRequest(TestGlobals.DB, "nightly")).getStatus());
        assertNull(cache.getSchedule(TestGlobals.DB, "nightly"));
    }

    @Test
    public void test_save_failure_is_reported_as_an_error_code() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), processor.processMessage(saveRequest()).getErrorCode());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                processor.processMessage(new DeleteScheduleRequest(TestGlobals.DB, "nightly")).getErrorCode());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                processor.processMessage(new ListSchedulesRequest(TestGlobals.DB)).getErrorCode());
    }

    // Both mutations are coordinator-serialized DDL, replicated by re-execution like SAVE_PROCEDURE.
    @Test
    public void test_schedule_mutations_are_coordinated_admin_ops() {
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.SAVE_SCHEDULE));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.DELETE_SCHEDULE));
        assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.LIST_SCHEDULES));
    }

    // Dropping the database takes its .schedules folder with it, so no cascade code is needed.
    @Test
    public void test_drop_database_removes_schedules() {
        final var dbName = "scheddropdb";
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(dbName)).getStatus());
        processor.processMessage(new SaveProcedureRequest(dbName, "job", "return 1;"));
        final var request = new SaveScheduleRequest(dbName, "nightly", "job");
        request.setIntervalMs(60_000L);
        assertInstanceOf(SaveScheduleResponse.class, processor.processMessage(request));

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(dbName)).getStatus());
        assertTrue(fs.listScheduleNames(dbName).isEmpty());
        assertNull(cache.getSchedule(dbName, "nightly"));
    }
}
