package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerHelperTest {
    private static final String SENTINEL = "__capture-sentinel__";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerThreads", 2);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        // One worker keeps the queue strictly FIFO, which is what makes the sentinel in capture() conclusive
        TestUtils.setPrivateField(configuration, "triggerThreads", 1);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        triggerExecutor.stop();
    }

    // Collects the submitted events instead of running them, so the emit decision is what is under test
    private List<TriggerEvent> capture(Runnable emit) {
        final var captured = new CopyOnWriteArrayList<TriggerEvent>();
        final var sentinelSeen = new CountDownLatch(1);
        triggerExecutor.start(triggerEvent -> {
            if (SENTINEL.equals(triggerEvent.getTriggerName())) {
                sentinelSeen.countDown();
            } else {
                captured.add(triggerEvent);
            }
        });
        emit.run();
        // The single worker drains in FIFO order, so the sentinel arriving proves every emitted event was
        // already collected -- including the "nothing was emitted" case, which no wait on the events themselves
        // could establish.
        triggerExecutor.submit(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, SENTINEL, "recalc",
                false, List.of(), "alice", 0));
        try {
            assertTrue(sentinelSeen.await(5, TimeUnit.SECONDS), "the sentinel event was never dispatched");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        return List.copyOf(captured);
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private void install(Set<EventType> events, String mode, boolean allowCascade, boolean enabled) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(new TriggerDefinition("t",
                new LinkedHashSet<>(events), "recalc", mode, allowCascade, enabled, "owner", 1L, 1L, 1L, "owner")));
    }

    @Test
    public void test_fires_nothing_when_triggers_disabled() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 0)).isEmpty());
    }

    @Test
    public void test_fires_nothing_when_no_trigger_is_installed() {
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 0)).isEmpty());
    }

    @Test
    public void test_fires_only_matching_event_type() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        assertEquals(1, capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 0)).size());
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.UPDATED,
                entry("a"), "alice", 0)).isEmpty());
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.DELETED,
                entry("a"), "alice", 0)).isEmpty());
    }

    @Test
    public void test_document_mode_fires_once_per_document() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        final var events = capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                List.of(entry("a"), entry("b"), entry("c")), "alice", 0));
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(event -> event.getEntries().size() == 1));
        assertFalse(events.getFirst().isBatchMode());
    }

    @Test
    public void test_batch_mode_fires_once() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_BATCH, false, true);
        final var events = capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                List.of(entry("a"), entry("b"), entry("c")), "alice", 0));
        assertEquals(1, events.size());
        assertTrue(events.getFirst().isBatchMode());
        assertEquals(3, events.getFirst().getEntries().size());
    }

    @Test
    public void test_skips_disabled_trigger() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, false);
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 0)).isEmpty());
    }

    // allowCascade defaults to false, so the common configuration cannot cascade even once
    @Test
    public void test_does_not_fire_when_allow_cascade_false_and_depth_positive() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 1)).isEmpty());
    }

    @Test
    public void test_fires_at_depth_when_cascade_allowed() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, true, true);
        final var events = capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 2));
        assertEquals(1, events.size());
        assertEquals(2, events.getFirst().getDepth());
    }

    // The writer, which is what the trigger's args report and what explains why it fired
    @Test
    public void test_event_carries_the_acting_user() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        final var events = capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                entry("a"), "alice", 0));
        assertEquals("alice", events.getFirst().getActingUser());
        assertEquals("recalc", events.getFirst().getProcedureName());
        assertEquals("t", events.getFirst().getTriggerName());
        assertEquals(EventType.CREATED, events.getFirst().getType());
    }

    @Test
    public void test_null_and_empty_entries_fire_nothing() {
        install(Set.of(EventType.DELETED), TriggerDefinition.MODE_DOCUMENT, false, true);
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.DELETED,
                (DbEntry) null, "alice", 0)).isEmpty());
        assertTrue(capture(() -> TriggerHelper.afterWrite(TestGlobals.DB, TestGlobals.COLL, EventType.DELETED,
                List.of(), "alice", 0)).isEmpty());
    }

    @Test
    public void test_capture_for_delete_returns_null_without_a_delete_trigger() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        assertNull(TriggerHelper.captureForDelete(TestGlobals.DB, TestGlobals.COLL, "a", 0));
    }

    @Test
    public void test_after_write_ids_fires_nothing_for_empty_ids() {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT, false, true);
        assertTrue(capture(() -> TriggerHelper.afterWriteIds(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED,
                List.of(), "alice", 0)).isEmpty());
        assertTrue(capture(() -> TriggerHelper.afterWriteIds(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, null,
                "alice", 0)).isEmpty());
    }

    // afterBulkSave fires only for a BulkSaveResponse: a bulk save that failed has no inserts or updates
    // to report, so nothing must be queued.
    @Test
    public void test_after_bulk_save_fires_nothing_for_a_failed_write() {
        assertTrue(capture(() -> TriggerHelper.afterBulkSave(TestGlobals.DB, TestGlobals.COLL,
                new OperationResponse(OperationType.BULK_SAVE, ErrorCode.ERROR_BULK_SAVING), "alice", 0)).isEmpty());
    }
}
