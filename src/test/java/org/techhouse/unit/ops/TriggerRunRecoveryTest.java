package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * Startup recovery of pending runs: what is re-queued, what is discarded, and what is only warned about.
 */
public class TriggerRunRecoveryTest {
    private static final Configuration configuration = Configuration.getInstance();

    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
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
        triggerExecutor.start(captured::add);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private static void sleep() {
        try {
            Thread.sleep((long) 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.addProperty("value", 1L);
        return object;
    }

    private static void writeRecord(String runId, String nodeId, EventType type, List<String> ids,
            List<JsonObject> documents, long firedAt) throws Exception {
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(runId, 0L, nodeId, TestGlobals.DB,
                TestGlobals.COLL, "audit", "recalc", type, false, "alice", 0, firedAt, ids, documents));
    }

    private void saveDocument() {
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document("live"));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        final var request = new org.techhouse.ops.req.SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("live"));
        request.set_id("live");
        IocContainer.get(org.techhouse.ops.OperationProcessor.class).processMessage(request);
    }

    @Test
    public void test_pending_run_is_resubmitted_at_startup() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        writeRecord("run-a", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal();
        sleep();

        assertEquals(1, captured.size());
        assertEquals("run-a", captured.getFirst().getRunId());
        assertEquals(1, captured.getFirst().getEntries().size());
    }

    // Only this node's own records are replayed: another node's pending runs are its to recover.
    @Test
    public void test_run_from_another_node_is_not_replayed_locally() throws Exception {
        writeRecord("run-b", "some-other-node", EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal();
        sleep();

        assertTrue(captured.isEmpty());
        assertEquals(1, TriggerRunLog.pending().size(), "the other node's record must be left alone");
    }

    // A DELETED run carries its documents, because they cannot be re-read from the collection.
    @Test
    public void test_deleted_event_replays_the_stored_document() throws Exception {
        writeRecord("run-c", TriggerRunLog.currentNodeId(), EventType.DELETED, List.of(), List.of(document("removed")),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal();
        sleep();

        assertEquals(1, captured.size());
        assertEquals(EventType.DELETED, captured.getFirst().getType());
        assertEquals("removed", captured.getFirst().getEntries().getFirst().get_id());
    }

    // The documents a run was about are gone, so there is nothing to re-run: the record is consumed rather
    // than left to be replayed at every restart.
    @Test
    public void test_a_run_whose_documents_vanished_is_consumed() throws Exception {
        writeRecord("run-d", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("no-such-doc"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal();
        sleep();

        assertTrue(captured.isEmpty());
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_recovery_is_skipped_when_triggers_are_disabled() throws Exception {
        writeRecord("run-e", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);

        TriggerRunRecovery.recoverLocal();
        sleep();

        assertTrue(captured.isEmpty());
        assertEquals(1, TriggerRunLog.pending().size());
    }

    @Test
    public void test_recovery_is_skipped_when_the_run_log_is_disabled() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);

        assertDoesNotThrow(TriggerRunRecovery::recoverLocal);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void test_warns_about_a_long_pending_run() throws Exception {
        writeRecord("run-f", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), 1L);

        assertDoesNotThrow(TriggerRunRecovery::warnAboutStrandedRuns);
        assertEquals(1, TriggerRunLog.pending().size(), "warning must not consume the record");
    }

    @Test
    public void test_warning_is_skipped_when_triggers_are_disabled() throws Exception {
        writeRecord("run-g", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), 1L);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);

        assertDoesNotThrow(TriggerRunRecovery::warnAboutStrandedRuns);
    }

    @Test
    public void test_garbage_collect_uses_the_configured_retention() throws Exception {
        writeRecord("run-h", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), 1L);
        assertNotNull(TriggerRunLog.pending());

        TriggerRunRecovery.garbageCollect();

        assertTrue(TriggerRunLog.pending().isEmpty(), "a record older than triggerRunRetentionMs is collected");
    }
}
