package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The exactly-once guarantee: a trigger's effects and the consumption of its pending-run record commit
 * together, so a replay after a crash cannot apply a non-idempotent trigger twice.
 */
public class TriggerExactlyOnceTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final String COUNTER_COLL = "counters";
    // The definer owns the database, so the trigger's own writes are authorized wherever they land.
    private static final String DEFINER = "trigger-owner";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, COUNTER_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, COUNTER_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, COUNTER_COLL));
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(DEFINER));
        final var request = new CreateUserRequest();
        request.setUsername(DEFINER);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        captured.clear();
        triggerExecutor.stop();
        // Captures the queued events instead of running them, so a test can inspect the durable record while
        // the run is still pending and then drive the dispatcher itself.
        triggerExecutor.start(captured::add);
        drainPendingRuns();
    }

    private void drainPendingRuns() throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    // Both CREATED and UPDATED: a save that carries an _id is an upsert, which the write path reports as an
    // update, so a CREATED-only trigger would never fire for the saves these tests make.
    private void installTrigger(String procedureName) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit",
                        new LinkedHashSet<>(Set.of(EventType.CREATED, EventType.UPDATED)), procedureName,
                        TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER)));
    }

    private void installProcedure(String name, String source) throws Exception {
        final var definition = new ProcedureDefinition(name, source, 1L, null, true, 1L, 1L, DEFINER);
        fs.writeProcedure(TestGlobals.DB, name, eJson.toJson(definition.toJsonObject()));
        cache.putProcedure(TestGlobals.DB, definition);
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.addProperty("value", 1L);
        return object;
    }

    private void save(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        processor.processMessage(request);
    }

    private TriggerEvent settleOne() {
        for (var i = 0; i < 200 && captured.isEmpty(); i++) {
            sleep(5);
        }
        return captured.isEmpty() ? null : captured.getFirst();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long counterValue() throws Exception {
        final var entries = cache.getEntriesByIds(TestGlobals.DB, COUNTER_COLL, Set.of("total"));
        if (entries.isEmpty()) {
            return 0L;
        }
        return entries.getFirst().getData().get("count").asJsonNumber().asInteger().longValue();
    }

    @Test
    public void test_a_fired_trigger_leaves_a_durable_record() {
        installTrigger("noop");
        save("d1");

        final var event = settleOne();
        assertNotNull(event, "the trigger should have been queued");
        assertNotNull(event.getRunId(), "a queued trigger should carry the id of its durable record");
        assertFalse(TriggerRunLog.recordIdsFor(event.getRunId()).isEmpty(),
                "the pending run should still be on disk while the run has not applied");
    }

    // The inventory case. A trigger that increments a counter is run, then replayed the way recovery would
    // after a crash. The counter must end at one.
    @Test
    public void test_counter_trigger_applied_once_across_crash_replay() throws Exception {
        installProcedure("increment", """
                import db from "db";
                const existing = db.findById(db.name, "counters", "total");
                const next = existing === null ? 1 : existing.count + 1;
                db.save(db.name, "counters", { _id: "total", count: next });
                """);
        installTrigger("increment");
        save("d2");

        final var event = settleOne();
        assertNotNull(event);
        final var runId = event.getRunId();
        assertNotNull(runId);

        TriggerDispatcher.dispatch(event);
        assertEquals(1L, counterValue());
        assertTrue(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                "a run that applied must leave no record behind to replay");

        // Recovery only re-queues what is still pending, so the applied run is not replayed at all.
        captured.clear();
        TriggerRunRecovery.recoverLocal();
        sleep(50);
        assertTrue(captured.isEmpty(), "an applied run must not be re-queued at startup");
        assertEquals(1L, counterValue(), "the counter must not advance twice");
    }

    // The other half of the guarantee: a run whose effects never committed is still pending and does replay.
    @Test
    public void test_a_run_that_never_applied_is_replayed() throws Exception {
        installProcedure("noop2", "export default 1;");
        installTrigger("noop2");
        save("d3");

        final var event = settleOne();
        assertNotNull(event);
        assertFalse(TriggerRunLog.recordIdsFor(event.getRunId()).isEmpty());

        captured.clear();
        TriggerRunRecovery.recoverLocal();
        sleep(50);
        assertEquals(1, captured.size(), "a pending run must be re-queued at startup");
        assertEquals(event.getRunId(), captured.getFirst().getRunId());
    }

    // A deterministically failing script must be terminal, not replayed at every restart forever.
    @Test
    public void test_script_error_consumes_the_record_without_effects() throws Exception {
        installProcedure("boom", """
                import db from "db";
                db.save(db.name, "counters", { _id: "total", count: 99 });
                throw new Error("nope");
                """);
        installTrigger("boom");
        save("d4");

        final var event = settleOne();
        assertNotNull(event);
        TriggerDispatcher.dispatch(event);

        assertTrue(TriggerRunLog.recordIdsFor(event.getRunId()).isEmpty(),
                "a failed run must consume its record rather than replay forever");
        assertEquals(0L, counterValue(), "a failed run's writes must roll back");
    }

    @Test
    public void test_a_missing_procedure_consumes_the_record() {
        installTrigger("absent");
        save("d5");

        final var event = settleOne();
        assertNotNull(event);
        TriggerDispatcher.dispatch(event);

        assertTrue(TriggerRunLog.recordIdsFor(event.getRunId()).isEmpty());
    }

    @Test
    public void test_depth_beyond_the_max_consumes_the_record() throws Exception {
        installProcedure("noop3", "export default 1;");
        installTrigger("noop3");
        save("d6");
        final var queued = settleOne();
        assertNotNull(queued);

        final var deep = new TriggerEvent(EventType.UPDATED, TestGlobals.DB, TestGlobals.COLL, "audit", "noop3", false,
                List.of(), DEFINER, configuration.getTriggerMaxDepth(), queued.getRunId());
        TriggerDispatcher.dispatch(deep);

        assertTrue(TriggerRunLog.recordIdsFor(queued.getRunId()).isEmpty());
    }

    @Test
    public void test_disabled_run_log_records_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
        installTrigger("noop");
        save("d7");

        final var event = settleOne();
        assertNotNull(event);
        assertNull(event.getRunId(), "with the log off a run carries no record id");
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_garbage_collect_drops_records_past_retention() throws Exception {
        installTrigger("noop");
        save("d8");
        assertNotNull(settleOne());
        assertFalse(TriggerRunLog.pending().isEmpty());

        TriggerRunLog.garbageCollect(-1L);

        assertTrue(TriggerRunLog.pending().isEmpty(), "records older than the retention window are dropped");
    }

    @Test
    public void test_recovery_is_skipped_when_triggers_are_disabled() throws Exception {
        installTrigger("noop");
        save("d9");
        assertNotNull(settleOne());
        captured.clear();

        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TriggerRunRecovery.recoverLocal();
        sleep(50);

        assertTrue(captured.isEmpty());
    }
}
