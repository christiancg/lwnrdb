package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.req.TestTriggerRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AuthorizationCheckerTriggerCarveOutTest {
    private static final String OWNER = "carveowner";
    private static final String CARVED_KEY = TestGlobals.DB + "|" + TestGlobals.COLL;

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

    private static AdminUserEntry managerWith(Map<String, PermissionLevel> collectionPermissions) {
        return managerNamed("manager", collectionPermissions);
    }

    private static AdminUserEntry managerNamed(String name, Map<String, PermissionLevel> collectionPermissions) {
        final var scriptPerms = Map.of(TestGlobals.DB, ScriptPermissionLevel.MANAGE);
        return new AdminUserEntry(name, "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(collectionPermissions), new HashMap<>(scriptPerms));
    }

    private static <T extends OperationRequest> T namingTheCarvedCollection(T request) throws Exception {
        final var field = OperationRequest.class.getDeclaredField("collectionName");
        field.setAccessible(true);
        field.set(request, TestGlobals.COLL);
        return request;
    }

    private static List<OperationRequest> triggerRequests() {
        return List.of(new SaveTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", List.of("CREATED"), "p"),
                new DeleteTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t"),
                new TestTriggerRequest(TestGlobals.DB, TestGlobals.COLL, "t", "CREATED", new JsonObject()));
    }

    @Test
    public void test_manage_with_a_read_carve_out_cannot_manage_triggers_there() {
        final var manager = managerWith(Map.of(CARVED_KEY, PermissionLevel.READ));
        for (final var request : triggerRequests()) {
            assertFalse(AuthorizationChecker.check(request, manager).isAllowed(),
                    "a trigger rewrites and vetoes writes, which READ does not cover: " + request.getType().name());
        }
    }

    @Test
    public void test_manage_with_a_read_write_collection_entry_can_manage_triggers() {
        final var manager = managerWith(Map.of(CARVED_KEY, PermissionLevel.READ_WRITE));
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_without_a_collection_entry_is_unchanged() {
        final var manager = managerWith(Map.of());
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_a_carve_out_on_another_collection_does_not_apply() {
        final var manager = managerWith(Map.of(TestGlobals.DB + "|othercoll", PermissionLevel.READ));
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_a_procedure_or_schedule_save_ignores_collection_entries() throws Exception {
        final var manager = managerWith(Map.of(CARVED_KEY, PermissionLevel.READ));
        final var procedure = namingTheCarvedCollection(new SaveProcedureRequest(TestGlobals.DB, "p", "return 1;"));
        final var schedule = new SaveScheduleRequest(TestGlobals.DB, "sch", "p");
        schedule.setIntervalMs(1000L);
        namingTheCarvedCollection(schedule);

        assertTrue(AuthorizationChecker.check(procedure, manager).isAllowed());
        assertTrue(AuthorizationChecker.check(schedule, manager).isAllowed());
    }

    @Test
    public void test_an_owner_is_unaffected_by_a_carve_out() {
        final var owner = managerNamed(OWNER, Map.of(CARVED_KEY, PermissionLevel.READ));
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, owner).isAllowed(), request.getType().name());
        }
    }
}
