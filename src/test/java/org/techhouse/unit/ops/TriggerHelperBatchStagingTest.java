package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.StagedTriggerRuns;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TriggerTestSupport;

public class TriggerHelperBatchStagingTest {
    private static final String DRAIN_MARKER = "__drain-marker__";
    private static final String USER = "alice";
    private static final String RUNS_LOG = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION;
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
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("t", new LinkedHashSet<>(Set.of(EventType.CREATED)), "recalc",
                        TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner")));
        triggerExecutor.stop();
        TriggerTestSupport.consumeAllPendingRuns();
    }

    private static BulkSaveRequest bulkOf(String prefix, int count) {
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < count; i++) {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, prefix + i);
            objects.add(object);
        }
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        return request;
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

    private static List<String> idsOf(List<AdminTriggerRunEntry> records) {
        return records.stream().flatMap(entry -> entry.getIds().stream()).sorted().toList();
    }

    @Test
    public void test_a_bulk_save_stages_every_document_run_in_one_forced_record() throws Exception {
        final var bulk = bulkOf("one-record-", 50);

        try (var recorder = RecordingForcer.install(true)) {
            final var staged = TriggerHelper.stageBulkSave(bulk, USER);

            assertEquals(1L, recorder.forcedNames().stream().filter(RUNS_LOG::equals).count(),
                    "fifty document-mode runs must be staged by one forced record, not one each");
            assertFalse(staged.isEmpty());
        }
        final var records = TriggerRunLog.pending();
        assertEquals(50, records.size());
        assertTrue(records.stream().allMatch(record -> record.getStatus() == TriggerRunStatus.STAGED));
        assertEquals(50, records.stream().map(AdminTriggerRunEntry::getRunId).distinct().count());
    }

    @Test
    public void test_a_landed_bulk_save_submits_every_run_under_its_staged_id_with_one_document_read()
            throws Exception {
        final var bulk = bulkOf("landed-", 20);
        final var staged = TriggerHelper.stageBulkSave(bulk, USER);
        final var response = (BulkSaveResponse) SaveOperationHelper.executeBulkSave(bulk);

        final var reads = spy(cache);
        TestUtils.setPrivateStaticField(TriggerHelper.class, "cache", reads);
        final List<TriggerEvent> events;
        try {
            events = dispatched(() -> {
                staged.submitLanded(Map.of(EventType.CREATED, new LinkedHashSet<>(response.getInserted())),
                        TestGlobals.DB, TestGlobals.COLL, USER, 0);
                return null;
            });
            verify(reads, times(1)).getEntriesByIds(eq(TestGlobals.DB), eq(TestGlobals.COLL), anySet());
        } finally {
            TestUtils.setPrivateStaticField(TriggerHelper.class, "cache", cache);
        }
        assertEquals(20, events.size());
        final var recordedRunIds = TriggerRunLog.pending().stream().map(AdminTriggerRunEntry::getRunId)
                .collect(Collectors.toSet());
        assertEquals(recordedRunIds, events.stream().map(TriggerEvent::getRunId).collect(Collectors.toSet()));
    }

    @Test
    public void test_a_failed_batch_write_falls_back_to_one_record_per_run() throws Exception {
        final var bulk = bulkOf("fallback-", 5);

        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            admin.when(() -> AdminOperationHelper.saveTriggerRuns(anyList(), anyList()))
                    .thenThrow(new IOException("disk full"));

            TriggerHelper.stageBulkSave(bulk, USER);

            admin.verify(() -> AdminOperationHelper.saveTriggerRun(any()), times(5));
        }
        final var records = TriggerRunLog.pending();
        assertEquals(List.of("fallback-0", "fallback-1", "fallback-2", "fallback-3", "fallback-4"), idsOf(records));
        assertTrue(records.stream().allMatch(record -> record.getStatus() == TriggerRunStatus.STAGED),
                "the fallback keeps the staging durable before the write");
    }

    @Test
    public void test_runs_whose_fallback_fails_too_still_fire_without_a_record() throws Exception {
        final var bulk = bulkOf("unrecorded-", 3);
        final var staged = new ArrayList<StagedTriggerRuns>();
        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            admin.when(() -> AdminOperationHelper.saveTriggerRuns(anyList(), anyList()))
                    .thenThrow(new IOException("disk full"));
            admin.when(() -> AdminOperationHelper.saveTriggerRun(any())).thenThrow(new IOException("disk full"));

            staged.add(TriggerHelper.stageBulkSave(bulk, USER));
        }
        assertTrue(TriggerRunLog.pending().isEmpty());

        final var events = dispatched(() -> TriggerHelper.runStaged(staged.getFirst(), TestGlobals.DB, TestGlobals.COLL,
                USER, 0, () -> SaveOperationHelper.executeBulkSave(bulk)));

        assertEquals(3, events.size(), "a run that could not be recorded still fires, as before");
    }

    @Test
    public void test_a_partial_document_mode_bulk_save_discards_the_unlanded_runs_in_one_delete() throws Exception {
        final var bulk = bulkOf("partial-", 3);
        final var staged = TriggerHelper.stageBulkSave(bulk, USER);
        final var committed = new BulkSaveResponse("partial", List.of("partial-0"), List.of());

        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            final var events = dispatched(
                    () -> TriggerHelper.runStaged(staged, TestGlobals.DB, TestGlobals.COLL, USER, 0, () -> {
                        SaveOperationHelper.executeBulkSave(bulkOf("partial-", 1));
                        throw new PartialBulkSaveException(committed, new IOException("disk full"));
                    }));

            assertEquals(List.of("partial-0"),
                    events.stream().map(event -> event.getEntries().getFirst().get_id()).toList());
            admin.verify(() -> AdminOperationHelper.deleteTriggerRuns(anyList()), times(1));
        }
        assertEquals(List.of("partial-0"), idsOf(TriggerRunLog.pending()));
    }

    @Test
    public void test_a_write_that_throws_discards_every_staged_run_in_one_delete() throws Exception {
        final var staged = TriggerHelper.stageBulkSave(bulkOf("thrown-", 4), USER);
        assertEquals(4, TriggerRunLog.pending().size());

        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            try {
                TriggerHelper.runStaged(staged, TestGlobals.DB, TestGlobals.COLL, USER, 0, () -> {
                    throw new IOException("disk full");
                });
                fail("the write failure must propagate");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
            admin.verify(() -> AdminOperationHelper.deleteTriggerRuns(anyList()), times(1));
        }
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_a_deleted_document_vanished_before_submission_discards_only_its_run() throws Exception {
        final var bulk = bulkOf("vanish-", 2);
        final var staged = TriggerHelper.stageBulkSave(bulk, USER);
        SaveOperationHelper.executeBulkSave(bulkOf("vanish-", 1));

        final var events = dispatched(() -> {
            staged.submitLanded(Map.of(EventType.CREATED, Set.of("vanish-0", "vanish-1")), TestGlobals.DB,
                    TestGlobals.COLL, USER, 0);
            return null;
        });

        assertEquals(List.of("vanish-0"),
                events.stream().map(event -> event.getEntries().getFirst()).map(DbEntry::get_id).toList());
        assertEquals(List.of("vanish-0"), idsOf(TriggerRunLog.pending()));
    }
}
