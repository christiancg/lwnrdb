package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

// Covers the index read/write consistency layer: the PendingIndexWrites overlay (no false positives
// or negatives across FILTER/COUNT/GROUP_BY/SORT/DISTINCT/JOIN), evict-on-write convergence, and the
// UserCache snapshot/eviction helpers.
public class IndexConsistencyTest {
    private Cache cache;
    private PendingIndexWrites pending;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        cache = IocContainer.get(Cache.class);
        pending = IocContainer.get(PendingIndexWrites.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void addDoc(String coll, String id, String field, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, coll, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, coll, entry);
    }

    private void enableIndex(String coll, String field) {
        IndexHelper.createIndex(TestGlobals.DB, coll, field);
        cache.getAdminCollectionEntry(TestGlobals.DB, coll).setIndexes(Set.of(field));
    }

    // Simulates a committed-but-not-yet-indexed write: in cache + marked pending, but not in the index
    // (because it is added after enableIndex).
    private void addPendingDoc(String coll, String id, String field, JsonBaseElement value) {
        addDoc(coll, id, field, value);
        pending.mark(TestGlobals.DB, coll, id);
    }

    private Set<String> filterIds(JsonBaseElement value) throws IOException {
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "status", value);
        return FilterOperatorHelper.processOperator(operator, null, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).collect(Collectors.toSet());
    }

    private long countWith(FilterAggregationStep filter) throws IOException {
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(filter, new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(req);
        return result.getFirst().get("count").asJsonNumber().getValue().longValue();
    }

    // ── FILTER ───────────────────────────────────────────────────────────────

    // A matching document committed but not yet indexed is returned (no false negative).
    @Test
    public void test_filter_includes_pending_not_yet_indexed_match() throws IOException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("active"));
        enableIndex(TestGlobals.COLL, "status");
        addPendingDoc(TestGlobals.COLL, "2", "status", new JsonString("active"));

        assertEquals(Set.of("1", "2"), filterIds(new JsonString("active")));
    }

    // A document whose indexed value changed (stale index entry) is not wrongly returned under its old
    // value (no false positive) and is found under its new value (no false negative).
    @Test
    public void test_filter_reconciles_stale_updated_value() throws IOException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("active"));
        enableIndex(TestGlobals.COLL, "status");
        // Update doc 1 to inactive in the document cache, but leave the index stale (still active->{1}).
        addPendingDoc(TestGlobals.COLL, "1", "status", new JsonString("inactive"));

        assertTrue(filterIds(new JsonString("active")).isEmpty());
        assertEquals(Set.of("1"), filterIds(new JsonString("inactive")));
    }

    // ── COUNT ──────────────────────────────────────────────────────────────--

    // Index-only COUNT with a filter reflects the current documents, not the stale index.
    @Test
    public void test_count_with_filter_is_consistent() throws IOException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("active"));
        enableIndex(TestGlobals.COLL, "status");
        addPendingDoc(TestGlobals.COLL, "2", "status", new JsonString("active"));
        addPendingDoc(TestGlobals.COLL, "3", "status", new JsonString("inactive"));

        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")));
        assertEquals(2L, countWith(filter));
    }

    // Whole-collection COUNT comes from the synchronously-maintained PK index, so it is exact even
    // before the background has processed the saves.
    @Test
    public void test_whole_collection_count_uses_pk_index() throws IOException {
        final var processor = new OperationProcessor();
        for (var id : List.of("1", "2", "3")) {
            final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString(id));
            obj.addProperty("status", "active");
            save.setObject(obj);
            processor.processMessage(save);
        }
        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new CountAggregationStep()));
        final var result = AggregationOperationHelper.processAggregation(req);
        assertEquals(3L, result.getFirst().get("count").asJsonNumber().getValue().longValue());
    }

    // ── DISTINCT ─────────────────────────────────────────────────────────────

    // A pending document with a brand-new value contributes its value to the distinct set.
    @Test
    public void test_distinct_includes_pending_new_value() throws IOException {
        addDoc(TestGlobals.COLL, "1", "color", new JsonString("red"));
        addDoc(TestGlobals.COLL, "2", "color", new JsonString("blue"));
        enableIndex(TestGlobals.COLL, "color");
        addPendingDoc(TestGlobals.COLL, "3", "color", new JsonString("green"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var values = AggregationOperationHelper.processAggregation(req).stream()
                .map(o -> o.get("color").asJsonString().getValue()).collect(Collectors.toSet());
        assertEquals(Set.of("red", "blue", "green"), values);
    }

    // When a pending update empties an index value, that value no longer surfaces as distinct.
    @Test
    public void test_distinct_drops_emptied_value_after_pending_update() throws IOException {
        addDoc(TestGlobals.COLL, "1", "color", new JsonString("red"));
        enableIndex(TestGlobals.COLL, "color");
        // Doc 1 (the only "red") is updated to "blue" but not yet indexed.
        addPendingDoc(TestGlobals.COLL, "1", "color", new JsonString("blue"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new DistinctAggregationStep("color")));
        final var values = AggregationOperationHelper.processAggregation(req).stream()
                .map(o -> o.get("color").asJsonString().getValue()).collect(Collectors.toSet());
        assertEquals(Set.of("blue"), values);
    }

    // ── GROUP_BY ───────────────────────────────────────────────────────────--

    // Pending documents are grouped under their current value (merged into existing groups and added
    // as new groups).
    @Test
    public void test_group_by_includes_pending_docs() throws IOException {
        addDoc(TestGlobals.COLL, "1", "type", new JsonString("A"));
        enableIndex(TestGlobals.COLL, "type");
        addPendingDoc(TestGlobals.COLL, "2", "type", new JsonString("A"));
        addPendingDoc(TestGlobals.COLL, "3", "type", new JsonString("B"));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new GroupByAggregationStep("type")));
        final var groups = AggregationOperationHelper.processAggregation(req);
        final var sizesByType = groups.stream().collect(Collectors.toMap(o -> o.get("type").asJsonString().getValue(),
                o -> o.get("group").asJsonArray().size()));
        assertEquals(2, sizesByType.get("A"));
        assertEquals(1, sizesByType.get("B"));
    }

    // A pending document whose value is non-scalar (object) forces a full-scan fallback, which still
    // produces consistent groups.
    @Test
    public void test_group_by_falls_back_to_scan_for_non_scalar_pending() throws IOException {
        addDoc(TestGlobals.COLL, "1", "type", new JsonString("A"));
        enableIndex(TestGlobals.COLL, "type");
        final var objValue = new JsonObject();
        objValue.addProperty("nested", 1);
        addPendingDoc(TestGlobals.COLL, "2", "type", objValue);

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new GroupByAggregationStep("type")));
        final var groups = AggregationOperationHelper.processAggregation(req);
        // Both the scalar "A" group and the object-valued group are present (scan fallback sees all).
        assertEquals(2, groups.size());
    }

    // ── SORT ───────────────────────────────────────────────────────────────--

    // Pending documents are ordered by their current value alongside indexed documents.
    @Test
    public void test_sort_orders_pending_docs_by_current_value() throws IOException {
        addDoc(TestGlobals.COLL, "mid", "num", new JsonNumber(2));
        enableIndex(TestGlobals.COLL, "num");
        addPendingDoc(TestGlobals.COLL, "low", "num", new JsonNumber(1));
        addPendingDoc(TestGlobals.COLL, "high", "num", new JsonNumber(3));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new SortAggregationStep("num", true)));
        final var ordered = AggregationOperationHelper.processAggregation(req).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
        assertEquals(List.of("low", "mid", "high"), ordered);
    }

    // ── JOIN ───────────────────────────────────────────────────────────────--

    // A pending (not-yet-indexed) remote document is still matched by an index-backed JOIN.
    @Test
    public void test_join_includes_pending_remote_doc() throws IOException {
        final var main = new JsonObject();
        main.add(Globals.PK_FIELD, new JsonString("m1"));
        main.addProperty("ref", 42);
        final var mainEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, main);
        mainEntry.set_id("m1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, mainEntry);

        addDoc(TestGlobals.JOIN_COLL, "j0", "refKey", new JsonNumber(7));
        enableIndex(TestGlobals.JOIN_COLL, "refKey");
        // Remote doc with refKey 42 committed but not yet indexed.
        addPendingDoc(TestGlobals.JOIN_COLL, "j1", "refKey", new JsonNumber(42));

        final var req = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        req.setAggregationSteps(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "refKey", "joined")));
        final var result = AggregationOperationHelper.processAggregation(req);
        assertEquals(1, result.size());
        assertEquals(1, result.getFirst().get("joined").asJsonArray().size());
    }

    // ── evict-on-write convergence (Problem 3) ─────────────────────────────--

    // After the background entity event runs, the index is rewritten + cache evicted and the pending
    // mark cleared, so the document is found via the index alone (no longer via the overlay).
    @Test
    public void test_background_indexing_converges_and_clears_pending() throws IOException, InterruptedException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("active"));
        enableIndex(TestGlobals.COLL, "status");
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("2"));
        obj.addProperty("status", "active");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id("2");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        pending.mark(TestGlobals.DB, TestGlobals.COLL, "2");

        runEntityEvent(entry);

        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        assertEquals(Set.of("1", "2"), filterIds(new JsonString("active")));
    }

    private void runEntityEvent(DbEntry entry) throws IOException, InterruptedException {
        EventProcessorHelper.processEvent(new EntityEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, entry));
    }

    // ── DELETE (Finding 1) ─────────────────────────────────────────────────--

    private void saveViaProcessor(OperationProcessor processor, String id, String value) {
        final var save = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty("status", value);
        save.setObject(obj);
        // Mirror RequestParser, which derives the request _id from the object's PK so an existing id
        // is recognised as an update (without it, every save degrades to an insert).
        save.set_id(id);
        processor.processMessage(save);
    }

    // A deleted document must be immediately absent from the index-only paths that do not re-fetch
    // documents (COUNT, DISTINCT), not just from FILTER — because the delete marks it pending until the
    // async index removal completes.
    @Test
    public void test_delete_is_consistent_for_count_and_distinct() throws IOException {
        final var processor = new OperationProcessor();
        saveViaProcessor(processor, "gone", "gone");
        saveViaProcessor(processor, "stay", "stay");
        enableIndex(TestGlobals.COLL, "status");
        // Simulate the saves' background indexing having completed, to isolate the delete's effect.
        pending.clear(TestGlobals.DB, TestGlobals.COLL, "gone");
        pending.clear(TestGlobals.DB, TestGlobals.COLL, "stay");

        final var del = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        del.set_id("gone");
        processor.processMessage(del);

        // The delete marked the id pending (its async index removal has not run in this test).
        assertTrue(pending.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("gone"));
        // FILTER already excluded it; COUNT and DISTINCT must too.
        assertTrue(filterIds(new JsonString("gone")).isEmpty());

        final var countReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        countReq.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("gone"))),
                new CountAggregationStep()));
        assertEquals(0L, AggregationOperationHelper.processAggregation(countReq).getFirst().get("count").asJsonNumber()
                .getValue().longValue());

        final var distinctReq = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        distinctReq.setAggregationSteps(List.of(new DistinctAggregationStep("status")));
        final var values = AggregationOperationHelper.processAggregation(distinctReq).stream()
                .map(o -> o.get("status").asJsonString().getValue()).collect(Collectors.toSet());
        assertEquals(Set.of("stay"), values);
    }

    // ── order-independent re-read (Finding 2) ──────────────────────────────--

    // Index maintenance indexes the CURRENT committed document, not a (possibly stale) event snapshot.
    @Test
    public void test_update_indexes_uses_current_doc_not_snapshot() throws IOException, InterruptedException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("B"));
        enableIndex(TestGlobals.COLL, "status");
        // The document's current value moves to C; maintenance must index C, not the old B.
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("C"));

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");

        assertEquals(Set.of("1"), filterIds(new JsonString("C")));
        assertTrue(filterIds(new JsonString("B")).isEmpty());
    }

    // When the document no longer exists (deleted), maintenance removes it from the index.
    @Test
    public void test_update_indexes_removes_when_doc_absent() throws IOException, InterruptedException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("active"));
        enableIndex(TestGlobals.COLL, "status");
        // Simulate a committed delete: gone from cache (and never in the PK index in this test).
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "1");

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");

        assertTrue(filterIds(new JsonString("active")).isEmpty());
    }

    // Re-applying maintenance for the same id (simulating an older event running last) converges to the
    // current value rather than regressing to a stale one.
    @Test
    public void test_update_indexes_is_idempotent_across_reorder() throws IOException, InterruptedException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("old"));
        enableIndex(TestGlobals.COLL, "status");
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("new"));

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");

        assertEquals(Set.of("1"), filterIds(new JsonString("new")));
        assertTrue(filterIds(new JsonString("old")).isEmpty());
    }

    // bulkUpdateIndexes upserts present ids and removes absent (deleted) ids in one pass.
    @Test
    public void test_bulk_update_indexes_mixed_present_and_absent() throws IOException, InterruptedException {
        addDoc(TestGlobals.COLL, "p", "status", new JsonString("present"));
        addDoc(TestGlobals.COLL, "g", "status", new JsonString("gone"));
        enableIndex(TestGlobals.COLL, "status");
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "g");

        IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("p", "g"));

        assertEquals(Set.of("p"), filterIds(new JsonString("present")));
        assertTrue(filterIds(new JsonString("gone")).isEmpty());
    }

    // A collection dropped while its index event is still queued must not crash background maintenance:
    // getIndexesForCollection returns empty for the missing collection, so updateIndexes/bulkUpdateIndexes
    // are clean no-ops instead of throwing an NPE.
    @Test
    public void test_update_indexes_is_noop_for_missing_collection() {
        assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, "nonexistent_coll", "1"));
        assertDoesNotThrow(() -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, "nonexistent_coll", List.of("1", "2")));
    }

    // ── CREATE_INDEX / DROP_INDEX synchronous registration (Finding 3) ───────--

    private Set<String> indexesOf() {
        return cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).getIndexes();
    }

    // CREATE_INDEX builds the index files and registers the field as a known index synchronously
    // (under the collection write lock), so the field is usable the instant the request returns —
    // no background event is required. Documents committed before the build are captured.
    @Test
    public void test_create_index_registers_field_and_indexes_existing_docs_synchronously() throws IOException {
        final var processor = new OperationProcessor();
        saveViaProcessor(processor, "1", "active");
        saveViaProcessor(processor, "2", "inactive");

        final var resp = processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "status"));

        assertEquals(OperationStatus.OK, resp.getStatus());
        // Registered synchronously — no IndexEvent processed in this test.
        assertTrue(indexesOf().contains("status"));
        // The pre-existing documents are present in the freshly built index.
        assertEquals(Set.of("1"), filterIds(new JsonString("active")));
        assertEquals(Set.of("2"), filterIds(new JsonString("inactive")));
    }

    // DROP_INDEX deletes the index files and unregisters the field synchronously, so the field is no
    // longer a known index the instant the request returns.
    @Test
    public void test_drop_index_unregisters_field_synchronously() {
        final var processor = new OperationProcessor();
        saveViaProcessor(processor, "1", "active");
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, TestGlobals.COLL, "status"));
        assertTrue(indexesOf().contains("status"));

        final var resp = processor.processMessage(new DropIndexRequest(TestGlobals.DB, TestGlobals.COLL, "status"));

        assertEquals(OperationStatus.OK, resp.getStatus());
        assertFalse(indexesOf().contains("status"));
    }

    // End-to-end: a delete and an update on a collection keep the in-memory PK index positions
    // consistent (OperationProcessor applies the compaction returned by the FileSystem), so survivors
    // still read back correctly from disk (positioned reads) after their documents are evicted.
    @Test
    public void test_delete_and_update_keep_positions_consistent() {
        final var processor = new OperationProcessor();
        saveViaProcessor(processor, "a", "alpha");
        saveViaProcessor(processor, "b", "beta");
        saveViaProcessor(processor, "c", "gamma");

        final var del = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        del.set_id("a");
        processor.processMessage(del);
        saveViaProcessor(processor, "b", "beta2"); // update -> compacts the page, shifts "c"

        // Force positioned reads from disk for the survivors.
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "b");
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "c");

        assertEquals("gamma", readStatus(processor, "c"));
        assertEquals("beta2", readStatus(processor, "b"));
    }

    // A BULK_SAVE that updates the lexicographically-smallest existing _id (PK-index slot 0) must be
    // recognised as an update, not inserted as a duplicate (regression for the binarySearch > 0 bug).
    @Test
    public void test_bulk_save_updates_smallest_id_without_duplicate() throws Exception {
        final var processor = new OperationProcessor();
        saveViaProcessor(processor, "a", "alpha");
        saveViaProcessor(processor, "b", "beta");
        saveViaProcessor(processor, "c", "gamma");

        final var bulk = new org.techhouse.ops.req.BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("a"));
        obj.addProperty("status", "alpha2");
        bulk.setObjects(List.of(obj));
        final var resp = (org.techhouse.ops.resp.BulkSaveResponse) processor.processMessage(bulk);

        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals(List.of("a"), resp.getUpdated(), "smallest id must be treated as an update");
        assertTrue(resp.getInserted().isEmpty(), "must not insert a duplicate");
        // No duplicate PK entry: exactly three ids remain.
        assertEquals(3, cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size());
        assertEquals("alpha2", readStatus(processor, "a"));
    }

    private String readStatus(OperationProcessor processor, String id) {
        final var req = new org.techhouse.ops.req.FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        req.set_id(id);
        final var resp = (org.techhouse.ops.resp.FindByIdResponse) processor.processMessage(req);
        return resp.getObject().get("status").asJsonString().getValue();
    }

    // ── UserCache helpers ────────────────────────────────────────────────────

    // evictFieldIndexAllTypes drops every per-type list for a field while leaving other fields cached.
    @Test
    public void test_evict_field_index_all_types_removes_only_the_field() throws Exception {
        final var userCache = IocContainer.get(UserCache.class);
        final var token = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(userCache, "fieldIndexMap", token);
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        final Map<String, List<FieldIndexEntry<?>>> inner = new ConcurrentHashMap<>();
        inner.put("mixed" + Globals.COLL_IDENTIFIER_SEPARATOR + "Double", new ArrayList<>());
        inner.put("mixed" + Globals.COLL_IDENTIFIER_SEPARATOR + "String", new ArrayList<>());
        inner.put("other" + Globals.COLL_IDENTIFIER_SEPARATOR + "Double", new ArrayList<>());
        fieldIndexMap.put(collId, inner);

        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, "mixed");

        assertEquals(Set.of("other" + Globals.COLL_IDENTIFIER_SEPARATOR + "Double"), inner.keySet());
    }

    // When the last field is evicted, the collection's index map entry is removed entirely.
    @Test
    public void test_evict_field_index_all_types_removes_empty_collection_entry() throws Exception {
        final var userCache = IocContainer.get(UserCache.class);
        final var token = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(userCache, "fieldIndexMap", token);
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        final Map<String, List<FieldIndexEntry<?>>> inner = new ConcurrentHashMap<>();
        inner.put("only" + Globals.COLL_IDENTIFIER_SEPARATOR + "Double", new ArrayList<>());
        fieldIndexMap.put(collId, inner);

        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, "only");

        assertFalse(fieldIndexMap.containsKey(collId));
    }

    // getIdsFromIndex returns a detached snapshot that does not alias cached state.
    @Test
    public void test_get_ids_from_index_returns_detached_snapshot() throws IOException {
        addDoc(TestGlobals.COLL, "1", "status", new JsonString("active"));
        enableIndex(TestGlobals.COLL, "status");
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        final var first = cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, "status", operator, "active");
        first.add("injected");
        final var second = cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, "status", operator, "active");
        assertEquals(Set.of("1"), second);
    }
}
