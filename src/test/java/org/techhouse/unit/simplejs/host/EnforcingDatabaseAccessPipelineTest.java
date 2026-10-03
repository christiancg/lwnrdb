package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class EnforcingDatabaseAccessPipelineTest {
    private static final String ADMIN = "pipelineadmin";
    private static final String UNPARSEABLE = "The command is not valid";

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
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonArray pipelineOf(String stepType) {
        final var step = new JsonObject();
        step.add("type", new JsonString(stepType));
        final var pipeline = new JsonArray();
        pipeline.add(step);
        return pipeline;
    }

    private static ScriptResult run(String body) {
        final var source = "import db from 'db';\n" + body;
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(),
                new EnforcingDatabaseAccess(ADMIN, null), null, ResourceLimits.unlimited()));
    }

    @Test
    public void test_a_filter_step_without_an_operator_is_a_catchable_error() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        final var error = assertThrows(JsThrowException.class,
                () -> db.aggregate(TestGlobals.DB, TestGlobals.COLL, pipelineOf("FILTER")));
        assertInstanceOf(JsObject.class, error.getValue());
    }

    @Test
    public void test_an_unknown_step_type_is_a_catchable_error() {
        final var db = new EnforcingDatabaseAccess(ADMIN, null);
        assertThrows(JsThrowException.class, () -> db.aggregate(TestGlobals.DB, TestGlobals.COLL, pipelineOf("NOPE")));
    }

    @Test
    public void test_an_unparseable_pipeline_is_caught_inside_the_script() {
        final var result = run("try { db.aggregate('" + TestGlobals.DB + "', '" + TestGlobals.COLL
                + "', [{ type: 'FILTER' }]); return 'returned'; } catch (e) { return e.name + ': ' + e.message; }");
        assertFalse(result.isError());
        assertEquals("Error: " + UNPARSEABLE, result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_an_uncaught_unparseable_pipeline_is_not_an_internal_error() {
        final var result = run(
                "db.aggregate('" + TestGlobals.DB + "', '" + TestGlobals.COLL + "', [{ type: 'NOPE' }]);");
        assertTrue(result.isError());
        assertEquals("Error", result.getErrorName());
        assertEquals(UNPARSEABLE, result.getErrorMessage());
    }

    @Test
    public void test_a_cursor_over_an_unparseable_pipeline_is_a_catchable_error() {
        final var result = run("try { for (const doc of db.cursor('" + TestGlobals.DB + "', '" + TestGlobals.COLL
                + "', [{ type: 'FILTER' }])) { return doc; } return 'empty'; } catch (e) { return e.name; }");
        assertFalse(result.isError());
        assertEquals("Error", result.getValue().asJsonString().getValue());
    }
}
