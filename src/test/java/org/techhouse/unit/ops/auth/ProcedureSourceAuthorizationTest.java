package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.ListProceduresRequest;
import org.techhouse.ops.req.ListSchedulesRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureSourceAuthorizationTest {
    private static final String OWNER = "srcowner";

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

    private static AdminUserEntry user(String name, boolean admin, PermissionLevel dbLevel,
            ScriptPermissionLevel scriptLevel) {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        if (dbLevel != null) {
            dbPerms.put(TestGlobals.DB, dbLevel);
        }
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        if (scriptLevel != null) {
            scriptPerms.put(TestGlobals.DB, scriptLevel);
        }
        return new AdminUserEntry(name, "hash", admin, new HashSet<>(), dbPerms, new HashMap<>(), scriptPerms);
    }

    private static ListProceduresRequest list(boolean includeSource) {
        final var request = new ListProceduresRequest(TestGlobals.DB);
        request.setIncludeSource(includeSource);
        return request;
    }

    private static ListSchedulesRequest schedules(boolean includeArgs) {
        final var request = new ListSchedulesRequest(TestGlobals.DB);
        request.setIncludeArgs(includeArgs);
        return request;
    }

    @Test
    public void test_list_schedules_args_require_manage() {
        final var runner = user("runner", false, PermissionLevel.READ, ScriptPermissionLevel.RUN);
        final var refused = AuthorizationChecker.check(schedules(true), runner);
        assertFalse(refused.isAllowed());
        assertEquals(ErrorCode.SOURCE_REQUIRES_MANAGE, refused.getErrorCode());
        assertTrue(AuthorizationChecker.check(schedules(false), runner).isAllowed());
    }

    @Test
    public void test_list_schedules_args_are_allowed_with_manage_and_for_an_admin() {
        final var manager = user("manager", false, PermissionLevel.READ, ScriptPermissionLevel.MANAGE);
        assertTrue(AuthorizationChecker.check(schedules(true), manager).isAllowed());
        assertTrue(AuthorizationChecker.check(schedules(true), user("root", true, null, null)).isAllowed());
    }

    @Test
    public void test_list_procedures_source_requires_manage() {
        final var reader = user("reader", false, PermissionLevel.READ, ScriptPermissionLevel.RUN);
        final var refused = AuthorizationChecker.check(list(true), reader);
        assertFalse(refused.isAllowed());
        assertEquals(ErrorCode.SOURCE_REQUIRES_MANAGE, refused.getErrorCode());
        assertTrue(AuthorizationChecker.check(list(false), reader).isAllowed());
    }

    @Test
    public void test_list_procedures_source_is_allowed_with_manage() {
        final var manager = user("manager", false, PermissionLevel.READ, ScriptPermissionLevel.MANAGE);
        assertTrue(AuthorizationChecker.check(list(true), manager).isAllowed());
    }

    @Test
    public void test_list_procedures_source_is_allowed_for_an_owner_and_an_admin() {
        assertTrue(AuthorizationChecker.check(list(true), user(OWNER, false, null, null)).isAllowed());
        assertTrue(AuthorizationChecker.check(list(true), user("root", true, null, null)).isAllowed());
    }

    @Test
    public void test_a_caller_without_read_is_refused_before_the_source_rule() {
        final var manager = user("manager", false, null, ScriptPermissionLevel.MANAGE);
        final var refused = AuthorizationChecker.check(list(true), manager);
        assertFalse(refused.isAllowed());
        assertEquals(ErrorCode.NO_PERMISSIONS, refused.getErrorCode());
    }

    @Test
    public void test_may_read_procedure_source_follows_manage_ownership_and_admin() {
        assertFalse(AuthorizationChecker.mayReadProcedureSource(null, TestGlobals.DB));
        assertFalse(AuthorizationChecker.mayReadProcedureSource(
                user("runner", false, PermissionLevel.READ_WRITE, ScriptPermissionLevel.RUN), TestGlobals.DB));
        assertTrue(AuthorizationChecker
                .mayReadProcedureSource(user("manager", false, null, ScriptPermissionLevel.MANAGE), TestGlobals.DB));
        assertTrue(AuthorizationChecker.mayReadProcedureSource(user(OWNER, false, null, null), TestGlobals.DB));
        assertTrue(AuthorizationChecker.mayReadProcedureSource(user("root", true, null, null), TestGlobals.DB));
        assertFalse(AuthorizationChecker.mayReadProcedureSource(user(OWNER, false, null, null), "otherdb"));
        assertFalse(AuthorizationChecker
                .mayReadProcedureSource(user("manager", false, null, ScriptPermissionLevel.MANAGE), "otherdb"));
    }

    @Test
    public void test_owns_or_administers_admits_only_owners_and_admins() {
        assertFalse(AuthorizationChecker.ownsOrAdministers(null, TestGlobals.DB));
        assertFalse(AuthorizationChecker.ownsOrAdministers(user("manager", false, null, ScriptPermissionLevel.MANAGE),
                TestGlobals.DB));
        assertTrue(AuthorizationChecker.ownsOrAdministers(user(OWNER, false, null, null), TestGlobals.DB));
        assertTrue(AuthorizationChecker.ownsOrAdministers(user("root", true, null, null), "otherdb"));
        assertFalse(AuthorizationChecker.ownsOrAdministers(user(OWNER, false, null, null), "otherdb"));
    }
}
