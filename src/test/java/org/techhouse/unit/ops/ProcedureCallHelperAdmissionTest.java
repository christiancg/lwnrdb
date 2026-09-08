package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ProcedureCallHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureCallHelperAdmissionTest {
    private static final String ADMIN = "procadmission";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
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
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    @AfterEach
    void restoreAdmission() {
        admission.reconfigure(0, 0L);
    }

    private void store() throws Exception {
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "answer", "return 42;"), ADMIN).getStatus());
    }

    private static OperationResponse call(String name) {
        return ProcedureCallHelper.execute(new CallProcedureRequest(TestGlobals.DB, name, null), ADMIN, null);
    }

    @Test
    public void test_rejects_call_procedure_when_saturated() throws Exception {
        store();
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try {
            final var response = call("answer");
            assertEquals(OperationStatus.ERROR, response.getStatus());
            assertEquals(ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode(), response.getErrorCode());
            assertEquals(OperationType.CALL_PROCEDURE, response.getType());
        } finally {
            admission.release();
        }
    }

    @Test
    public void test_permit_released_after_a_call() throws Exception {
        store();
        admission.reconfigure(1, 0L);
        assertEquals(OperationStatus.OK, call("answer").getStatus());
        assertEquals(1, admission.available());
        assertEquals(OperationStatus.OK, call("answer").getStatus());
    }

    @Test
    public void test_unknown_procedure_does_not_consume_a_permit() {
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try {
            assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), call("nosuch").getErrorCode());
        } finally {
            admission.release();
        }
        assertEquals(1, admission.available());
    }
}
