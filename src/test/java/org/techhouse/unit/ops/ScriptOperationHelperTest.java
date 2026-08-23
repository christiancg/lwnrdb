package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptOperationHelperTest {
    private static final String ADMIN = "scriptrunner";
    private static final Configuration configuration = Configuration.getInstance();

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
        setConfig("scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void enableScripts() throws Exception {
        setConfig("scriptsEnabled", true);
        setConfig("scriptInstructionBudget", 10_000_000L);
        setConfig("scriptTimeoutMs", 5_000L);
        setConfig("scriptMaxDepth", 200);
        setConfig("scriptMaxSourceBytes", 262_144L);
        setConfig("scriptMaxLogLines", 1_000);
        setConfig("scriptMaxLogLineChars", 4_096);
        setConfig("scriptTextImportEnabled", false);
    }

    private static <T> void setConfig(String field, T value) throws Exception {
        TestUtils.setPrivateField(configuration, field, value);
    }

    private static RunScriptResponse run(String script) {
        final var response = ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB, script, null), ADMIN,
                null);
        assertInstanceOf(RunScriptResponse.class, response);
        return (RunScriptResponse) response;
    }

    // Scripts are off by default: the operation is refused before anything is parsed
    @Test
    public void test_returns_403_when_scripts_disabled() throws Exception {
        setConfig("scriptsEnabled", false);
        final var response = ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB, "return 1;", null),
                ADMIN, null);
        assertEquals(OperationStatus.FORBIDDEN, response.getStatus());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), response.getErrorCode());
        assertEquals(OperationType.RUN_SCRIPT, response.getType());
    }

    @Test
    public void test_returns_404_for_unknown_database() {
        final var response = ScriptOperationHelper.execute(new RunScriptRequest("missingDb", "return 1;", null), ADMIN,
                null);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("missingDb"));
    }

    @Test
    public void test_returns_400_10_when_script_exceeds_max_source_bytes() throws Exception {
        setConfig("scriptMaxSourceBytes", 4L);
        final var response = ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, "return 1234567;", null), ADMIN, null);
        assertEquals(ErrorCode.SCRIPT_TOO_LARGE.getCode(), response.getErrorCode());
    }

    @Test
    public void test_returns_script_value() {
        final var response = run("return 1 + 1;");
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getErrorCode());
        assertEquals(2, response.getResult().asJsonNumber().getValue().intValue());
        assertTrue(response.getLogs().isEmpty());
        assertFalse(response.isLogsTruncated());
    }

    @Test
    public void test_returns_named_exports_when_no_return() {
        final var response = run("export const a = 1;");
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(1, response.getResult().asJsonObject().get("a").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_returns_json_null_for_undefined_result() {
        final var response = run("const unused = 1;");
        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(response.getResult().isJsonNull());
    }

    @Test
    public void test_captures_console_output() {
        final var response = run("console.log('first'); console.log('second'); return 1;");
        assertEquals(java.util.List.of("first", "second"), response.getLogs());
        assertFalse(response.isLogsTruncated());
    }

    @Test
    public void test_truncates_console_output() throws Exception {
        setConfig("scriptMaxLogLines", 2);
        final var response = run("console.log('a'); console.log('b'); console.log('c'); return 1;");
        assertEquals(java.util.List.of("b", "c"), response.getLogs());
        assertTrue(response.isLogsTruncated());
    }

    @Test
    public void test_maps_thrown_error_to_400_9() {
        final var response = run("console.log('before'); throw new TypeError('boom');");
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertEquals("TypeError: boom", response.getMessage());
        assertEquals(java.util.List.of("before"), response.getLogs());
        assertNull(response.getResult());
    }

    @Test
    public void test_maps_syntax_error_to_400_9() {
        final var response = run("function (");
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().startsWith("SyntaxError"));
    }

    @Test
    public void test_maps_rejected_promise_to_400_9() {
        final var response = run("return Promise.reject(new Error('nope'));");
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertEquals("Error: nope", response.getMessage());
    }

    @Test
    public void test_maps_timeout_to_408_1() throws Exception {
        setConfig("scriptTimeoutMs", 1L);
        final var response = run("while (true) {}");
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), response.getErrorCode());
    }

    @Test
    public void test_maps_instruction_budget_to_400_11() throws Exception {
        setConfig("scriptInstructionBudget", 1L);
        final var response = run("let i = 0; while (i < 1000) { i++; }");
        assertEquals(ErrorCode.SCRIPT_LIMIT_EXCEEDED.getCode(), response.getErrorCode());
    }

    @Test
    public void test_script_reads_and_writes_its_database() {
        final var response = run("""
                import db from "db";
                db.save(db.name, "%s", { _id: "scripted", value: "written" });
                return db.findById(db.name, "%s", "scripted").value;
                """.formatted(TestGlobals.COLL, TestGlobals.COLL));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals("written", response.getResult().asJsonString().getValue());
    }

    @Test
    public void test_args_are_visible_to_the_script() {
        final var args = new org.techhouse.ejson.elements.JsonObject();
        args.add("foo", new org.techhouse.ejson.elements.JsonString("bar"));
        final var response = ScriptOperationHelper.execute(
                new RunScriptRequest(TestGlobals.DB, "import args from \"args\"; return args.foo;", args), ADMIN, null);
        assertEquals("bar", ((RunScriptResponse) response).getResult().asJsonString().getValue());
    }

    @Test
    public void test_db_name_is_the_scoped_database() {
        final var response = run("import db from \"db\"; return db.name;");
        assertEquals(TestGlobals.DB, response.getResult().asJsonString().getValue());
    }

    @Test
    public void test_script_cannot_reach_another_database() {
        final var response = run("""
                import db from "db";
                try {
                    db.findById("otherDb", "someColl", "x");
                    return "reached";
                } catch (e) {
                    return e.message;
                }
                """);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(response.getResult().asJsonString().getValue().contains("may only access database"));
    }

    // Reading a collection that does not exist answers null instead of failing the run
    @Test
    public void test_missing_collection_reads_as_null() {
        final var response = run("""
                import db from "db";
                return db.findById(db.name, "goneCollection", "x") === null;
                """);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertTrue(response.getResult().asJsonBoolean().getValue());
    }

    // An acting user that no longer exists is rejected on the script's first database call
    @Test
    public void test_unknown_acting_user_fails_the_script() {
        final var response = ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB,
                "import db from \"db\"; return db.findById(db.name, \"" + TestGlobals.COLL + "\", \"x\");", null),
                "ghost", null);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("ghost"));
    }

    @Test
    public void test_unicode_survives_source_and_logs() {
        final var response = run("console.log('días 🎉'); return 'ñandú 🚀';");
        assertEquals("ñandú 🚀", response.getResult().asJsonString().getValue());
        assertEquals(java.util.List.of("días 🎉"), response.getLogs());
    }

    // A refused write is no longer swallowed: the script sees it, and an uncaught one fails the run
    @Test
    public void test_failed_write_surfaces_into_the_script() {
        final var caught = run("""
                import db from "db";
                try {
                    db.save(db.name, "neverCreated", { _id: "x" });
                    return "silently succeeded";
                } catch (e) {
                    return e instanceof Error ? "caught" : "wrong type";
                }
                """);
        assertEquals(OperationStatus.OK, caught.getStatus(), caught.getMessage());
        assertEquals("caught", caught.getResult().asJsonString().getValue());

        final var uncaught = run("import db from \"db\"; db.save(db.name, \"neverCreated\", { _id: \"y\" });");
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), uncaught.getErrorCode());
    }

    @Test
    public void test_delete_of_absent_document_stays_a_no_op_in_a_script() {
        final var response = run("""
                import db from "db";
                db.delete(db.name, "%s", "not-there");
                return "ok";
                """.formatted(TestGlobals.COLL));
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertEquals("ok", response.getResult().asJsonString().getValue());
    }
}
