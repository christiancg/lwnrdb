package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerDispatcherClusterRetryTest {
    private static final String OWNER = "clusterretryowner";
    private static final Configuration configuration = Configuration.getInstance();

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
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveDocument(String id) throws Exception {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("A"));
        request.setObject(object);
        request.set_id(id);
        SaveOperationHelper.executeSave(request);
    }

    private static void deleteDocument() throws Exception {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("vanished");
        DeleteOperationHelper.executeDelete(request);
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        data.add("value", new JsonString("captured"));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static TriggerDefinition trigger() {
        return new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                TriggerDefinition.MODE_DOCUMENT, false, true, OWNER, 1L, 1L, 1L, OWNER);
    }

    private static String pendingRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.CREATED, false, OWNER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private static void failBecauseTheClusterIsUnavailable(TriggerEvent event) throws Exception {
        final var method = TriggerDispatcher.class.getDeclaredMethod("handleFailure", TriggerEvent.class,
                TriggerDefinition.class, String.class, String.class, long.class, String.class, String.class, List.class,
                ScriptResult.class, boolean.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, event, trigger(), OWNER, "run", System.currentTimeMillis(), "Error", "no quorum", List.of(),
                ScriptResult.error("Error", "no quorum"), true, true);
    }

    private static AdminTriggerRunEntry runNamed(String runId) throws Exception {
        for (final var run : TriggerRunLog.pending()) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        return null;
    }

    private static TriggerEvent eventFor(String id, String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "audit", false,
                List.of(entry(id)), OWNER, 0, runId, 1);
    }

    @Test
    public void test_a_cluster_unavailable_retry_for_a_vanished_document_is_consumed() throws Exception {
        saveDocument("vanished");
        final var runId = pendingRunFor("vanished");
        deleteDocument();

        failBecauseTheClusterIsUnavailable(eventFor("vanished", runId));

        assertNull(runNamed(runId), "a retry for a document that no longer exists must not stay pending");
    }

    @Test
    public void test_a_cluster_unavailable_retry_for_a_live_document_stays_pending_without_consuming_an_attempt()
            throws Exception {
        saveDocument("alive");
        final var runId = pendingRunFor("alive");

        failBecauseTheClusterIsUnavailable(eventFor("alive", runId));

        final var run = runNamed(runId);
        assertNotNull(run);
        assertEquals(TriggerRunStatus.PENDING, run.getStatus());
        assertEquals(1, run.getAttempts());
    }

    @Test
    public void test_an_unreadable_procedure_leaves_the_run_pending_for_an_in_process_retry() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        final var fs = IocContainer.get(FileSystem.class);
        saveDocument("unreadable");
        final var runId = pendingRunFor("unreadable");
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(trigger()));
        final var folder = new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + ".procedures");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        Files.writeString(new File(folder, "audit.json").toPath(), "{not json");
        cache.removeProceduresForDatabase(TestGlobals.DB);

        assertDoesNotThrow(() -> TriggerDispatcher.dispatch(eventFor("unreadable", runId)));

        final var run = runNamed(runId);
        assertNotNull(run);
        assertEquals(TriggerRunStatus.PENDING, run.getStatus());
    }
}
