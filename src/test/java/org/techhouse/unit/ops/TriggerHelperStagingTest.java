package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
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
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.StagedTriggerRuns;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerHelperStagingTest {
    private static final String DRAIN_MARKER = "__drain-marker__";
    private static final String USER = "alice";
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
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerThreads", 1);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        triggerExecutor.stop();
        for (final var run : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(run.getRunId(), run.getTriggerName());
        }
    }

    private List<TriggerEvent> dispatched(Callable<?> emit) {
        final var dispatched = new CopyOnWriteArrayList<TriggerEvent>();
        final var drained = new CountDownLatch(1);
        triggerExecutor.start(event -> {
            if (DRAIN_MARKER.equals(event.getTriggerName())) {
                drained.countDown();
            } else {
                dispatched.add(event);
            }
        });
        try {
            emit.call();
        } catch (Exception ignored) {
        }
        triggerExecutor.submit(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, DRAIN_MARKER,
                "recalc", false, List.of(), USER, 0));
        try {
            assertTrue(drained.await(5, TimeUnit.SECONDS), "the drain marker was never dispatched");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        return List.copyOf(dispatched);
    }

    private void install(Set<EventType> events, String mode) {
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL, List.of(new TriggerDefinition("t",
                new LinkedHashSet<>(events), "recalc", mode, false, true, "owner", 1L, 1L, 1L, "owner")));
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("v", "value-" + id);
        return object;
    }

    private static SaveRequest saveRequest(String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        return request;
    }

    private static void store(String id) throws Exception {
        SaveOperationHelper.executeSave(saveRequest(id));
    }

    private long storedVersion(String id) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        return primaryKeyIndex.get(Collections.binarySearch(primaryKeyIndex, id)).getVersion();
    }

    private static AdminTriggerRunEntry onlyRecord() throws Exception {
        final var pending = TriggerRunLog.pending();
        assertEquals(1, pending.size(), "expected exactly one recorded run, got " + pending);
        return pending.getFirst();
    }

    private static OperationResponse runStaged(StagedTriggerRuns staged, Callable<OperationResponse> write)
            throws Exception {
        return TriggerHelper.runStaged(staged, TestGlobals.DB, TestGlobals.COLL, USER, 0, write);
    }

    @Test
    public void test_stage_save_records_a_created_run_with_an_absent_version_for_a_new_id() throws Exception {
        install(Set.of(EventType.CREATED, EventType.UPDATED), TriggerDefinition.MODE_DOCUMENT);

        TriggerHelper.stageSave(saveRequest("brand-new"), USER);

        final var record = onlyRecord();
        assertEquals(TriggerRunStatus.STAGED, record.getStatus());
        assertEquals(EventType.CREATED, record.getEventType());
        assertEquals(Map.of("brand-new", AdminTriggerRunEntry.ABSENT_VERSION), record.getPriorVersions());
    }

    @Test
    public void test_stage_save_decides_updated_from_the_pk_index() throws Exception {
        store("existing");
        install(Set.of(EventType.CREATED, EventType.UPDATED), TriggerDefinition.MODE_DOCUMENT);

        TriggerHelper.stageSave(saveRequest("existing"), USER);

        final var record = onlyRecord();
        assertEquals(EventType.UPDATED, record.getEventType());
        assertEquals(Map.of("existing", storedVersion("existing")), record.getPriorVersions());
    }

    @Test
    public void test_stage_save_assigns_an_id_to_an_object_without_one() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(new JsonObject());

        TriggerHelper.stageSave(request, USER);

        assertEquals(List.of(request.get_id()), onlyRecord().getIds());
    }

    @Test
    public void test_stage_delete_records_the_captured_document_and_its_version() throws Exception {
        store("doomed");
        install(Set.of(EventType.DELETED), TriggerDefinition.MODE_DOCUMENT);
        final var captured = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document("doomed"));
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("doomed");

        TriggerHelper.stageDelete(request, captured, USER);

        final var record = onlyRecord();
        assertEquals(EventType.DELETED, record.getEventType());
        assertEquals(1, record.getDocuments().size());
        assertEquals(Map.of("doomed", storedVersion("doomed")), record.getPriorVersions());
        assertTrue(TriggerHelper.stageDelete(request, null, USER).isEmpty());
    }

    @Test
    public void test_no_trigger_stages_nothing() throws Exception {
        assertTrue(TriggerHelper.stageSave(saveRequest("quiet"), USER).isEmpty());
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_a_landed_save_is_submitted_with_its_staged_run_id_and_no_second_record() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT);
        final var request = saveRequest("landed");

        final var events = dispatched(() -> runStaged(TriggerHelper.stageSave(request, USER),
                () -> SaveOperationHelper.executeSave(request)));

        assertEquals(1, events.size());
        final var record = onlyRecord();
        assertEquals(record.getRunId(), events.getFirst().getRunId());
        assertEquals("landed", events.getFirst().getEntries().getFirst().get_id());
    }

    @Test
    public void test_an_error_response_discards_the_staged_run() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT);
        final var refused = new OperationResponse(OperationType.SAVE, ErrorCode.ERROR_SAVING);

        final var events = dispatched(
                () -> runStaged(TriggerHelper.stageSave(saveRequest("refused"), USER), () -> refused));

        assertTrue(events.isEmpty());
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_an_exception_discards_the_staged_run_and_propagates() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT);
        final var staged = TriggerHelper.stageSave(saveRequest("thrown"), USER);

        assertThrows(IOException.class, () -> runStaged(staged, () -> {
            throw new IOException("disk full");
        }));

        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_a_partial_bulk_save_submits_and_keeps_only_the_committed_ids() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_BATCH);
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(List.of(document("kept"), document("lost")));
        final var staged = TriggerHelper.stageBulkSave(bulk, USER);
        final var committed = new BulkSaveResponse("partial", List.of("kept"), List.of());

        final var events = dispatched(() -> runStaged(staged, () -> {
            store("kept");
            throw new PartialBulkSaveException(committed, new IOException("disk full"));
        }));

        assertEquals(1, events.size());
        assertEquals(List.of("kept"), events.getFirst().getEntries().stream().map(DbEntry::get_id).toList());
        final var record = onlyRecord();
        assertEquals(List.of("kept"), record.getIds());
        assertEquals(TriggerRunStatus.PENDING, record.getStatus());
    }

    @Test
    public void test_a_landed_write_of_another_type_than_staged_falls_back_to_record_after() throws Exception {
        install(Set.of(EventType.CREATED, EventType.UPDATED), TriggerDefinition.MODE_DOCUMENT);
        final var staged = TriggerHelper.stageSave(saveRequest("retyped"), USER);
        store("retyped");

        final var events = dispatched(
                () -> runStaged(staged, () -> new SaveResponse("Successfully saved", "retyped", false)));

        assertEquals(1, events.size());
        assertEquals(EventType.UPDATED, events.getFirst().getType());
        assertEquals(EventType.UPDATED, onlyRecord().getEventType());
    }

    @Test
    public void test_a_disabled_run_log_still_fires_without_a_record() throws Exception {
        install(Set.of(EventType.CREATED), TriggerDefinition.MODE_DOCUMENT);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
        final var request = saveRequest("unlogged");

        final var events = dispatched(() -> runStaged(TriggerHelper.stageSave(request, USER),
                () -> SaveOperationHelper.executeSave(request)));

        assertEquals(1, events.size());
        assertNull(events.getFirst().getRunId());
    }
}
