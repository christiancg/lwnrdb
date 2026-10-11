package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureCallHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteSchemaRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveSchemaRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MetadataDeleteFailureTest {
    private static final String ADMIN = "unreadableadmin";
    private static final String UNPARSEABLE = "{not json";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private File lockedFolder;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
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
        TestUtils.setPrivateField(configuration, "scheduleMaxPerDatabase", 100);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        for (final var name : fs.listScheduleNames(TestGlobals.DB)) {
            fs.deleteSchedule(TestGlobals.DB, name);
        }
        fs.deleteTriggers(TestGlobals.DB, TestGlobals.COLL);
        cache.removeProceduresForDatabase(TestGlobals.DB);
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(OperationStatus.OK,
                processor.processMessage(new SaveProcedureRequest(TestGlobals.DB, "kept", "return 1;")).getStatus());
    }

    @AfterEach
    void unlock() throws Exception {
        if (lockedFolder != null) {
            Files.setPosixFilePermissions(lockedFolder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            lockedFolder = null;
        }
    }

    private void lock(String relativeFolder) throws Exception {
        lockedFolder = new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + relativeFolder);
        Files.setPosixFilePermissions(lockedFolder.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        assumeFalse(Files.isWritable(lockedFolder.toPath()), "a superuser ignores the permission bits");
    }

    @Test
    public void test_a_procedure_whose_file_cannot_be_removed_answers_an_error_and_stays() throws Exception {
        assertEquals(OperationStatus.OK, processor
                .processMessage(new SaveProcedureRequest(TestGlobals.DB, "unreferenced", "return 1;")).getStatus());
        lock(Globals.PROCEDURES_FOLDER);

        final var response = processor.processMessage(new DeleteProcedureRequest(TestGlobals.DB, "unreferenced"));

        assertEquals(ErrorCode.ERROR_DELETING_PROCEDURE.getCode(), response.getErrorCode(), response.getMessage());
        assertNotNull(cache.getProcedure(TestGlobals.DB, "unreferenced"));
    }

    @Test
    public void test_a_schedule_whose_file_cannot_be_removed_answers_an_error_and_stays_scheduled() throws Exception {
        final var save = new SaveScheduleRequest(TestGlobals.DB, "nightly", "kept");
        save.setIntervalMs(60_000L);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
        lock(Globals.SCHEDULES_FOLDER);

        final var response = processor.processMessage(new DeleteScheduleRequest(TestGlobals.DB, "nightly"));

        assertEquals(ErrorCode.ERROR_DELETING_SCHEDULE.getCode(), response.getErrorCode(), response.getMessage());
        assertNotNull(cache.getSchedule(TestGlobals.DB, "nightly"));
        assertNotNull(IocContainer.get(ScheduleRegistry.class).get(TestGlobals.DB, "nightly"));
    }

    @Test
    public void test_a_schema_whose_file_cannot_be_removed_answers_an_error_and_stays() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(new SaveSchemaRequest(TestGlobals.DB,
                TestGlobals.COLL, eJson.fromJson("{\"type\":\"object\"}", JsonObject.class))).getStatus());
        lock(TestGlobals.COLL);

        final var response = processor.processMessage(new DeleteSchemaRequest(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(ErrorCode.ERROR_DELETING_SCHEMA.getCode(), response.getErrorCode(), response.getMessage());
        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_the_last_trigger_whose_file_cannot_be_removed_answers_an_error_and_stays() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(
                new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "onInsert", List.of("CREATED"), "kept"))
                .getStatus());
        lock(TestGlobals.COLL);

        final var response = processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "onInsert"));

        assertEquals(ErrorCode.ERROR_DELETING_TRIGGER.getCode(), response.getErrorCode(), response.getMessage());
        assertFalse(cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_listing_procedures_answers_an_error_when_a_definition_is_unparseable() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "broken", UNPARSEABLE);

        final var response = ProcedureOperationHelper.executeList(new ListProceduresRequest(TestGlobals.DB));

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }

    @Test
    public void test_listing_triggers_answers_an_error_when_a_trigger_file_is_unparseable() throws Exception {
        fs.writeTriggers(TestGlobals.DB, TestGlobals.COLL, UNPARSEABLE);

        final var response = TriggerOperationHelper
                .executeList(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }

    @Test
    public void test_listing_schedules_answers_an_error_when_a_definition_is_unparseable() throws Exception {
        fs.writeSchedule(TestGlobals.DB, "broken", UNPARSEABLE);

        final var response = ScheduleOperationHelper.executeList(new ListSchedulesRequest(TestGlobals.DB));

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }

    @Test
    public void test_calling_a_procedure_answers_an_error_when_its_definition_is_unparseable() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "broken", UNPARSEABLE);
        final var request = new CallProcedureRequest(TestGlobals.DB, "broken", new JsonObject());

        final var response = ProcedureCallHelper.execute(request, ADMIN, null);

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }
}
