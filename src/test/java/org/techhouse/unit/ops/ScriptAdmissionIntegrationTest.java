package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ShutdownCoordinator;
import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.ScriptLoad;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The cap under real concurrency: enough overlapping runs to exceed it, on the real setup, asserting both
 * that the ceiling holds and that the wait is what decides between queueing and rejecting.
 */
public class ScriptAdmissionIntegrationTest {
    private static final String ADMIN = "admissionclient";
    private static final String SLOW_SCRIPT = "export default new Promise(r => setTimeout(() => r(1), 200));";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final ScriptLoad scriptLoad = IocContainer.get(ScriptLoad.class);

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

    private static List<OperationResponse> burst() throws Exception {
        final var responses = new ConcurrentLinkedQueue<OperationResponse>();
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(6);
        for (var i = 0; i < 6; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    responses.add(ScriptOperationHelper.execute(new RunScriptRequest(TestGlobals.DB, SLOW_SCRIPT, null),
                            ADMIN, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        return List.copyOf(responses);
    }

    private static long countWith(List<OperationResponse> responses) {
        return responses.stream().filter(r -> ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode().equals(r.getErrorCode()))
                .count();
    }

    @Test
    public void test_concurrent_runs_are_capped_and_the_rejection_is_clean() throws Exception {
        admission.reconfigure(2, 0L);
        // Sampled on its own thread, because the ceiling is the claim: response counts alone cannot
        // tell "the cap held" from "the runs never overlapped". It stops when the burst does, rather
        // than spinning out a fixed deadline, and sleeps between samples - a 200ms script cannot
        // overshoot the cap invisibly at a 2ms sampling interval.
        final var peak = new AtomicInteger();
        final var sampling = new AtomicBoolean(true);
        final var sampler = Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                peak.getAndUpdate(current -> Math.max(current, scriptLoad.current()));
                try {
                    TimeUnit.MILLISECONDS.sleep(2L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        final var responses = burst();
        sampling.set(false);
        sampler.join();

        final var succeeded = responses.stream().filter(r -> r.getStatus() == OperationStatus.OK).toList();
        final var rejected = countWith(responses);
        assertEquals(6, responses.size());
        assertEquals(6 - succeeded.size(), rejected, "every non-OK response must be the capacity rejection");
        assertTrue(!succeeded.isEmpty() && succeeded.size() <= 2,
                "at most the capacity may run with no wait, but " + succeeded.size() + " succeeded");
        assertTrue(peak.get() <= 2, "the cap was exceeded: " + peak.get() + " runs were executing at once");
        for (final var ok : succeeded) {
            assertNotNull(((org.techhouse.ops.resp.RunScriptResponse) ok).getResult());
        }
        assertEquals(2, admission.available(), "a permit leaked");
        assertEquals(rejected, admission.getRejected());
    }

    // The ordered shutdown stops accepting first, so no new run can acquire a permit; a permit still held
    // by an in-flight run must not hold the shutdown past its budget either.
    @Test
    public void test_shutdown_completes_while_a_permit_is_held() {
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try {
            final var budget = configuration.getShutdownTimeoutMs();
            final var start = System.currentTimeMillis();
            new ShutdownCoordinator().shutdown(new SocketServer(0), null);
            final var elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed <= budget, "shutdown took " + elapsed + "ms, past the " + budget + "ms budget");
        } finally {
            admission.release();
        }
        assertEquals(1, admission.available());
    }

    // The same burst, with a wait long enough to outlast the queue: the cap still bounds concurrency, but
    // nobody is turned away.
    @Test
    public void test_burst_is_absorbed_by_the_wait() throws Exception {
        admission.reconfigure(2, 5_000L);
        final var responses = burst();
        assertEquals(6, responses.size());
        for (final var response : responses) {
            assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        }
        assertEquals(0, admission.getRejected());
        assertTrue(admission.getWaited() > 0, "no caller queued, so the wait was never exercised");
        assertEquals(2, admission.available());
    }
}
