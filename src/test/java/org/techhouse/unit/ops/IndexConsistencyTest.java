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
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

// Covers the index read/write consistency layer: the PendingIndexWrites overlay (no false positives
// or negatives across FILTER/COUNT/GROUP_BY/SORT/DISTINCT/JOIN), evict-on-write convergence, and the
// UserCache snapshot/eviction helpers.
public class IndexConsistencyTest {
    private static final String STATUS = "status";
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

    private void addDoc(String id, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(STATUS, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex() {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, STATUS);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(STATUS));
    }

    private Set<String> filterIds(JsonBaseElement value) throws IOException {
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "status", value);
        return FilterOperatorHelper.processOperator(operator, null, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).collect(Collectors.toSet());
    }

    // ── evict-on-write convergence (Problem 3) ─────────────────────────────--

    // After the background entity event runs, the index is rewritten + cache evicted and the pending
    // mark cleared, so the document is found via the index alone (no longer via the overlay).
    @Test
    public void test_background_indexing_converges_and_clears_pending() throws IOException, InterruptedException {
        addDoc("1", new JsonString("active"));
        enableIndex();
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

    // ── order-independent re-read (Finding 2) ──────────────────────────────--

    // Index maintenance indexes the CURRENT committed document, not a (possibly stale) event snapshot.
    @Test
    public void test_update_indexes_uses_current_doc_not_snapshot() throws IOException, InterruptedException {
        addDoc("1", new JsonString("B"));
        enableIndex();
        // The document's current value moves to C; maintenance must index C, not the old B.
        addDoc("1", new JsonString("C"));

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");

        assertEquals(Set.of("1"), filterIds(new JsonString("C")));
        assertTrue(filterIds(new JsonString("B")).isEmpty());
    }

    // When the document no longer exists (deleted), maintenance removes it from the index.
    @Test
    public void test_update_indexes_removes_when_doc_absent() throws IOException, InterruptedException {
        addDoc("1", new JsonString("active"));
        enableIndex();
        // Simulate a committed delete: gone from cache (and never in the PK index in this test).
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "1");

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");

        assertTrue(filterIds(new JsonString("active")).isEmpty());
    }

    // Re-applying maintenance for the same id (simulating an older event running last) converges to the
    // current value rather than regressing to a stale one.
    @Test
    public void test_update_indexes_is_idempotent_across_reorder() throws IOException, InterruptedException {
        addDoc("1", new JsonString("old"));
        enableIndex();
        addDoc("1", new JsonString("new"));

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "1");

        assertEquals(Set.of("1"), filterIds(new JsonString("new")));
        assertTrue(filterIds(new JsonString("old")).isEmpty());
    }

    // bulkUpdateIndexes upserts present ids and removes absent (deleted) ids in one pass.
    @Test
    public void test_bulk_update_indexes_mixed_present_and_absent() throws IOException, InterruptedException {
        addDoc("p", new JsonString("present"));
        addDoc("g", new JsonString("gone"));
        enableIndex();
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
        addDoc("1", new JsonString("active"));
        enableIndex();
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        final var first = cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, "status", operator, "active");
        first.add("injected");
        final var second = cache.getIdsFromIndex(TestGlobals.DB, TestGlobals.COLL, "status", operator, "active");
        assertEquals(Set.of("1"), second);
    }
}
