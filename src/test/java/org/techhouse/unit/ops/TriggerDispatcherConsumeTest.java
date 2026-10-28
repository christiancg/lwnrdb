package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.TriggerTestSupport.entry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class TriggerDispatcherConsumeTest {
    private static final String OWNER = "consumeowner";
    private static final String TRANSACTIONS_LOG = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME + Globals.WAL_EXTENSION;
    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 60_000L);
        TriggerTestSupport.createDefiner(OWNER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, false, true, OWNER, 1L, 1L, 1L, OWNER)));
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TriggerTestSupport.consumeAllPendingRuns();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void procedure(String source) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit", source), OWNER);
    }

    private static String pendingRun(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.CREATED, false, OWNER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private static TriggerEvent fired(String id, String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "audit", false,
                List.of(entry(id)), OWNER, 0, runId, 1);
    }

    private static long transactionLogForces(RecordingForcer recorder) {
        return recorder.forcedNames().stream().filter(TRANSACTIONS_LOG::equals).count();
    }

    @Test
    public void test_a_trigger_that_wrote_nothing_consumes_its_run_with_a_plain_delete() throws Exception {
        procedure("return 'read only';");
        final var runId = pendingRun("read-only");

        try (var recorder = RecordingForcer.install(true)) {
            TriggerDispatcher.dispatch(fired("read-only", runId));

            assertEquals(0, transactionLogForces(recorder), "no transaction bookkeeping for an empty body");
        }

        assertTrue(TriggerRunLog.recordIdsFor(runId).isEmpty());
    }

    @Test
    public void test_a_trigger_that_wrote_consumes_its_run_inside_its_transaction() throws Exception {
        procedure("import db from 'db'; db.save(db.name, '" + TestGlobals.COLL
                + "', { _id: 'written-by-trigger' }); return 'ok';");
        final var runId = pendingRun("writer");

        try (var recorder = RecordingForcer.install(true)) {
            TriggerDispatcher.dispatch(fired("writer", runId));

            assertTrue(transactionLogForces(recorder) > 0, "the consume rides the commit's transaction records");
        }

        assertTrue(TriggerRunLog.recordIdsFor(runId).isEmpty());
        assertTrue(cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL).containsKey("written-by-trigger"));
    }

    @Test
    public void test_a_failed_plain_delete_leaves_the_run_for_a_retry() throws Exception {
        procedure("return 'read only';");
        final var runId = pendingRun("delete-fails");

        try (var recorder = RecordingForcer.install(true)) {
            recorder.failingFor(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION);
            TriggerDispatcher.dispatch(fired("delete-fails", runId));
        }

        assertEquals(1, TriggerRunLog.recordIdsFor(runId).size());
        assertEquals(TriggerRunStatus.PENDING, TriggerTestSupport.statusOf(runId));
    }
}
