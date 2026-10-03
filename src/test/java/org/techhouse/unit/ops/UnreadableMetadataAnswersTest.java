package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.ProcedureCallHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleOperationHelper;
import org.techhouse.ops.TriggerOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UnreadableMetadataAnswersTest {
    private static final String ADMIN = "unreadableadmin";
    private static final String UNPARSEABLE = "{not json";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
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
