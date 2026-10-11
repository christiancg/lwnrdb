package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.techhouse.test.TriggerTestSupport.entry;
import static org.techhouse.test.TriggerTestSupport.pendingRun;
import static org.techhouse.test.TriggerTestSupport.statusOf;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class TriggerClusterRetryTest {
    private static final String DEFINER = "clusterretryowner";
    private static final Configuration configuration = Configuration.getInstance();

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private OwnershipManager realOwnership;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 3);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 3_600_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 60_000L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        if (realOwnership != null) {
            TestUtils.setPrivateField(coordinator, "ownershipManager", realOwnership);
            TestUtils.setPrivateField(router, "ownershipManager", realOwnership);
        }
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void loseQuorum() throws Exception {
        TriggerTestSupport.createDefiner(DEFINER);
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 1);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 86_400_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TriggerTestSupport.clearTriggersAndProcedures();
        TriggerTestSupport.consumeAllPendingRuns();
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.DB, "audit", "import db from 'db'; import args from 'args';"
                        + " db.save(db.name, '" + TestGlobals.COLL + "', { _id: 'written-by-' + args.id });"),
                DEFINER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(trigger()));
        realOwnership = TestUtils.getPrivateField(coordinator, "ownershipManager", OwnershipManager.class);
        final var withoutQuorum = mock(OwnershipManager.class);
        when(withoutQuorum.hasQuorum()).thenReturn(false);
        when(withoutQuorum.isOwner(any(), any())).thenReturn(true);
        TestUtils.setPrivateField(coordinator, "ownershipManager", withoutQuorum);
        TestUtils.setPrivateField(router, "ownershipManager", withoutQuorum);
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
    }

    private static TriggerDefinition trigger() {
        return new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER);
    }

    private static void saveDocument(String id) throws Exception {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        SaveOperationHelper.executeSave(request);
    }

    private static void saveDocumentWithValue(String id) throws Exception {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("A"));
        request.setObject(object);
        request.set_id(id);
        SaveOperationHelper.executeSave(request);
    }

    private static String recordedRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private static String pendingRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(),
                List.of(entry(id, "captured"))));
    }

    private static void dispatchLastAttempt(String id, String runId) {
        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry(id)), DEFINER, 0, runId, 1));
    }

    private static void dispatchLastAttemptFiredAt(String id, String runId, long firedAt) {
        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry(id)), DEFINER, 0, runId, 1, firedAt));
    }

    private static TriggerEvent eventFor(String id, String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "audit", false,
                List.of(entry(id, "captured")), DEFINER, 0, runId, 1);
    }

    private static void failBecauseTheClusterIsUnavailable(TriggerEvent event) throws Exception {
        final var method = TriggerDispatcher.class.getDeclaredMethod("handleFailure", TriggerEvent.class,
                TriggerDefinition.class, String.class, String.class, long.class, String.class, String.class, List.class,
                ScriptResult.class, boolean.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, event, trigger(), DEFINER, "run", System.currentTimeMillis(), "Error", "no quorum",
                List.of(), ScriptResult.error("Error", "no quorum"), true, true);
    }

    @Test
    public void test_a_write_refused_for_lack_of_quorum_does_not_consume_an_attempt() throws Exception {
        loseQuorum();
        saveDocument("waiting");
        final var runId = recordedRunFor("waiting");

        dispatchLastAttempt("waiting", runId);

        assertEquals(TriggerRunStatus.PENDING, statusOf(runId),
                "a node that cannot reach quorum has not failed the trigger, so its last attempt must survive");
    }

    @Test
    public void test_the_cluster_wait_is_bounded_by_the_run_retention() throws Exception {
        loseQuorum();
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 0L);
        saveDocument("stranded");
        final var runId = recordedRunFor("stranded");

        dispatchLastAttempt("stranded", runId);

        assertEquals(TriggerRunStatus.DEAD, statusOf(runId),
                "a node that permanently lost ownership must become actionable rather than retry forever");
    }

    @Test
    public void test_the_cluster_wait_expires_once_the_run_is_older_than_retention() throws Exception {
        loseQuorum();
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 1_000L);
        saveDocument("expired");
        final var runId = recordedRunFor("expired");

        dispatchLastAttemptFiredAt("expired", runId, System.currentTimeMillis() - 2_000L);

        assertEquals(TriggerRunStatus.DEAD, statusOf(runId),
                "a run older than the retention must stop waiting for the cluster even after earlier retries");
    }

    @Test
    public void test_a_run_younger_than_retention_keeps_waiting_for_the_cluster() throws Exception {
        loseQuorum();
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 60_000L);
        saveDocument("young");
        final var runId = recordedRunFor("young");

        dispatchLastAttemptFiredAt("young", runId, System.currentTimeMillis() - 2_000L);

        assertEquals(TriggerRunStatus.PENDING, statusOf(runId),
                "a run still inside the retention keeps waiting without consuming its last attempt");
    }

    @Test
    public void test_a_cluster_unavailable_retry_for_a_vanished_document_is_left_to_its_run() throws Exception {
        saveDocumentWithValue("vanished");
        final var runId = pendingRunFor("vanished");
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("vanished");
        DeleteOperationHelper.executeDelete(delete);

        failBecauseTheClusterIsUnavailable(eventFor("vanished", runId));

        final var run = pendingRun(runId);
        assertNotNull(run, "whether the document vanished is decided when the retry runs, not when it is scheduled");
        assertEquals(TriggerRunStatus.PENDING, run.getStatus());
    }

    @Test
    public void test_a_cluster_unavailable_retry_for_a_live_document_stays_pending_without_consuming_an_attempt()
            throws Exception {
        saveDocumentWithValue("alive");
        final var runId = pendingRunFor("alive");

        failBecauseTheClusterIsUnavailable(eventFor("alive", runId));

        final var run = pendingRun(runId);
        assertNotNull(run);
        assertEquals(TriggerRunStatus.PENDING, run.getStatus());
        assertEquals(1, run.getAttempts());
    }

    @Test
    public void test_an_unreadable_procedure_leaves_the_run_pending_for_an_in_process_retry() throws Exception {
        saveDocumentWithValue("unreadable");
        final var runId = pendingRunFor("unreadable");
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(trigger()));
        final var folder = new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + ".procedures");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        Files.writeString(new File(folder, "audit.json").toPath(), "{not json");
        cache.removeProceduresForDatabase(TestGlobals.DB);

        assertDoesNotThrow(() -> TriggerDispatcher.dispatch(eventFor("unreadable", runId)));

        final var run = pendingRun(runId);
        assertNotNull(run);
        assertEquals(TriggerRunStatus.PENDING, run.getStatus());
    }

    @Test
    public void test_a_cluster_retry_keeps_the_original_fired_at() throws Exception {
        saveDocumentWithValue("carried");
        final var firedAt = System.currentTimeMillis() - 5_000L;
        final var original = new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "audit",
                false, List.of(entry("carried", "captured")), DEFINER, 0, "run", 1, firedAt);

        final var waiting = original.retry(original.getAttempt());

        assertNotNull(waiting);
        assertEquals(firedAt, waiting.getFiredAt(), "waiting for the cluster must not restart the retention window");
    }

    @Test
    public void test_an_attempt_retry_keeps_the_original_fired_at() throws Exception {
        saveDocumentWithValue("attempted");
        final var firedAt = System.currentTimeMillis() - 5_000L;
        final var original = new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "audit",
                false, List.of(entry("attempted", "captured")), DEFINER, 0, "run", 1, firedAt);

        final var retry = original.retry(2);

        assertNotNull(retry);
        assertEquals(2, retry.getAttempt());
        assertEquals(firedAt, retry.getFiredAt(), "a retried run must show its script the time it originally fired");
    }
}
