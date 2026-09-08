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
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.mid_operators.ScriptMidOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * A pipeline script executes code, so it needs the same per-database grant RUN_SCRIPT does - READ on the
 * collection is not enough.
 */
public class AuthorizationCheckerScriptOperatorTest {
    private static final String OWNER = "dbowner";
    private static final String SOURCE = "export default (doc) => true;";

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

    private static AdminUserEntry reader(ScriptPermissionLevel level) {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        if (level != null) {
            scriptPerms.put(TestGlobals.DB, level);
        }
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put(TestGlobals.DB, PermissionLevel.READ);
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), dbPerms, new HashMap<>(), scriptPerms);
    }

    private static AggregateRequest aggregate(BaseAggregationStep... steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return request;
    }

    private static AggregateRequest scriptedFilter() {
        return aggregate(new FilterAggregationStep(new ScriptOperator(SOURCE)));
    }

    @Test
    public void test_read_only_user_cannot_use_a_script_operator() {
        assertFalse(AuthorizationChecker.check(scriptedFilter(), reader(null)).isAllowed());
    }

    @Test
    public void test_granted_user_can() {
        assertTrue(AuthorizationChecker.check(scriptedFilter(), reader(ScriptPermissionLevel.RUN)).isAllowed());
    }

    @Test
    public void test_manage_grant_also_allows_running() {
        assertTrue(AuthorizationChecker.check(scriptedFilter(), reader(ScriptPermissionLevel.MANAGE)).isAllowed());
    }

    @Test
    public void test_explicit_none_denies() {
        assertFalse(AuthorizationChecker.check(scriptedFilter(), reader(ScriptPermissionLevel.NONE)).isAllowed());
    }

    @Test
    public void test_admin_can() {
        assertTrue(AuthorizationChecker.check(scriptedFilter(), admin()).isAllowed());
    }

    @Test
    public void test_database_owner_can() {
        assertTrue(AuthorizationChecker.check(scriptedFilter(), owner()).isAllowed());
    }

    @Test
    public void test_plain_aggregate_still_works_for_read_only_user() {
        final var plain = aggregate(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "a", null)));
        assertTrue(AuthorizationChecker.check(plain, reader(null)).isAllowed());
    }

    @Test
    public void test_script_map_operator_needs_the_grant() {
        final var map = new MapAggregationStep(
                List.of(new AddFieldMapOperator("total", null, new ScriptMidOperator(SOURCE))));
        assertFalse(AuthorizationChecker.check(aggregate(map), reader(null)).isAllowed());
        assertTrue(AuthorizationChecker.check(aggregate(map), reader(ScriptPermissionLevel.RUN)).isAllowed());
    }

    @Test
    public void test_reduce_step_needs_the_grant() {
        final var reduce = new ReduceAggregationStep(SOURCE, null, "total");
        assertFalse(AuthorizationChecker.check(aggregate(reduce), reader(null)).isAllowed());
        assertTrue(AuthorizationChecker.check(aggregate(reduce), reader(ScriptPermissionLevel.RUN)).isAllowed());
    }

    @Test
    public void test_script_map_condition_needs_the_grant() {
        final var map = new MapAggregationStep(
                List.of(new AddFieldMapOperator("total", new ScriptOperator(SOURCE), null)));
        assertFalse(AuthorizationChecker.check(aggregate(map), reader(null)).isAllowed());
    }
}
