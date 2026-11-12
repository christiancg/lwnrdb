package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PkRowsSession;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.DeferredWrites;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DeferredWriteApplyTest {
    private static final String WAL = TestGlobals.COLL + ".wal";
    private static final int DOCUMENTS = 10;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        recorder = RecordingForcer.install(true);
        DeferredWrites.install();
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "n"));
        final var seeded = new ArrayList<JsonObject>();
        for (var index = 0; index < DOCUMENTS; index++) {
            seeded.add(document("d" + index, index));
        }
        assertEquals(OperationStatus.OK, bulkSave(seeded).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        writablePages(true);
        recorder.close();
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, int n) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.add("n", new JsonNumber(n));
        return object;
    }

    private OperationResponse bulkSave(List<JsonObject> objects) {
        final var request = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObjects(objects);
        return processor.processMessage(request);
    }

    private static List<File> pages() {
        final var files = new File(TestGlobals.PATH + "/" + TestGlobals.DB + "/" + TestGlobals.COLL)
                .listFiles((_, name) -> name.endsWith(".dat"));
        return files == null ? List.of() : List.of(files);
    }

    private static void writablePages(boolean writable) {
        for (final var page : pages()) {
            assertTrue(page.setWritable(writable));
        }
    }

    private void failTheTakeBack() {
        recorder.clear();
        recorder.refusingWhen((forced, name) -> name.equals(WAL) && forced.contains(WAL));
        writablePages(false);
    }

    private void freeTheDisk() {
        recorder.refusingWhen((_, _) -> false);
        writablePages(true);
    }

    private JsonObject findD1() {
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("d1");
        final var response = processor.processMessage(find);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return ((FindByIdResponse) response).getObject();
    }

    private OperationResponse query(boolean scan, int n) {
        final var steps = new ArrayList<BaseAggregationStep>();
        if (scan) {
            steps.add(new SkipAggregationStep(0));
        }
        steps.add(new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, "n", new JsonNumber(n))));
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        return processor.processMessage(request);
    }

    private List<String> idsWhere(boolean scan, int n) {
        final var response = query(scan, n);
        if (!(response instanceof AggregateResponse answered)) {
            assertEquals("404-3", response.getErrorCode(), response.getMessage());
            return List.of();
        }
        return answered.getResults().stream().map(row -> row.get("_id").asJsonString().getValue()).toList();
    }

    @Test
    public void aForcedWriteWhoseApplyAndTakeBackFailIsAnsweredUnknown() {
        failTheTakeBack();

        final var response = bulkSave(List.of(document("d1", 100)));

        assertEquals("500-34", response.getErrorCode(), response.getMessage());
    }

    @Test
    public void theDeferredApplyQueuesIndexWorkAndEvictsTheStalePkRow() {
        assertEquals(1.0, findD1().get("n").asJsonNumber().getValue());
        failTheTakeBack();
        bulkSave(List.of(document("d1", 100)));
        freeTheDisk();

        assertEquals(100.0, findD1().get("n").asJsonNumber().getValue());
        assertEquals(List.of("d1"), idsWhere(false, 100));
        assertEquals(List.of("d1"), idsWhere(true, 100));
        assertEquals(List.of(), idsWhere(false, 1));
        assertEquals(List.of(), idsWhere(true, 1));
    }

    @Test
    public void aReadWhileTheDiskIsStillFullLeavesTheApplyToTheNextUse() {
        failTheTakeBack();
        bulkSave(List.of(document("d1", 100)));
        recorder.refusingWhen((_, _) -> false);
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("d1");

        assertEquals("503-19", query(true, 1).getErrorCode(), "a scan must not read the half-applied pages");
        assertEquals("503-19", processor.processMessage(find).getErrorCode());
        writablePages(true);

        assertEquals(100.0, findD1().get("n").asJsonNumber().getValue());
        assertEquals(List.of("d1"), idsWhere(false, 100));
    }

    @Test
    public void unappliedBlanksAreNotVisibleToAScan() {
        writablePages(false);
        final var delete = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        delete.set_id("d2");
        final var deleted = processor.processMessage(delete);
        writablePages(true);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(OperationStatus.OK, deleted.getStatus(), deleted.getMessage());
        assertEquals(List.of(), idsWhere(true, 2));
    }

    @Test
    public void theRuntimeApplyRebuildsOnlyThePageRowsOfTheReplayedCollection() throws Exception {
        failTheTakeBack();
        bulkSave(List.of(document("d1", 100)));
        freeTheDisk();
        final var fs = IocContainer.get(FileSystem.class);
        final var spy = Mockito.spy(fs);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "fs", spy);
        try {
            assertEquals(100.0, findD1().get("n").asJsonNumber().getValue());
        } finally {
            TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "fs", fs);
        }

        Mockito.verify(spy, Mockito.never()).healTornPageTails(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString());
        Mockito.verify(spy, Mockito.never()).healTornPageTails(ArgumentMatchers.any(PkRowsSession.class));
        Mockito.verify(spy, Mockito.never()).retireDanglingPkEntries(ArgumentMatchers.any());
        Mockito.verify(spy, Mockito.never()).adoptOrphanedRecords(ArgumentMatchers.any());
        final var liveBytes = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .mapToLong(PkIndexEntry::getLength).sum();
        assertEquals(liveBytes,
                cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                        .mapToLong(AdminPageEntry::getPageSize).sum(),
                "the replayed collection's page rows are rebuilt");
    }

    private Map<Long, List<Long>> rowsByPage() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(row -> row.getEntryCount() > 0 || row.getPageSize() > 0)
                .collect(Collectors.toMap(AdminPageEntry::getPage,
                        row -> List.of((long) row.getEntryCount(), row.getPageSize()), (a, _) -> a, TreeMap::new));
    }

    private Map<Long, List<Long>> liveByPage() throws Exception {
        final var live = new TreeMap<Long, List<Long>>();
        for (final var row : fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL)) {
            live.merge(row.getPage(), List.of(1L, row.getLength()),
                    (a, b) -> List.of(a.getFirst() + b.getFirst(), a.getLast() + b.getLast()));
        }
        return live;
    }

    private static JsonObject grownD1() {
        final var object = document("d1", 100);
        object.addProperty("body", "y".repeat(300));
        return object;
    }

    @Test
    public void aReplayThatBypassesTheLockHookStillReconcilesThePageRowsOnTheNextUse() throws Exception {
        failTheTakeBack();
        assertEquals("500-34", bulkSave(List.of(grownD1())).getErrorCode());
        freeTheDisk();

        assertTrue(fs.applyDeferredWrites(TestGlobals.DB, TestGlobals.COLL));
        findD1();

        assertEquals(liveByPage(), rowsByPage(), "the rows follow the replayed write once the collection is used");
    }

    @Test
    public void aReplayInsideAnotherWritersLockHoldStillReconcilesThePageRows() throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            failTheTakeBack();
            assertEquals("500-34", bulkSave(List.of(grownD1())).getErrorCode());
            freeTheDisk();
            bulkSave(List.of(document("d2", 200)));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertEquals(100.0, findD1().get("n").asJsonNumber().getValue());

        assertEquals(liveByPage(), rowsByPage(), "the rows follow a write replayed by the next writer's plan");
    }
}
