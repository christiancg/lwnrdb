package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.StaleCompactionMarker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.index.IndexOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.PrimaryKeyReindexResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReindexPrimaryKeyTest {
    private static final String TAG = "tag";
    private static final String OTHER = "other";
    private static final String GARBAGE = "garbage\nmore garbage\n";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private BackgroundTaskManager originalTaskManager;
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(PageOccupancyReconciler.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", stubTaskManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", originalTaskManager);
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty(TAG, "tag-" + id);
        object.addProperty(OTHER, "other-" + id);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void index(String field) {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, field)).getStatus());
    }

    private OperationResponse reindex(String... fieldNames) {
        return processor
                .processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, List.of(fieldNames), true));
    }

    private OperationStatus find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private File collectionFile(String name) {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + name);
    }

    private File pkFile() {
        return collectionFile("-pk.idx");
    }

    private List<PkIndexEntry> pkRows() throws IOException {
        return new ArrayList<>(fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL));
    }

    private void writePk(List<PkIndexEntry> rows) throws IOException {
        Files.write(pkFile().toPath(), rows.stream().map(PkIndexEntry::toFileEntry).toList(), StandardCharsets.UTF_8);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
    }

    private void writeGarbagePk() throws IOException {
        Files.writeString(pkFile().toPath(), GARBAGE, StandardCharsets.UTF_8);
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
    }

    private byte[] tagIndexBytes() throws IOException {
        return Files.readAllBytes(collectionFile("-" + TAG + "-String.idx").toPath());
    }

    private Set<String> tagIndexIds() throws IOException {
        return fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, TAG, String.class).stream()
                .flatMap(entry -> entry.getIds().stream()).collect(Collectors.toSet());
    }

    private List<String> queuedIds() throws Exception {
        final LinkedBlockingQueue<?> queue = TestUtils.getPrivateField(stubTaskManager, "queue",
                LinkedBlockingQueue.class);
        return queue.stream().filter(EntityEvent.class::isInstance).map(EntityEvent.class::cast).map(EntityEvent::getId)
                .toList();
    }

    private List<String> idsTaggedLikeB() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(
                new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, TAG, new JsonString("tag-b")))));
        final var response = (AggregateResponse) processor.processMessage(request);
        return response.getResults().stream().map(row -> row.get("_id").asJsonString().getValue()).toList();
    }

    @Test
    public void test_primary_key_reindex_repairs_an_unrecognised_pk_index() throws Exception {
        save("a");
        save("b");
        index(TAG);
        writeGarbagePk();
        assertEquals(OperationStatus.ERROR, find("a"));

        final var response = assertInstanceOf(PrimaryKeyReindexResponse.class, reindex());

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(2, response.getPrimaryKeyEntries());
        assertEquals(2, response.getUnversionedEntries());
        assertEquals(OperationStatus.OK, find("a"));
        assertEquals(OperationStatus.OK, find("b"));
        save("c");
        assertEquals(OperationStatus.OK, find("c"));
    }

    @Test
    public void test_an_unreadable_pk_index_falls_back_to_rebuilding_every_field_index() throws Exception {
        save("a");
        index(TAG);
        index(OTHER);
        writeGarbagePk();

        final var response = assertInstanceOf(PrimaryKeyReindexResponse.class, reindex());

        assertEquals(Set.of(TAG, OTHER), Set.copyOf(response.getRebuiltFields()));
    }

    @Test
    public void test_primary_key_only_touches_no_field_index_file() throws Exception {
        save("a");
        save("b");
        index(TAG);
        final var rows = pkRows();
        rows.forEach(row -> row.setPosition(row.getPosition() + 1));
        writePk(rows);
        final var indexBefore = tagIndexBytes();

        final var response = assertInstanceOf(PrimaryKeyReindexResponse.class, reindex());

        assertTrue(response.getRebuiltFields().isEmpty());
        assertEquals(0, response.getUnversionedEntries());
        assertArrayEquals(indexBefore, tagIndexBytes());
        assertEquals(List.of("b"), idsTaggedLikeB());
        assertTrue(queuedIds().isEmpty(), "an offset repair changes no id, so nothing is queued");
    }

    @Test
    public void test_an_appeared_id_reaches_the_field_indexes() throws Exception {
        save("a");
        save("b");
        save("c");
        index(TAG);
        writePk(pkRows().stream().filter(row -> !row.getValue().equals("b")).toList());

        reindex();

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("b"));
        assertEquals(List.of("b"), queuedIds());
        assertEquals(List.of("b"), idsTaggedLikeB(), "the pending overlay answers before the worker runs");
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "b");
        assertEquals(Set.of("a", "b", "c"), tagIndexIds());
        assertEquals(OperationStatus.OK, find("b"));
    }

    @Test
    public void test_a_vanished_id_leaves_the_field_indexes() throws Exception {
        save("a");
        save("b");
        save("ghost");
        index(TAG);
        final var ghost = pkRows().stream().filter(row -> row.getValue().equals("ghost")).findFirst().orElseThrow();
        fs.deleteFromCollection(ghost.detachedCopy());
        final var rows = pkRows();
        rows.add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "ghost", 0, 1, 0, ghost.getVersion()));
        writePk(rows);
        assertTrue(tagIndexIds().contains("ghost"));

        reindex();

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("ghost"));
        assertEquals(List.of("ghost"), queuedIds());
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "ghost");
        assertEquals(Set.of("a", "b"), tagIndexIds());
    }

    @Test
    public void test_primary_key_with_field_names_rebuilds_only_those_fields() {
        save("a");
        index(TAG);
        index(OTHER);

        final var response = assertInstanceOf(PrimaryKeyReindexResponse.class, reindex(TAG));

        assertEquals(List.of(TAG), response.getRebuiltFields());
    }

    @Test
    public void test_an_unregistered_field_answers_404_6_before_pk_idx_is_touched() throws Exception {
        save("a");
        writeGarbagePk();

        final var response = reindex("neverIndexed");

        assertEquals(ErrorCode.INDEX_NOT_FOUND.getCode(), response.getErrorCode());
        assertEquals(GARBAGE, Files.readString(pkFile().toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_primary_key_reindex_rebuilds_page_rows_even_when_sizes_agree() throws Exception {
        save("a");
        save("b");
        final var planted = new AdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 0);
        planted.setEntryCount(99);
        planted.setPageSize(collectionFile("-0.dat").length());
        AdminPageHelper.replacePageEntries(TestGlobals.DB, TestGlobals.COLL, List.of(planted));

        reindex();

        assertEquals(2, cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(row -> row.getPage() == 0).findFirst().orElseThrow().getEntryCount());
    }

    @Test
    public void test_a_refused_rebuild_answers_409_15_and_touches_no_field_index() throws Exception {
        save("a");
        index(TAG);
        final var page = collectionFile("-0.dat").toPath();
        Files.writeString(page, Files.readAllLines(page, StandardCharsets.UTF_8).getFirst() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        final var pkBefore = Files.readAllBytes(pkFile().toPath());
        final var indexBefore = tagIndexBytes();

        final var response = reindex();

        assertEquals(ErrorCode.PRIMARY_KEY_REBUILD_REFUSED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("appears twice"), response.getMessage());
        assertArrayEquals(pkBefore, Files.readAllBytes(pkFile().toPath()));
        assertArrayEquals(indexBefore, tagIndexBytes());
    }

    @Test
    public void test_primary_key_reindex_clears_the_dirty_marker_after_a_full_field_rebuild() throws Exception {
        save("a");
        index(TAG);
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "a");
        final var identifier = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(TestUtils.dirtyIndexCollections().contains(identifier));
        writeGarbagePk();

        reindex();

        assertFalse(TestUtils.dirtyIndexCollections().contains(identifier));
    }

    @Test
    public void test_reindex_without_the_flag_never_rewrites_pk_idx() throws Exception {
        save("a");
        index(TAG);
        final var pkBefore = Files.readAllBytes(pkFile().toPath());
        final var modifiedBefore = pkFile().lastModified();

        final var response = processor.processMessage(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null));

        assertFalse(response instanceof PrimaryKeyReindexResponse);
        assertArrayEquals(pkBefore, Files.readAllBytes(pkFile().toPath()));
        assertEquals(modifiedBefore, pkFile().lastModified());
    }

    @Test
    public void test_a_held_collection_lock_answers_409_5() throws Exception {
        final var config = Configuration.getInstance();
        final var previousBudget = config.getReplicationAckTimeoutMs();
        final var locks = IocContainer.get(ResourceLocking.class);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 200L);
        holder.start();
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            final var response = IndexOperationHelper
                    .processReindex(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null, true), true);
            assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        } finally {
            release.countDown();
            holder.join(5000);
            TestUtils.setPrivateField(config, "replicationAckTimeoutMs", previousBudget);
        }
    }

    @Test
    public void test_a_stale_marker_with_a_readable_pk_index_is_refused() throws Exception {
        save("a");
        save("b");
        final var marker = StaleCompactionMarker.writeUpdateMarker(new File(TestGlobals.PATH).getAbsolutePath(),
                TestGlobals.DB, TestGlobals.COLL, "a");
        save("c");
        final var page = Files.readAllBytes(collectionFile("-0.dat").toPath());

        final var response = reindex();

        assertEquals(ErrorCode.PRIMARY_KEY_REBUILD_REFUSED.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains(marker.getName()), response.getMessage());
        assertArrayEquals(page, Files.readAllBytes(collectionFile("-0.dat").toPath()),
                "a stale tail must never be replayed over records written since");
        assertTrue(marker.exists());
        assertEquals(OperationStatus.OK, find("c"));
    }

    @Test
    public void test_an_emptied_marker_does_not_block_the_rebuild() throws Exception {
        save("a");
        Files.writeString(collectionFile("-0.compacting").toPath(), "", StandardCharsets.UTF_8);

        assertEquals(OperationStatus.OK, reindex().getStatus());
    }

    private List<Boolean> observeWhenThePkIndexIsReplaced(BooleanSupplier observation) throws Exception {
        final var faultInjector = IocContainer.get(FaultInjector.class);
        final var originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        final var observed = new CopyOnWriteArrayList<Boolean>();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt", (IntConsumer) _ -> observed.add(observation.getAsBoolean()));
        try {
            faultInjector.arm(FaultPoint.PK_INDEX_REPLACED, FaultAction.HALT,
                    Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
            assertEquals(OperationStatus.OK, reindex().getStatus());
            return observed;
        } finally {
            faultInjector.releaseAll();
            TestUtils.setPrivateField(faultInjector, "enabled", false);
            TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
        }
    }

    private boolean pkIndexNames(String id) {
        try {
            return pkRows().stream().anyMatch(row -> row.getValue().equals(id));
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    public void test_the_changed_ids_are_already_pending_when_the_new_pk_index_lands() throws Exception {
        save("a");
        save("b");
        index(TAG);
        writePk(pkRows().stream().filter(row -> !row.getValue().equals("b")).toList());
        pendingIndexWrites.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        fs.indexMarkers().clearDirty(TestGlobals.DB, TestGlobals.COLL);
        assertFalse(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("b"));

        final var observed = observeWhenThePkIndexIsReplaced(
                () -> pkIndexNames("b") && pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("b")
                        && TestUtils.dirtyIndexCollections()
                                .contains(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

        assertEquals(List.of(true), observed,
                "a kill right after the new pk.idx lands must find the changed ids pending and the dirty marker set");
    }

    @Test
    public void test_every_target_field_is_already_building_when_an_unreadable_pk_index_is_replaced() throws Exception {
        save("a");
        index(TAG);
        index(OTHER);
        writeGarbagePk();

        final var observed = observeWhenThePkIndexIsReplaced(
                () -> pkIndexNames("a") && fs.indexMarkers().isMarked(TestGlobals.DB, TestGlobals.COLL, TAG)
                        && fs.indexMarkers().isMarked(TestGlobals.DB, TestGlobals.COLL, OTHER));

        assertEquals(List.of(true), observed,
                "a kill right after the new pk.idx lands must leave every field declined until REINDEX");
        assertFalse(fs.indexMarkers().isMarked(TestGlobals.DB, TestGlobals.COLL, TAG));
    }
}
