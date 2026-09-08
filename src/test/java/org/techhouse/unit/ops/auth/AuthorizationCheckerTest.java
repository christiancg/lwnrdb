package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CloseConnectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;

public class AuthorizationCheckerTest {
    private AdminUserEntry createAdminUser() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry createNonAdminUser() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    @Test
    public void test_admin_user_allowed_for_every_operation_type() {
        final var admin = createAdminUser();
        final var req = new SaveRequest("testDb", "testColl");
        assertTrue(AuthorizationChecker.check(req, admin).isAllowed());
    }

    @Test
    public void test_non_admin_denied_user_admin_ops() {
        final var user = createNonAdminUser();
        final var createUserReq = new CreateUserRequest();
        assertFalse(AuthorizationChecker.check(createUserReq, user).isAllowed());
    }

    @Test
    public void test_list_transactions_admin_only() {
        final var request = new org.techhouse.ops.req.ListTransactionsRequest();
        assertTrue(AuthorizationChecker.check(request, createAdminUser()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, createNonAdminUser()).isAllowed());
    }

    @Test
    public void test_create_database_requires_global_permission_allow() {
        final var perms = new HashSet<GlobalPermissionType>();
        perms.add(GlobalPermissionType.CREATE_DATABASE);
        final var user = new AdminUserEntry("user", "hash", false, perms, new HashMap<>(), new HashMap<>());
        final var req = new CreateDatabaseRequest("newDb");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_create_database_requires_global_permission_deny() {
        final var user = createNonAdminUser();
        final var req = new CreateDatabaseRequest("newDb");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_find_by_id_with_collection_permission() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new FindByIdRequest("testDb", "testColl");
        req.set_id("123");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_find_by_id_falls_back_to_db_permission() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("testDb", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>());
        final var req = new FindByIdRequest("testDb", "testColl");
        req.set_id("123");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_save_requires_read_write_denied_with_read_only() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new SaveRequest("testDb", "testColl");
        req.setObject(new org.techhouse.ejson.elements.JsonObject());
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_save_allowed_with_read_write() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new SaveRequest("testDb", "testColl");
        req.setObject(new org.techhouse.ejson.elements.JsonObject());
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_list_databases_always_allowed() {
        final var user = createNonAdminUser();
        final var req = new ListDatabasesRequest();
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_close_connection_allowed() {
        final var user = createNonAdminUser();
        final var req = new CloseConnectionRequest();
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_missing_all_permissions_denied() {
        final var user = createNonAdminUser();
        final var req = new SaveRequest("testDb", "testColl");
        req.setObject(new org.techhouse.ejson.elements.JsonObject());
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_null_user() {
        final var req = new SaveRequest("testDb", "testColl");
        assertFalse(AuthorizationChecker.check(req, null).isAllowed());
    }

    private AggregateRequest aggregateWithSteps(List<BaseAggregationStep> steps) {
        final var req = new AggregateRequest("testDb", "testColl");
        req.setAggregationSteps(steps);
        return req;
    }

    private JoinAggregationStep joinStep(String joinCollection) {
        return new JoinAggregationStep(joinCollection, "localField", "remoteField", "asField");
    }

    @Test
    public void test_aggregate_join_user_has_read_on_both_collections_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        collPerms.put("testDb|joinColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_join_user_lacks_join_collection_permission_denied() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        final var result = AuthorizationChecker.check(req, user);
        assertFalse(result.isAllowed());
        assertEquals("action is forbidden, no permissions", result.getReason());
    }

    @Test
    public void test_aggregate_join_db_level_read_covers_join_collection_allowed() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("testDb", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>());
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_join_collection_level_main_but_join_missing_denied() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new FilterAggregationStep(null));
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_multiple_joins_one_forbidden_denied() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        collPerms.put("testDb|joinCollA", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinCollA"));
        steps.add(joinStep("joinCollB"));
        final var req = aggregateWithSteps(steps);
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_without_join_unchanged_behavior_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new FilterAggregationStep(null));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_null_steps_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new AggregateRequest("testDb", "testColl");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_join_admin_allowed() {
        final var admin = createAdminUser();
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, admin).isAllowed());
    }

    @Test
    public void test_aggregate_join_read_write_covers_read_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testDb|testColl", PermissionLevel.READ);
        collPerms.put("testDb|joinColl", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_run_script_allowed_for_admin() {
        final var request = new org.techhouse.ops.req.RunScriptRequest("testDb", "return 1;", null);
        assertTrue(AuthorizationChecker.check(request, createAdminUser()).isAllowed());
    }

    @Test
    public void test_run_script_denied_without_script_permission() {
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("testDb", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>());
        final var request = new org.techhouse.ops.req.RunScriptRequest("testDb", "return 1;", null);
        assertFalse(AuthorizationChecker.check(request, user).isAllowed());
    }

    @Test
    public void test_run_script_allowed_with_script_permission_for_that_database() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testDb", ScriptPermissionLevel.RUN);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPerms);
        final var request = new org.techhouse.ops.req.RunScriptRequest("testDb", "return 1;", null);
        assertTrue(AuthorizationChecker.check(request, user).isAllowed());
    }

    // The grant is per database: it does not carry over to another one
    @Test
    public void test_run_script_denied_for_a_different_database() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testDb", ScriptPermissionLevel.RUN);
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("otherDb", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>(),
                scriptPerms);
        final var request = new org.techhouse.ops.req.RunScriptRequest("otherDb", "return 1;", null);
        assertFalse(AuthorizationChecker.check(request, user).isAllowed());
    }

    // An explicit false is a denial, not a grant
    @Test
    public void test_run_script_denied_when_grant_is_false() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testDb", ScriptPermissionLevel.NONE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPerms);
        final var request = new org.techhouse.ops.req.RunScriptRequest("testDb", "return 1;", null);
        assertFalse(AuthorizationChecker.check(request, user).isAllowed());
    }

    // The script grant alone starts the script; what it may do is still the database/collection permissions
    @Test
    public void test_run_script_grant_does_not_imply_data_access() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testDb", ScriptPermissionLevel.RUN);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPerms);
        assertTrue(AuthorizationChecker
                .check(new org.techhouse.ops.req.RunScriptRequest("testDb", "return 1;", null), user).isAllowed());
        assertFalse(AuthorizationChecker.check(new SaveRequest("testDb", "testColl"), user).isAllowed());
        assertFalse(AuthorizationChecker.check(new FindByIdRequest("testDb", "testColl"), user).isAllowed());
    }
}
