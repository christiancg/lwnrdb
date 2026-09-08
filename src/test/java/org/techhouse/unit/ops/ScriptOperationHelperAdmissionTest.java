package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptOperationHelperAdmissionTest {
    private static final String ADMIN = "scriptadmission";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);

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
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
    }

    @AfterEach
    void restoreAdmission() {
        admission.reconfigure(0, 0L);
    }

    // The singleton is the one ScriptOperationHelper captured in its static field, so the cap has to be
    // resized in place rather than by handing the helper a different instance.

    // A poll, not a spin: the run holding the permit is on another thread and signals nothing, and a
    // ten-second onSpinWait would burn a core waiting for a 100ms handover.
    private static void awaitAvailable() throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && admission.available() != 0) {
            TimeUnit.MILLISECONDS.sleep(5L);
        }
    }

    private static OperationResponse run(String script) {
        return ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB, script, null), ADMIN, null);
    }

    @Test
    public void test_rejects_run_script_when_saturated() {
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try {
            final var response = run("return 1;");
            assertEquals(OperationStatus.ERROR, response.getStatus());
            assertEquals(ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode(), response.getErrorCode());
            assertEquals(OperationType.RUN_SCRIPT, response.getType());
        } finally {
            admission.release();
        }
    }

    @Test
    public void test_permit_released_after_a_successful_run() {
        admission.reconfigure(1, 0L);
        assertEquals(OperationStatus.OK, run("return 1;").getStatus());
        assertEquals(1, admission.available());
        assertEquals(OperationStatus.OK, run("return 2;").getStatus());
    }

    @Test
    public void test_permit_released_after_a_failing_run() {
        admission.reconfigure(1, 0L);
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), run("throw new Error('nope');").getErrorCode());
        assertEquals(1, admission.available());
        assertEquals(OperationStatus.OK, run("return 1;").getStatus());
    }

    // A timeout unwinds through a ScriptAbortException that user code cannot catch, so the finally release
    // is the only thing that returns the permit.
    @Test
    public void test_permit_released_after_a_timeout() throws Exception {
        admission.reconfigure(1, 0L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 1L);
        final var timedOut = run("while (true) {}");
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), timedOut.getErrorCode());
        assertEquals(1, admission.available());
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        assertEquals(OperationStatus.OK, run("return 1;").getStatus());
    }

    // A doomed request must not spend a permit: the cheap checks answer first, so the pool stays available
    // for a request that would actually run.
    @Test
    public void test_rejection_happens_after_the_cheaper_checks() throws Exception {
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try {
            TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
            final var disabled = run("return 1;");
            assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), disabled.getErrorCode());

            TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
            final var unknownDb = ScriptOperationHelper.execute(new RunScriptRequest("missingDb", "return 1;", null),
                    ADMIN, null);
            assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), unknownDb.getErrorCode());

            TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 4L);
            final var tooLarge = run("return 1234567;");
            assertEquals(ErrorCode.SCRIPT_TOO_LARGE.getCode(), tooLarge.getErrorCode());
        } finally {
            admission.release();
        }
        assertEquals(1, admission.available());
    }

    @Test
    public void test_disabled_cap_allows_concurrent_runs() throws Exception {
        admission.reconfigure(0, 0L);
        final var first = new AtomicReference<OperationResponse>();
        final var second = new AtomicReference<OperationResponse>();
        final var done = new CountDownLatch(2);
        final var script = "export default new Promise(r => setTimeout(() => r(1), 300));";
        Thread.ofVirtual().start(() -> {
            first.set(run(script));
            done.countDown();
        });
        Thread.ofVirtual().start(() -> {
            second.set(run(script));
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.OK, first.get().getStatus(), first.get().getMessage());
        assertEquals(OperationStatus.OK, second.get().getStatus(), second.get().getMessage());
    }

    // The permit is held across the whole run, transaction included, but the permit and the collection
    // locks are unrelated: a second caller queues on the permit and is refused cleanly rather than
    // deadlocking against the transaction's locks.
    @Test
    public void test_a_transactional_run_holds_its_permit_without_deadlocking() throws Exception {
        admission.reconfigure(1, 0L);
        final var transactional = new AtomicReference<OperationResponse>();
        final var done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            transactional.set(run("import db from 'db'; return db.transaction(() => {" + " db.save(db.name, '"
                    + TestGlobals.COLL + "', { _id: 'tx-permit', value: 1 });"
                    + " for (let i = 0; i < 3000000; i++) {} return 'ok'; });"));
            done.countDown();
        });
        // Sampled rather than signalled: the script cannot reach back into the test, so the observable fact
        // is that the permit is gone while the transactional run is still in flight.
        awaitAvailable();
        final var refused = run("return 1;");
        assertEquals(ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode(), refused.getErrorCode());
        assertTrue(done.await(30, TimeUnit.SECONDS), "the transactional run never completed");
        assertEquals(OperationStatus.OK, transactional.get().getStatus(), transactional.get().getMessage());
        assertEquals(1, admission.available(), "the transactional run leaked its permit");
    }

    // An allocation abort unwinds the same way a timeout does, so the finally release is again the only
    // thing that returns the permit.
    @Test
    public void test_permit_released_after_a_memory_abort() throws Exception {
        admission.reconfigure(1, 0L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 1_000_000L);
        final var aborted = run("return 'x'.repeat(100000000);");
        assertEquals(ErrorCode.SCRIPT_MEMORY_EXCEEDED.getCode(), aborted.getErrorCode());
        assertEquals(1, admission.available());
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        assertEquals(OperationStatus.OK, run("return 1;").getStatus());
    }

    // The wait may legally exceed scriptTimeoutMs: a caller can wait longer than a run takes, and the
    // queueing is what absorbs a burst instead of erroring on it.
    @Test
    public void test_wait_is_not_bounded_by_the_run_timeout() throws Exception {
        admission.reconfigure(1, 3_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 200L);
        assertTrue(admission.tryAcquire());
        final var response = new AtomicReference<OperationResponse>();
        final var done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            response.set(run("return 1;"));
            done.countDown();
        });
        Thread.sleep(600L);
        admission.release();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertEquals(OperationStatus.OK, response.get().getStatus(), response.get().getMessage());
        assertNotEquals(0, admission.getWaited());
    }
}
