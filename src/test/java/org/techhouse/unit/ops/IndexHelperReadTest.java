package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperReadTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private DbEntry entryWith(String id, String field, JsonBaseElement value) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        e.set_id(id);
        return e;
    }

    private void setupCollection(Cache cache, DbEntry... entries) {
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var pk = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, pk);
        for (var entry : entries) {
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
    }

    // getIndexEntriesForField returns null when the field has no index (caller falls back to scan)
    @Test
    public void test_get_index_entries_for_field_returns_null_when_no_index() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        DbEntry entry = entryWith("n1", "tag", new JsonString("alpha"));
        setupCollection(cache, entry);
        // No index created on "tag"
        assertNull(IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "tag"));
    }

    // getIndexEntriesForField returns all entries (value -> ids) for an indexed field
    @Test
    public void test_get_index_entries_for_field_returns_entries_when_indexed() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("n1", "tag", new JsonString("alpha")),
                entryWith("n2", "tag", new JsonString("beta")), entryWith("n3", "tag", new JsonString("alpha")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tag");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("tag"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "tag");
        assertNotNull(entries);
        // Two distinct values: alpha (ids n1, n3) and beta (id n2)
        assertEquals(2, entries.size());
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("n1", "n2", "n3"), allIds);
    }

    private static JsonObject objectValue(int n) {
        final var val = new JsonObject();
        val.addProperty("n", n);
        return val;
    }

    private static JsonArray arrayValue(String... items) {
        final var arr = new JsonArray();
        for (var item : items) {
            arr.add(item);
        }
        return arr;
    }

    // getIndexEntriesForField on a mixed scalar+object field returns entries for all docs
    @Test
    public void test_getIndexEntriesForField_mixed_scalar_and_object_includes_all_docs() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("s1", "data", new JsonString("hello")),
                entryWith("s2", "data", new JsonString("world")), entryWith("o1", "data", objectValue(1)),
                entryWith("o2", "data", objectValue(2)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "data");
        assertNotNull(entries);
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("s1", "s2", "o1", "o2"), allIds);
        // The object-valued entries carry actual JsonObject values, not hash strings
        final var hasObjectEntry = entries.stream()
                .anyMatch(e -> e.getValue() instanceof JsonBaseElement el && el.isJsonObject());
        assertTrue(hasObjectEntry);
    }

    // getIndexEntriesForField on a mixed scalar+array field returns entries for all docs
    @Test
    public void test_getIndexEntriesForField_mixed_scalar_and_array_includes_all_docs() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("s1", "data", new JsonNumber(42)),
                entryWith("a1", "data", arrayValue("x", "y")), entryWith("a2", "data", arrayValue("z")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "data");
        assertNotNull(entries);
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("s1", "a1", "a2"), allIds);
    }

    // getIndexEntriesForField on a pure scalar field still returns scalar entries (regression)
    @Test
    public void test_getIndexEntriesForField_pure_scalar_field_unchanged() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("n1", "score", new JsonNumber(10)),
                entryWith("n2", "score", new JsonNumber(20)), entryWith("n3", "score", new JsonNumber(10)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "score");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("score"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "score");
        assertNotNull(entries);
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("n1", "n2", "n3"), allIds);
    }

    // getIndexEntriesForField on a pure object field returns actual-value entries (not null)
    @Test
    public void test_getIndexEntriesForField_pure_object_field_returns_entries() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("o1", "data", objectValue(1)), entryWith("o2", "data", objectValue(1)),
                entryWith("o3", "data", objectValue(2)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "data");
        assertNotNull(entries);
        // Two distinct object values: {n:1} (ids o1, o2) and {n:2} (id o3)
        assertEquals(2, entries.size());
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("o1", "o2", "o3"), allIds);
    }

    // getIndexEntriesForField groups docs with identical object values into one entry
    @Test
    public void test_getIndexEntriesForField_same_object_value_grouped_into_one_entry() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("o1", "data", objectValue(5)), entryWith("o2", "data", objectValue(5)),
                entryWith("o3", "data", objectValue(5)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "data");
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(Set.of("o1", "o2", "o3"), entries.getFirst().getIds());
    }

    // getMatchingIdsForJoin returns null when the remote field has no index (caller falls back to scan)
    @Test
    public void test_get_matching_ids_for_join_returns_null_when_no_index() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("r1", "refKey", new JsonNumber(1)));
        // No index created on "refKey"
        final var result = IndexHelper.getMatchingIdsForJoin(TestGlobals.DB, TestGlobals.COLL, "refKey",
                Set.of(new JsonNumber(1)));
        assertNull(result);
    }

    // getMatchingIdsForJoin returns only the ids whose remote field matches a local value
    @Test
    public void test_get_matching_ids_for_join_returns_matching_ids() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("r1", "refKey", new JsonNumber(42)),
                entryWith("r2", "refKey", new JsonNumber(7)), entryWith("r3", "refKey", new JsonNumber(42)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "refKey");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("refKey"));

        final var result = IndexHelper.getMatchingIdsForJoin(TestGlobals.DB, TestGlobals.COLL, "refKey",
                Set.of(new JsonNumber(42)));

        assertNotNull(result);
        assertEquals(Set.of("r1", "r3"), result);
    }

    // getIndexEntriesForField throws IOException when the calling thread is interrupted while
    // blocked acquiring the field's index read lock (blocked here by a write lock held elsewhere)
    @Test
    public void test_getIndexEntriesForField_interrupted_while_acquiring_read_lock() throws Exception {
        String fieldName = "lockedField";
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("l1", fieldName, new JsonString("v")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, fieldName);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(fieldName));

        final var rl = IocContainer.get(org.techhouse.concurrency.ResourceLocking.class);
        rl.lockIndex(TestGlobals.DB, TestGlobals.COLL, fieldName);
        try {
            final var caught = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            final var readyLatch = new java.util.concurrent.CountDownLatch(1);
            final var reader = new Thread(() -> {
                readyLatch.countDown();
                try {
                    IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, fieldName);
                } catch (Throwable t) {
                    caught.set(t);
                }
            });
            reader.start();
            readyLatch.await();
            Thread.sleep(200);
            reader.interrupt();
            reader.join(2000);

            assertInstanceOf(IOException.class, caught.get());
            assertInstanceOf(InterruptedException.class, caught.get().getCause());
        } finally {
            rl.releaseIndex(TestGlobals.DB, TestGlobals.COLL, fieldName);
        }
    }

    // getIndexEntriesForField records the field as index-used and its read lock as acquired when an
    // analyze context is active on the calling thread
    @Test
    public void test_getIndexEntriesForField_records_analyze_context() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("a1", "tag", new JsonString("alpha")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "tag");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("tag"));

        final var analyzeContext = new org.techhouse.analyze.AnalyzeContext();
        org.techhouse.analyze.AnalyzeContext.set(analyzeContext);
        try {
            final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "tag");
            assertNotNull(entries);
            assertTrue(analyzeContext.getIndexesUsed().contains("tag"));
            assertTrue(analyzeContext.getLocksAcquired().contains(
                    org.techhouse.analyze.AnalyzeContext.fieldLockId(TestGlobals.DB, TestGlobals.COLL, "tag")));
        } finally {
            org.techhouse.analyze.AnalyzeContext.clear();
        }
    }

    // addHashIndexEntries skips a hash-matched id whose current document no longer has the field, or
    // whose value changed from object to a scalar without the index having been updated yet (the
    // background-processing lag the object/array hash index tolerates)
    @Test
    public void test_getIndexEntriesForField_hash_index_skips_stale_entries() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("keep1", "data", objectValue(1)), entryWith("gone1", "data", objectValue(2)),
                entryWith("scalarNow1", "data", objectValue(3)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("data"));

        final var withoutField = new JsonObject();
        withoutField.add(Globals.PK_FIELD, new JsonString("gone1"));
        final var goneEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, withoutField);
        goneEntry.set_id("gone1");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, goneEntry);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL,
                entryWith("scalarNow1", "data", new JsonString("no-longer-an-object")));

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "data");
        assertNotNull(entries);
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(allIds.contains("keep1"));
        assertFalse(allIds.contains("gone1"));
        assertFalse(allIds.contains("scalarNow1"));
    }

    // getMatchingIdsForJoin skips null-valued and object-valued local join keys, matching only the
    // scalar value against the remote index
    @Test
    public void test_get_matching_ids_for_join_skips_null_and_object_values() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("r1", "refKey", new JsonNumber(42)),
                entryWith("r2", "refKey", new JsonNumber(7)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "refKey");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("refKey"));

        final var localValues = new java.util.HashSet<JsonBaseElement>();
        localValues.add(JsonNull.INSTANCE);
        localValues.add(new JsonObject());
        localValues.add(new JsonNumber(42));

        final var result = IndexHelper.getMatchingIdsForJoin(TestGlobals.DB, TestGlobals.COLL, "refKey", localValues);

        assertNotNull(result);
        assertEquals(Set.of("r1"), result);
    }
}
