package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureCallHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.CallProcedureResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureCallHelperTest {
    private static final String ADMIN = "procadmin";
    private static final String READER = "procreader";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        createUser(ADMIN, true, new HashMap<>());
        final var readOnly = new HashMap<String, PermissionLevel>();
        readOnly.put(TestGlobals.DB, PermissionLevel.READ);
        createUser(READER, false, readOnly);
    }

    private static void createUser(String username, boolean admin, HashMap<String, PermissionLevel> dbPerms) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(admin);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(dbPerms);
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        // Deleting the files directly bypasses the handler that would invalidate the compiled cache, and
        // a re-created procedure restarts at version 1 - so the previous test's program would be served.
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    private void store(String name, String script) throws Exception {
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, name, script), ADMIN).getStatus());
    }

    private CallProcedureResponse call(String name, String user) {
        return call(name, user, null);
    }

    private CallProcedureResponse call(String name, String user, JsonObject args) {
        final var response = ProcedureCallHelper.execute(new CallProcedureRequest(TestGlobals.DB, name, args), user,
                null);
        assertInstanceOf(CallProcedureResponse.class, response, response.getMessage());
        return (CallProcedureResponse) response;
    }

    @Test
    public void test_calls_stored_procedure_and_returns_result() throws Exception {
        store("answer", "return 42;");
        final var response = call("answer", ADMIN);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(42d, response.getResult().asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_passes_args_to_the_procedure() throws Exception {
        store("greet", "import args from 'args'; return 'hi ' + args.name;");
        final var args = new JsonObject();
        args.add("name", new JsonString("ada"));
        assertEquals("hi ada", call("greet", ADMIN, args).getResult().asJsonString().getValue());
    }

    @Test
    public void test_returns_403_when_scripts_disabled() throws Exception {
        store("answer", "return 42;");
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        final var response = ProcedureCallHelper.execute(new CallProcedureRequest(TestGlobals.DB, "answer", null),
                ADMIN, null);
        assertEquals(OperationStatus.FORBIDDEN, response.getStatus());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), response.getErrorCode());
        assertEquals(OperationType.CALL_PROCEDURE, response.getType());
    }

    @Test
    public void test_unknown_procedure_is_not_found() {
        final var response = ProcedureCallHelper.execute(new CallProcedureRequest(TestGlobals.DB, "nothing", null),
                ADMIN, null);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_unknown_database_is_not_found() {
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), ProcedureCallHelper
                .execute(new CallProcedureRequest("missingDb", "p", null), ADMIN, null).getErrorCode());
    }

    // A disabled procedure answers not-found: the caller asked for it to run, and nothing ran
    @Test
    public void test_disabled_procedure_is_not_found() throws Exception {
        final var request = new SaveProcedureRequest(TestGlobals.DB, "off", "return 1;");
        request.setEnabled(false);
        ProcedureOperationHelper.executeSave(request, ADMIN);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), ProcedureCallHelper
                .execute(new CallProcedureRequest(TestGlobals.DB, "off", null), ADMIN, null).getErrorCode());
    }

    // Invoker rights: the procedure can never do more than its caller could
    @Test
    public void test_runs_with_callers_authority() throws Exception {
        store("write",
                "import db from 'db'; db.save(db.name, '" + TestGlobals.COLL + "', { _id: 'x', v: 1 }); return 'ok';");
        assertEquals(OperationStatus.OK, call("write", ADMIN).getStatus());
        final var refused = call("write", READER);
        assertNotEquals(OperationStatus.OK, refused.getStatus());
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), refused.getErrorCode());
        assertTrue(refused.getMessage().contains("forbidden"), refused.getMessage());
    }

    @Test
    public void test_throw_maps_to_400_9() throws Exception {
        store("boom", "throw new Error('nope');");
        final var response = call("boom", ADMIN);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("nope"));
    }

    @Test
    public void test_timeout_maps_to_408() throws Exception {
        store("spin", "while (true) { }");
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 50L);
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), call("spin", ADMIN).getErrorCode());
    }

    @Test
    public void test_limit_maps_to_400_11() throws Exception {
        store("spin", "let i = 0; while (true) { i++; }");
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 500L);
        assertEquals(ErrorCode.SCRIPT_LIMIT_EXCEEDED.getCode(), call("spin", ADMIN).getErrorCode());
    }

    @Test
    public void test_memory_maps_to_400_12() throws Exception {
        store("hog", "return 'x'.repeat(100000000);");
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 1_024L);
        assertEquals(ErrorCode.SCRIPT_MEMORY_EXCEEDED.getCode(), call("hog", ADMIN).getErrorCode());
    }

    @Test
    public void test_logs_ride_along_on_failure() throws Exception {
        store("noisy", "console.log('before the throw'); throw new Error('nope');");
        final var response = call("noisy", ADMIN);
        assertNotNull(response.getErrorCode());
        assertEquals(1, response.getLogs().size());
        assertTrue(response.getLogs().getFirst().contains("before the throw"));
        assertFalse(response.isLogsTruncated());
    }

    @Test
    public void test_uses_compiled_cache_on_second_call() throws Exception {
        store("answer", "return 42;");
        final var compiledCache = IocContainer.get(CompiledProcedureCache.class);
        final var before = compiledCache.size();
        call("answer", ADMIN);
        call("answer", ADMIN);
        assertEquals(before + 1, compiledCache.size());
    }

    // A new version must not serve the old compiled program
    @Test
    public void test_new_version_runs_the_new_body() throws Exception {
        store("answer", "return 1;");
        assertEquals(1d, call("answer", ADMIN).getResult().asJsonNumber().getValue().doubleValue());
        store("answer", "return 2;");
        assertEquals(2d, call("answer", ADMIN).getResult().asJsonNumber().getValue().doubleValue());
    }
}
