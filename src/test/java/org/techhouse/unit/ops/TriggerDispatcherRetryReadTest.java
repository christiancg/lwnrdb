package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
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
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerDispatcherRetryReadTest {
    private static final String OWNER = "retryreadowner";
    private static final int MAX_ATTEMPTS = 3;
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", MAX_ATTEMPTS);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 3_600_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 60_000L);
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(OWNER));
        createOwner();
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "audit", "return 'ok';"), OWNER);
        installTrigger();
    }

    private void installTrigger() {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.UPDATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, false, true, OWNER, 1L, 1L, 1L, OWNER)));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void createOwner() {
        final var request = new CreateUserRequest();
        request.setUsername(OWNER);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private static void saveDocument(String id, String value) throws Exception {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString(value));
        request.setObject(object);
        request.set_id(id);
        SaveOperationHelper.executeSave(request);
    }

    private static void deleteTheGoneDocument() throws Exception {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("gone");
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

    private static String pendingRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.UPDATED, false, OWNER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private static TriggerEvent firstAttempt(String id, String runId, EventType type) {
        return new TriggerEvent(type, TestGlobals.DB, TestGlobals.COLL, "audit", "audit", false, List.of(entry(id)),
                OWNER, 0, runId, 1);
    }

    private static AdminTriggerRunEntry runNamed(String runId) throws Exception {
        for (final var run : TriggerRunLog.pending()) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        return null;
    }

    private static TriggerEvent withCurrentEntries(TriggerEvent event) throws Exception {
        final var method = TriggerDispatcher.class.getDeclaredMethod("withCurrentEntries", TriggerEvent.class);
        method.setAccessible(true);
        return (TriggerEvent) method.invoke(null, event);
    }

    private void makeThePagesUnreadable() throws Exception {
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        final var folder = new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + TestGlobals.COLL);
        final var pages = folder.listFiles((_, name) -> name.endsWith(".dat"));
        assertNotNull(pages);
        assertTrue(pages.length > 0, "the fixture needs a page file to break");
        for (final var page : pages) {
            Files.delete(page.toPath());
            assertTrue(page.mkdir(), "a directory in place of the page makes its read fail with an IOException");
        }
        installTrigger();
    }

    @Test
    public void test_a_retry_whose_document_read_fails_stays_pending_instead_of_being_consumed() throws Exception {
        saveDocument("unreadable", "A");
        final var runId = pendingRunFor("unreadable");
        makeThePagesUnreadable();

        TriggerDispatcher.dispatch(firstAttempt("unreadable", runId, EventType.UPDATED).retry(2));

        final var run = runNamed(runId);
        assertNotNull(run, "a read failure is not a vanished document, so the durable run must survive it");
        assertEquals(TriggerRunStatus.PENDING, run.getStatus());
        assertEquals(2, run.getAttempts());
        assertTrue(run.getLastError().startsWith("IOException"), run.getLastError());
        assertTrue(run.getNextAttemptAt() > System.currentTimeMillis(), "the retry waits out its backoff");
    }

    @Test
    public void test_a_retry_whose_document_read_keeps_failing_is_dead_lettered() throws Exception {
        saveDocument("stillunreadable", "A");
        final var runId = pendingRunFor("stillunreadable");
        makeThePagesUnreadable();

        TriggerDispatcher.dispatch(firstAttempt("stillunreadable", runId, EventType.UPDATED).retry(MAX_ATTEMPTS));

        final var run = runNamed(runId);
        assertNotNull(run);
        assertEquals(TriggerRunStatus.DEAD, run.getStatus());
    }

    @Test
    public void test_a_retry_whose_document_is_gone_is_consumed_when_it_runs() throws Exception {
        saveDocument("gone", "A");
        final var runId = pendingRunFor("gone");
        deleteTheGoneDocument();

        TriggerDispatcher.dispatch(firstAttempt("gone", runId, EventType.UPDATED).retry(2));

        assertNull(runNamed(runId), "a retry for a document that no longer exists is consumed, not replayed");
    }

    @Test
    public void test_a_retry_reads_the_document_when_it_runs_not_when_it_was_scheduled() throws Exception {
        saveDocument("moving", "A");
        final var scheduled = firstAttempt("moving", "moving-run", EventType.UPDATED).retry(2);
        saveDocument("moving", "B");

        final var current = withCurrentEntries(scheduled);

        assertNotNull(current);
        assertEquals("B", current.getEntries().getFirst().getData().get("value").asJsonString().getValue());
        assertEquals(2, current.getAttempt());
        assertFalse(current.hasStaleEntries());
    }

    @Test
    public void test_a_first_attempt_is_not_read_again() throws Exception {
        final var first = firstAttempt("never-read", "first-run", EventType.UPDATED);

        assertSame(first, withCurrentEntries(first));
    }

    @Test
    public void test_a_deleted_retry_keeps_its_captured_snapshot() {
        final var retry = firstAttempt("deleted", "deleted-run", EventType.DELETED).retry(2);

        assertFalse(retry.hasStaleEntries());
        assertEquals("captured", retry.getEntries().getFirst().getData().get("value").asJsonString().getValue());
    }

    @Test
    public void test_a_created_or_updated_retry_is_marked_for_a_fresh_read() {
        assertTrue(firstAttempt("created", "created-run", EventType.CREATED).retry(2).hasStaleEntries());
        assertTrue(firstAttempt("updated", "updated-run", EventType.UPDATED).retry(2).hasStaleEntries());
    }
}
