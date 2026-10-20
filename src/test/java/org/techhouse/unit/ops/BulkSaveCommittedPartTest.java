package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ClusterWriteHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.StagedTriggerRuns;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class BulkSaveCommittedPartTest {
    private static final int DEPTH = 2;
    private static final List<String> COMMITTED_INSERTS = List.of("inserted");
    private static final List<String> COMMITTED_UPDATES = List.of("updated");

    private static final Map<EventType, Set<String>> COMMITTED_IDS = Map.of(EventType.CREATED,
            Set.copyOf(COMMITTED_INSERTS), EventType.UPDATED, Set.copyOf(COMMITTED_UPDATES));

    private final StagedTriggerRuns staged = mock(StagedTriggerRuns.class);
    private final BulkSaveResponse committed = new BulkSaveResponse("Partially saved entries", COMMITTED_INSERTS,
            COMMITTED_UPDATES);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static BulkSaveRequest bulkRequest() {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, "inserted");
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(List.of(object));
        request.setTriggerDepth(DEPTH);
        return request;
    }

    private PartialBulkSaveException partialFailure() {
        return new PartialBulkSaveException(committed, new IOException("disk full"));
    }

    private OperationResponse process(BulkSaveRequest request) {
        try (var triggers = mockStatic(TriggerHelper.class, CALLS_REAL_METHODS)) {
            triggers.when(() -> TriggerHelper.stageBulkSave(eq(request), any())).thenReturn(staged);
            return IocContainer.get(OperationProcessor.class).processMessage(request);
        }
    }

    private static boolean isTheCommittedPart(OperationResponse response) {
        return response instanceof BulkSaveResponse bulk && bulk.getInserted().equals(COMMITTED_INSERTS)
                && bulk.getUpdated().equals(COMMITTED_UPDATES);
    }

    @Test
    public void test_triggers_fire_for_the_committed_part_of_a_failed_bulk_save() {
        final var request = bulkRequest();
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var ignored = mockStatic(ClusterWriteHelper.class)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(request)).thenThrow(partialFailure());

            assertEquals(ErrorCode.ERROR_BULK_SAVING.getCode(), process(request).getErrorCode());

            verify(staged).submitLanded(eq(COMMITTED_IDS), eq(TestGlobals.DB), eq(TestGlobals.COLL), any(), eq(DEPTH));
        }
    }

    @Test
    public void test_the_committed_part_is_replicated() {
        final var request = bulkRequest();
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var cluster = mockStatic(ClusterWriteHelper.class)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(request)).thenThrow(partialFailure());

            assertEquals(ErrorCode.ERROR_BULK_SAVING.getCode(), process(request).getErrorCode());

            cluster.verify(() -> ClusterWriteHelper.afterBulkSave(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    argThat(BulkSaveCommittedPartTest::isTheCommittedPart)));
        }
    }

    @Test
    public void test_success_path_is_unchanged() {
        final var request = bulkRequest();
        final var replicated = new OperationResponse(null, ErrorCode.ERROR_BULK_SAVING);
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var cluster = mockStatic(ClusterWriteHelper.class)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(request)).thenReturn(committed);
            cluster.when(() -> ClusterWriteHelper.afterBulkSave(TestGlobals.DB, TestGlobals.COLL, committed))
                    .thenReturn(replicated);

            assertSame(replicated, process(request),
                    "a successful bulk save answers with whatever replication made of it");
            verify(staged).submitLanded(eq(COMMITTED_IDS), eq(TestGlobals.DB), eq(TestGlobals.COLL), any(), eq(DEPTH));
        }
    }

    @Test
    public void test_the_client_still_receives_error_bulk_saving() {
        final var request = bulkRequest();
        try (var save = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS);
                var triggers = mockStatic(TriggerHelper.class, CALLS_REAL_METHODS)) {
            save.when(() -> SaveOperationHelper.executeBulkSave(any(BulkSaveRequest.class)))
                    .thenThrow(partialFailure());

            final var response = IocContainer.get(OperationProcessor.class).processMessage(request);

            assertEquals(ErrorCode.ERROR_BULK_SAVING.getCode(), response.getErrorCode(),
                    "publishing the committed part must not turn the failed request into a success");
            triggers.verify(() -> TriggerHelper.afterWriteIds(anyString(), anyString(), eq(EventType.CREATED),
                    eq(COMMITTED_INSERTS), any(), anyInt()));
            triggers.verify(() -> TriggerHelper.afterWriteIds(anyString(), anyString(), eq(EventType.UPDATED),
                    eq(COMMITTED_UPDATES), any(), anyInt()));
        }
    }

    private static List<JsonObject> objectsFor(int round, int count) {
        final var ids = new LinkedHashSet<String>();
        for (var i = 0; ids.size() < count; i++) {
            ids.add("bulk-" + ((i * 7 + round * 31) % 300));
        }
        final var objects = new ArrayList<JsonObject>();
        for (final var id : ids) {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, id);
            object.addProperty("round", round);
            objects.add(object);
        }
        return objects;
    }

    @Test
    public void test_a_bulk_save_keeps_the_pk_list_sorted_and_unique() throws Exception {
        final var processor = IocContainer.get(OperationProcessor.class);
        for (var round = 0; round < 4; round++) {
            final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObjects(objectsFor(round, round % 2 == 0 ? 200 : 10));
            assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        }
        final var locks = IocContainer.get(ResourceLocking.class);
        final var cache = IocContainer.get(Cache.class);
        final var fs = IocContainer.get(FileSystem.class);
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var cached = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
            final var ids = cached.stream().map(PkIndexEntry::getValue).toList();
            for (var i = 1; i < ids.size(); i++) {
                assertEquals(-1, Integer.signum(ids.get(i - 1).compareTo(ids.get(i))), "sorted and unique at " + i);
            }
            final var fromDisk = new ArrayList<>(fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL));
            fromDisk.sort(Comparator.comparing(PkIndexEntry::getValue));
            assertEquals(fromDisk, new ArrayList<>(cached));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }
}
