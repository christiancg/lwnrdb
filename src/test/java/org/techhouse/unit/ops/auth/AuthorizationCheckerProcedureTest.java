package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.DeleteProcedureRequest;
import org.techhouse.ops.req.DeleteScheduleRequest;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.req.TestTriggerRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AuthorizationCheckerProcedureTest {
    private static final String OWNER = "dbowner";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OWNER));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry admin() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private static AdminUserEntry owner() {
        return new AdminUserEntry(OWNER, "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private static AdminUserEntry userWith(ScriptPermissionLevel level, PermissionLevel dbLevel) {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        if (level != null) {
            scriptPerms.put(TestGlobals.DB, level);
        }
        final var dbPerms = new HashMap<String, PermissionLevel>();
        if (dbLevel != null) {
            dbPerms.put(TestGlobals.DB, dbLevel);
        }
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>(), scriptPerms);
    }

    private static SaveProcedureRequest saveProcedure() {
        return new SaveProcedureRequest(TestGlobals.DB, "p", "return 1;");
    }

    private static SaveTriggerRequest saveTrigger() {
        return new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("CREATED"), "p");
    }

    private static SaveScheduleRequest saveSchedule() {
        final var request = new SaveScheduleRequest(TestGlobals.DB, "sch", "p");
        request.setIntervalMs(1000L);
        return request;
    }

    // A schedule runs with its installer's authority, so installing one carries the same privilege as
    // installing a trigger and belongs on the same list.
    // Executing a hook is a management action, not a read: it is grouped with the installers so a RUN-level
    // user cannot run arbitrary code through it.
    private static TestTriggerRequest testTrigger() {
        return new TestTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", "CREATED", new JsonObject());
    }

    private static List<OperationRequest> managementRequests() {
        return List.of(saveProcedure(), new DeleteProcedureRequest(TestGlobals.DB, "p"), saveTrigger(),
                new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t"), saveSchedule(),
                new DeleteScheduleRequest(TestGlobals.DB, "sch"), testTrigger());
    }

    @Test
    public void test_admin_may_save_procedure() {
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, admin()).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_owner_may_save_procedure() {
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, owner()).isAllowed(), request.getType().name());
        }
    }

    // The privilege-laundering guard: whoever installs a procedure hands higher-privileged callers code to
    // execute, and a trigger runs with the installer's authority. Neither may follow from READ_WRITE.
    @Test
    public void test_read_write_user_may_not_save_procedure() {
        final var writer = userWith(null, PermissionLevel.READ_WRITE);
        for (final var request : managementRequests()) {
            assertFalse(AuthorizationChecker.check(request, writer).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_run_level_allows_call_but_not_save() {
        final var runner = userWith(ScriptPermissionLevel.RUN, null);
        assertTrue(AuthorizationChecker.check(new CallProcedureRequest(TestGlobals.DB, "p", null), runner).isAllowed());
        for (final var request : managementRequests()) {
            assertFalse(AuthorizationChecker.check(request, runner).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_level_allows_save_and_call() {
        final var manager = userWith(ScriptPermissionLevel.MANAGE, null);
        assertTrue(
                AuthorizationChecker.check(new CallProcedureRequest(TestGlobals.DB, "p", null), manager).isAllowed());
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_none_level_allows_nothing() {
        final var denied = userWith(ScriptPermissionLevel.NONE, null);
        assertFalse(
                AuthorizationChecker.check(new CallProcedureRequest(TestGlobals.DB, "p", null), denied).isAllowed());
        for (final var request : managementRequests()) {
            assertFalse(AuthorizationChecker.check(request, denied).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_on_one_database_does_not_leak_to_another() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put(TestGlobals.DB, ScriptPermissionLevel.MANAGE);
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("otherDb", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>(),
                scriptPerms);
        assertFalse(
                AuthorizationChecker.check(new SaveProcedureRequest("otherDb", "p", "return 1;"), user).isAllowed());
        assertFalse(AuthorizationChecker.check(new CallProcedureRequest("otherDb", "p", null), user).isAllowed());
    }

    // The ordering trap: these ops must be denied AFTER the ownership short-circuit, never by being listed
    // in ADMIN_ONLY_OPERATIONS - which is tested first and would lock out database owners.
    @Test
    public void test_procedure_ddl_is_not_admin_only_so_owners_are_not_locked_out() {
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, owner()).isAllowed(),
                    request.getType() + " must stay reachable for a database owner");
        }
    }

    @Test
    public void test_list_requires_read_only() {
        final var reader = userWith(null, PermissionLevel.READ);
        assertTrue(AuthorizationChecker.check(new ListProceduresRequest(TestGlobals.DB), reader).isAllowed());
        assertTrue(AuthorizationChecker.check(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL), reader)
                .isAllowed());
        assertTrue(AuthorizationChecker.check(new ListSchedulesRequest(TestGlobals.DB), reader).isAllowed());
        final var nobody = userWith(null, null);
        assertFalse(AuthorizationChecker.check(new ListSchedulesRequest(TestGlobals.DB), nobody).isAllowed());
        assertFalse(AuthorizationChecker.check(new ListProceduresRequest(TestGlobals.DB), nobody).isAllowed());
        assertFalse(AuthorizationChecker.check(new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL), nobody)
                .isAllowed());
    }

    @Test
    public void test_every_new_operation_type_is_covered_by_a_branch() {
        final var nobody = userWith(null, null);
        for (final var type : List.of(OperationType.SAVE_PROCEDURE, OperationType.DELETE_PROCEDURE,
                OperationType.LIST_PROCEDURES, OperationType.CALL_PROCEDURE, OperationType.SAVE_TRIGGER,
                OperationType.DELETE_TRIGGER, OperationType.LIST_TRIGGERS, OperationType.SAVE_SCHEDULE,
                OperationType.DELETE_SCHEDULE, OperationType.LIST_SCHEDULES, OperationType.TEST_TRIGGER)) {
            final var request = switch (type) {
                case SAVE_PROCEDURE -> saveProcedure();
                case DELETE_PROCEDURE -> new DeleteProcedureRequest(TestGlobals.DB, "p");
                case LIST_PROCEDURES -> new ListProceduresRequest(TestGlobals.DB);
                case CALL_PROCEDURE -> new CallProcedureRequest(TestGlobals.DB, "p", null);
                case SAVE_TRIGGER -> saveTrigger();
                case DELETE_TRIGGER -> new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t");
                case SAVE_SCHEDULE -> saveSchedule();
                case DELETE_SCHEDULE -> new DeleteScheduleRequest(TestGlobals.DB, "sch");
                case LIST_SCHEDULES -> new ListSchedulesRequest(TestGlobals.DB);
                case TEST_TRIGGER -> testTrigger();
                default -> new ListTriggersRequest(TestGlobals.DB, TestGlobals.COLL);
            };
            assertFalse(AuthorizationChecker.check(request, nobody).isAllowed(), type.name());
            assertTrue(AuthorizationChecker.check(request, admin()).isAllowed(), type.name());
        }
    }
}
