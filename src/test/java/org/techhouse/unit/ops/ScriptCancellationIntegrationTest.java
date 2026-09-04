package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScheduleDispatcher;
import org.techhouse.ops.ScriptLoad;
import org.techhouse.ops.ScriptOperationHelper;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CallProcedureRequest;
import org.techhouse.ops.req.CancelScriptRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.ListScriptsRequest;
import org.techhouse.ops.req.RunScriptRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.resp.CancelScriptResponse;
import org.techhouse.ops.resp.ListScriptsResponse;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The whole feature on the real setup: a run appears in LIST_SCRIPTS, CANCEL_SCRIPT stops it, and the
 * cancelled caller learns why.
 */
public class ScriptCancellationIntegrationTest {
    private static final String ADMIN = "cancelclient";
    private static final String OUTPUT_COLL = "cancelOutput";
    // Long enough that the run is still going when the test finds and cancels it.
    private static final String SLOW_SCRIPT = "export default new Promise(r => setTimeout(() => r(1), 20000));";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
    private static final ScriptLoad scriptLoad = IocContainer.get(ScriptLoad.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry schedules = IocContainer.get(ScheduleRegistry.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, OUTPUT_COLL));
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(ADMIN));
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
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        // Generous, so a cancellation is unmistakably a cancellation and not a timeout
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 60_000L);
        TestUtils.setPrivateField(configuration, "scheduleTimeoutMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLines", 1_000);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLineChars", 4_096);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
        TestUtils.setPrivateField(configuration, "scriptTextImportEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listScheduleNames(TestGlobals.DB)) {
            fs.deleteSchedule(TestGlobals.DB, name);
        }
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeSchedulesForDatabase(TestGlobals.DB);
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
        schedules.clear();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        registry.list().forEach(run -> registry.unregister(run.runId()));
    }

    private List<JsonObject> listScripts() {
        final var response = processor.processMessage(new ListScriptsRequest());
        assertInstanceOf(ListScriptsResponse.class, response);
        return ((ListScriptsResponse) response).getScripts();
    }

    private CancelScriptResponse cancel(String runId) {
        final var request = new CancelScriptRequest();
        request.setRunId(runId);
        final var response = processor.processMessage(request);
        assertInstanceOf(CancelScriptResponse.class, response);
        return (CancelScriptResponse) response;
    }

    // The single polling site. A run registers itself on another thread and the registry offers no
    // notification hook, so polling is the only way to wait for one; how long it takes to appear is a
    // scheduling detail rather than part of the contract.
    @SuppressWarnings("BusyWait")
    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    // Waits for the listing to report a run, so the test never races the run's own start-up.
    private JsonObject awaitOneScript() throws Exception {
        if (!await(() -> !listScripts().isEmpty())) {
            throw new AssertionError("the run never appeared in LIST_SCRIPTS");
        }
        return listScripts().getFirst();
    }

    private void awaitEmptyListing() throws Exception {
        assertTrue(await(() -> listScripts().isEmpty()), "the run stayed registered after it ended");
    }

    private static String stringField(JsonObject row, String field) {
        return row.get(field).asJsonString().getValue();
    }

    @Test
    public void test_cancels_running_script() throws Exception {
        final var result = new AtomicReference<RunScriptResponse>();
        final var runner = Thread.ofVirtual().start(() -> result.set((RunScriptResponse) ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, SLOW_SCRIPT, null), ADMIN, null)));
        final var row = awaitOneScript();
        assertEquals("RUN_SCRIPT", stringField(row, "kind"));
        assertEquals(TestGlobals.DB, stringField(row, "database"));
        assertEquals(ADMIN, stringField(row, "username"));
        assertTrue(row.get("ageMs").asJsonNumber().getValue().longValue() >= 0);

        final var runId = stringField(row, "runId");
        final var cancelled = cancel(runId);
        assertEquals(OperationStatus.OK, cancelled.getStatus());
        assertTrue(cancelled.isCancelled());

        runner.join(30_000L);
        assertFalse(runner.isAlive(), "the cancelled run never returned");
        assertEquals(ErrorCode.SCRIPT_CANCELLED.getCode(), result.get().getErrorCode());
        assertEquals(runId, result.get().getRunId());
        awaitEmptyListing();
    }

    @Test
    public void test_list_shows_kind_and_name_for_procedure() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "slow", SLOW_SCRIPT), ADMIN);
        final var result = new AtomicReference<org.techhouse.ops.resp.OperationResponse>();
        final var runner = Thread.ofVirtual().start(
                () -> result.set(processor.processMessage(new CallProcedureRequest(TestGlobals.DB, "slow", null))));
        final var row = awaitOneScript();
        assertEquals("CALL_PROCEDURE", stringField(row, "kind"));
        assertEquals("slow", stringField(row, "name"));

        assertTrue(cancel(stringField(row, "runId")).isCancelled());
        runner.join(30_000L);
        assertEquals(ErrorCode.SCRIPT_CANCELLED.getCode(), result.get().getErrorCode());
        awaitEmptyListing();
    }

    @Test
    public void test_list_shows_trigger_run() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "slow", SLOW_SCRIPT), ADMIN);
        installTrigger();
        final var done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                TriggerDispatcher.dispatch(triggerEvent("t1", null));
            } finally {
                done.countDown();
            }
        });
        final var row = awaitOneScript();
        assertEquals("TRIGGER", stringField(row, "kind"));
        assertEquals("audit", stringField(row, "name"));
        assertEquals(ADMIN, stringField(row, "username"));

        assertTrue(cancel(stringField(row, "runId")).isCancelled());
        assertTrue(done.await(30, TimeUnit.SECONDS), "the cancelled trigger run never returned");
        awaitEmptyListing();
    }

    @Test
    public void test_list_shows_scheduled_run() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "slow", SLOW_SCRIPT), ADMIN);
        final var entry = registerSchedule();
        final var done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                ScheduleDispatcher.dispatch(entry);
            } finally {
                done.countDown();
            }
        });
        final var row = awaitOneScript();
        assertEquals("SCHEDULE", stringField(row, "kind"));
        assertEquals("s", stringField(row, "name"));

        assertTrue(cancel(stringField(row, "runId")).isCancelled());
        assertTrue(done.await(30, TimeUnit.SECONDS), "the cancelled scheduled run never returned");
        awaitEmptyListing();
    }

    // The one place the exactly-once guarantee is deliberately waived: an operator cancelling a runaway
    // trigger wants it stopped, not retried, so the pending record is consumed rather than replayed.
    @Test
    public void test_cancelled_trigger_run_is_not_replayed() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "slow", SLOW_SCRIPT), ADMIN);
        installTrigger();
        final var event = triggerEvent("t2", null);
        final var runId = TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(event.getDbName(),
                event.getCollName(), event.getTriggerName(), event.getProcedureName(), event.getType(),
                event.isBatchMode(), event.getActingUser(), event.getDepth(), event.getFiredAt(), event.getEntries()));
        assertNotNull(runId);
        assertFalse(TriggerRunLog.pending().isEmpty());

        final var done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                TriggerDispatcher.dispatch(triggerEvent("t2", runId));
            } finally {
                done.countDown();
            }
        });
        final var row = awaitOneScript();
        assertTrue(cancel(stringField(row, "runId")).isCancelled());
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertTrue(TriggerRunLog.pending().isEmpty(), "the cancelled run was left pending and would be replayed");
        final var captured = new java.util.concurrent.CopyOnWriteArrayList<TriggerEvent>();
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
        try {
            TriggerRunRecovery.recoverLocal();
            Thread.sleep(200);
            assertTrue(captured.isEmpty(), "the cancelled trigger run was re-queued at startup");
        } finally {
            triggerExecutor.stop();
        }
    }

    @Test
    public void test_run_id_is_returned_on_the_response() {
        final var response = (RunScriptResponse) ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, "return 1;", null), ADMIN, null);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        assertNotNull(response.getRunId());
        assertEquals(java.util.UUID.fromString(response.getRunId()).toString(), response.getRunId());
    }

    // The log line is the operator's other way of learning a run's id, so it has to carry it
    @Test
    public void test_run_id_appears_in_the_log_line() throws Exception {
        final var origLogPath = configuration.getLogPath();
        TestUtils.setPrivateField(configuration, "logPath", TestGlobals.LOG_PATH);
        final var logDir = new java.io.File(TestGlobals.LOG_PATH);
        try {
            assertTrue(logDir.exists() || logDir.mkdir());
            final var logFile = new java.io.File(logDir,
                    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE) + ".log");
            java.nio.file.Files.deleteIfExists(logFile.toPath());
            final var response = (RunScriptResponse) ScriptOperationHelper
                    .execute(new RunScriptRequest(TestGlobals.DB, "return 1;", null), ADMIN, null);
            assertTrue(logFile.exists(), "nothing was logged for the run");
            assertTrue(java.nio.file.Files.readString(logFile.toPath()).contains("runId=" + response.getRunId()),
                    "the log line does not name the run");
            java.nio.file.Files.deleteIfExists(logFile.toPath());
        } finally {
            TestUtils.setPrivateField(configuration, "logPath", origLogPath);
            java.nio.file.Files.deleteIfExists(logDir.toPath());
        }
    }

    @Test
    public void test_cancel_unknown_run_id_is_ok_and_false() {
        final var response = cancel(java.util.UUID.randomUUID().toString());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(response.isCancelled());
        assertNull(response.getErrorCode());
    }

    // Every outcome must unregister, or the placement signal drifts upwards for the node's lifetime
    @Test
    public void test_registry_empty_after_every_outcome() throws Exception {
        assertEquals(0, scriptLoad.current());
        assertEquals(OperationStatus.OK, ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, "return 1;", null), ADMIN, null).getStatus());
        assertEquals(0, scriptLoad.current());

        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(),
                ScriptOperationHelper
                        .execute(new RunScriptRequest(TestGlobals.DB, "throw new Error('nope');", null), ADMIN, null)
                        .getErrorCode());
        assertEquals(0, scriptLoad.current());

        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 50L);
        assertEquals(ErrorCode.SCRIPT_TIMEOUT.getCode(), ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, "while (true) { }", null), ADMIN, null).getErrorCode());
        assertEquals(0, scriptLoad.current());

        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 60_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 1_000L);
        assertEquals(ErrorCode.SCRIPT_LIMIT_EXCEEDED.getCode(), ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, "while (true) { }", null), ADMIN, null).getErrorCode());
        assertEquals(0, scriptLoad.current());

        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        final var result = new AtomicReference<RunScriptResponse>();
        final var runner = Thread.ofVirtual().start(() -> result.set((RunScriptResponse) ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, SLOW_SCRIPT, null), ADMIN, null)));
        assertTrue(cancel(stringField(awaitOneScript(), "runId")).isCancelled());
        runner.join(30_000L);
        assertEquals(ErrorCode.SCRIPT_CANCELLED.getCode(), result.get().getErrorCode());
        awaitEmptyListing();
        assertEquals(0, scriptLoad.current());
    }

    // Cancelling a run that has already finished is not an error: it is the state the caller wanted
    @Test
    public void test_cancelling_a_finished_run_returns_false() {
        final var response = (RunScriptResponse) ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, "return 1;", null), ADMIN, null);
        assertFalse(cancel(response.getRunId()).isCancelled());
    }

    // A cancelled db.transaction must roll back and strand no collection lock
    @Test
    public void test_cancelling_a_transactional_script_rolls_back_and_strands_no_lock() throws Exception {
        final var script = "import db from 'db';\n" + "db.transaction(() => {\n" + "  db.save(db.name, '" + OUTPUT_COLL
                + "', { _id: 'tx-cancelled', ok: true });\n" + "  while (true) { }\n" + "});\n";
        final var result = new AtomicReference<RunScriptResponse>();
        final var runner = Thread.ofVirtual().start(() -> result.set((RunScriptResponse) ScriptOperationHelper
                .execute(new RunScriptRequest(TestGlobals.DB, script, null), ADMIN, null)));
        assertTrue(cancel(stringField(awaitOneScript(), "runId")).isCancelled());
        runner.join(30_000L);
        assertEquals(ErrorCode.SCRIPT_CANCELLED.getCode(), result.get().getErrorCode());
        assertNull(outputRow(), "the cancelled transaction was not rolled back");
        assertFalse(anyLockHeld(), "the cancelled transaction stranded a collection lock");
    }

    private static boolean anyLockHeld() throws Exception {
        final var locker = IocContainer.get(org.techhouse.concurrency.ResourceLocking.class);
        final var locksType = new org.techhouse.utils.ReflectionUtils.TypeToken<java.util.Map<String, java.util.concurrent.locks.ReentrantReadWriteLock>>() {
        };
        for (final var lock : TestUtils.getPrivateField(locker, "locks", locksType).values()) {
            if (lock.isWriteLocked() || lock.getReadLockCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private JsonObject outputRow() {
        final var request = new org.techhouse.ops.req.FindByIdRequest(TestGlobals.DB, OUTPUT_COLL);
        request.set_id("tx-cancelled");
        final var response = processor.processMessage(request);
        return response instanceof org.techhouse.ops.resp.FindByIdResponse found ? found.getObject() : null;
    }

    private void installTrigger() {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "slow",
                        TriggerDefinition.MODE_DOCUMENT, false, true, ADMIN, 1L, 1L, 1L, ADMIN)));
    }

    private static TriggerEvent triggerEvent(String id, String runId) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "slow", false,
                List.of(dbEntry), ADMIN, 0, runId);
    }

    private ScheduleRegistry.Entry registerSchedule() throws Exception {
        final var definition = new ScheduleDefinition("s", "slow", null, 60_000L, null, 0L, true, ADMIN, null, 1L, 1L,
                1L, ADMIN);
        fs.writeSchedule(TestGlobals.DB, "s", eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, "s");
        schedules.reload(TestGlobals.DB);
        return schedules.get(TestGlobals.DB, "s");
    }
}
