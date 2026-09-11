package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class UserCacheIndexTest {
    // Mockito mocks skip field initializers, so the real getIdsFromIndex (called via thenCallRealMethod)
    // would see a null index lock. Inject a real ResourceLocking so the read lock can be acquired.
    private static void injectRealLocking(UserCache mock) {
        try {
            final var rlField = UserCache.class.getDeclaredField("rl");
            rlField.setAccessible(true);
            rlField.set(mock, new ResourceLocking());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    // Retrieving field index loads data from the file system if not present in cache
    @Test
    public void test_retrieving_field_index_loads_data()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Mocking FileSystem and setting up necessary data
        FileSystem fsMock = mock(FileSystem.class);
        UserCache cache = new UserCache();
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "test_db";
        String collName = "test_coll";
        String fieldName = "test_field";

        List<FieldIndexEntry<Double>> fieldIndexEntries = new ArrayList<>();
        fieldIndexEntries.add(new FieldIndexEntry<>(dbName, collName, 1.5, new HashSet<>(List.of("1", "2"))));

        when(fsMock.readWholeFieldIndexFiles(dbName, collName, fieldName, Double.class)).thenReturn(fieldIndexEntries);

        // Calling the method under test
        List<FieldIndexEntry<Double>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                Double.class);

        // Assertions
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1.5, result.getFirst().getValue());
    }

    @Test
    public void test_returns_primary_key_index_from_cache()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        List<PkIndexEntry> expectedPkIndex = List.of(new PkIndexEntry(dbName, collName, "value1", 0, 10, 0));
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(collectionIdentifier, expectedPkIndex);

        // Act
        List<PkIndexEntry> actualPkIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);

        // Assert
        assertEquals(expectedPkIndex, actualPkIndex);
    }

    // shiftPkPositionsAfterCompaction decrements only same-page entries with a greater position, by
    // the removed length, in place; other pages and earlier entries are untouched.
    @Test
    public void test_shift_pk_positions_after_compaction() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        final var before = new PkIndexEntry(dbName, collName, "a", 0, 10, 0);
        final var removed = new PkIndexEntry(dbName, collName, "b", 10, 10, 0);
        final var after = new PkIndexEntry(dbName, collName, "c", 20, 10, 0);
        final var otherPage = new PkIndexEntry(dbName, collName, "d", 20, 10, 1);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        TestUtils.getPrivateField(cache, "pkIndexMap", type).put(Cache.getCollectionIdentifier(dbName, collName),
                new ArrayList<>(List.of(before, removed, after, otherPage)));

        cache.shiftPkPositionsAfterCompaction(dbName, collName, 0, removed.getPosition(), removed.getLength());

        assertEquals(0, before.getPosition(), "entry before the removed position is untouched");
        assertEquals(10, removed.getPosition(), "the entry at the removed position itself is untouched");
        assertEquals(10, after.getPosition(), "entry after the removed position shifts left by removed length");
        assertEquals(20, otherPage.getPosition(), "entry on a different page is untouched");
    }

    // No-op when the collection's PK index is not cached (does not throw).
    @Test
    public void test_shift_pk_positions_after_compaction_uncached_is_noop() {
        UserCache cache = new UserCache();
        assertDoesNotThrow(() -> cache.shiftPkPositionsAfterCompaction("noDb", "noColl", 0, 0, 10));
    }

    // Returns list of FieldIndexEntry when index is already loaded
    @Test
    public void test_returns_list_when_index_loaded() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        UserCache cache = new UserCache();
        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        Class<String> indexType = String.class;
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        String indexIdentifier = Cache.getIndexIdentifier(fieldName, indexType);

        FieldIndexEntry<String> entry = new FieldIndexEntry<>(dbName, collName, "value", Set.of("id1", "id2"));
        Map<String, List<FieldIndexEntry<?>>> indexMap = new ConcurrentHashMap<>();
        indexMap.put(indexIdentifier, List.of(entry));

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);
        fieldIndexMap.put(collectionIdentifier, indexMap);

        // Act
        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                indexType);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("value", result.getFirst().getValue());
    }

    // Handles empty or null field index map
    @Test
    public void test_handles_empty_or_null_field_index_map()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        // Changing the accessibility of fs for testing
        Field fsField = cache.getClass().getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "testDB";
        String collName = "testCollection";
        String fieldName = "testField";
        Class<String> indexType = String.class;

        when(fsMock.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType)).thenReturn(null);

        // Act
        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                indexType);

        // Assert
        assertNull(result);
    }

    // A cached key for field "ba" (ba|String) must not prevent loading field "a" (a|String)
    @Test
    public void test_getFieldIndex_suffix_collision_loads_from_disk()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "testDB";
        String collName = "testCollection";
        String collId = Cache.getCollectionIdentifier(dbName, collName);

        // Pre-populate the cache with field "ba" so its key "ba|String" is present
        Map<String, List<FieldIndexEntry<?>>> innerMap = new ConcurrentHashMap<>();
        innerMap.put(Cache.getIndexIdentifier("ba", String.class),
                List.of(new FieldIndexEntry<>(dbName, collName, "x", Set.of("id99"))));
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        TestUtils.getPrivateField(cache, "fieldIndexMap", type).put(collId, innerMap);

        List<FieldIndexEntry<String>> diskEntries = List
                .of(new FieldIndexEntry<>(dbName, collName, "hello", Set.of("id1")));
        when(fsMock.readWholeFieldIndexFiles(dbName, collName, "a", String.class)).thenReturn(diskEntries);

        List<FieldIndexEntry<String>> result = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, "a",
                String.class);

        verify(fsMock).readWholeFieldIndexFiles(dbName, collName, "a", String.class);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("hello", result.getFirst().getValue());
    }

    // A cached key for field "ba" (ba|Object) must not prevent loading field "a" (a|Object)
    @Test
    public void test_getHashIndex_suffix_collision_loads_from_disk()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        Field fsField = UserCache.class.getDeclaredField("fs");
        fsField.setAccessible(true);
        fsField.set(cache, fsMock);

        String dbName = "testDB";
        String collName = "testCollection";
        String collId = Cache.getCollectionIdentifier(dbName, collName);

        // Pre-populate the cache with field "ba" so its key "ba|Object" is present
        Map<String, List<FieldIndexEntry<?>>> innerMap = new ConcurrentHashMap<>();
        innerMap.put(Cache.getIndexIdentifier("ba", IndexKind.OBJECT.label()),
                List.of(new FieldIndexEntry<>(dbName, collName, "deadbeef", Set.of("id99"))));
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        TestUtils.getPrivateField(cache, "fieldIndexMap", type).put(collId, innerMap);

        List<FieldIndexEntry<String>> diskEntries = List
                .of(new FieldIndexEntry<>(dbName, collName, "cafebabe", Set.of("id1")));
        when(fsMock.readWholeHashIndexFile(dbName, collName, "a", IndexKind.OBJECT)).thenReturn(diskEntries);

        List<FieldIndexEntry<String>> result = cache.getHashIndexAndLoadIfNecessary(dbName, collName, "a",
                IndexKind.OBJECT);

        verify(fsMock).readWholeHashIndexFile(dbName, collName, "a", IndexKind.OBJECT);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("cafebabe", result.getFirst().getValue());
    }

    // Returns true when the field index is present in the fieldIndexMap
    @Test
    public void test_returns_true_when_field_index_present() throws NoSuchFieldException, IllegalAccessException {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";
        String collectionIdentifier = Cache.getCollectionIdentifier(dbName, collName);

        Map<String, List<FieldIndexEntry<?>>> fieldIndexes = new HashMap<>();
        fieldIndexes.put(fieldName, new ArrayList<>());

        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", type);

        fieldIndexMap.put(collectionIdentifier, fieldIndexes);

        boolean result = cache.hasLoadedIndex(dbName, collName, fieldName);

        assertTrue(result);
    }

    // Returns false when fieldIndexMap is empty
    @Test
    public void test_returns_false_when_field_index_map_empty() {
        UserCache cache = new UserCache();
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";

        boolean result = cache.hasLoadedIndex(dbName, collName, fieldName);

        assertFalse(result);
    }

    // getIdsFromIndex with a JsonCustom value returns results from custom index
    @Test
    public void test_get_ids_from_index_with_custom_type() throws IOException {
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "time";
        var timeValue = new JsonTime("#time(10:00:00)");
        var operator = new FieldOperator(FieldOperatorType.EQUALS, fieldName, timeValue);
        List<FieldIndexEntry<Object>> idx = List.of(new FieldIndexEntry<>(dbName, collName, timeValue, Set.of("id1")));
        when(cache.getFieldIndexAndLoadIfNecessary(eq(dbName), eq(collName), eq(fieldName), any())).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, (Object) timeValue)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, (Object) timeValue);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
    }

    // getIdsFromIndex with JsonArray containing JsonString elements
    @Test
    public void test_get_ids_from_index_with_json_array_of_strings() throws IOException {
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "tag";
        var arr = new JsonArray();
        arr.add(new JsonString("alpha"));
        arr.add(new JsonString("beta"));
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        var idx = List.of(new FieldIndexEntry<>(dbName, collName, "alpha", Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, "gamma", Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertFalse(result.contains("id2"));
    }

    // getIdsFromIndex with JsonArray containing JsonNumber elements
    @Test
    public void test_get_ids_from_index_with_json_array_of_numbers() throws IOException {
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "score";
        var arr = new JsonArray();
        arr.add(new JsonNumber(10.0));
        arr.add(new JsonNumber(20.0));
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        List<FieldIndexEntry<Number>> idx = List.of(new FieldIndexEntry<>(dbName, collName, 10.0, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, 30.0, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertFalse(result.contains("id2"));
    }

    // getIdsFromIndex with JsonArray containing JsonBoolean elements
    @Test
    public void test_get_ids_from_index_with_json_array_of_booleans() throws IOException {
        var cache = mock(UserCache.class);
        injectRealLocking(cache);
        var dbName = "db";
        var collName = "coll";
        var fieldName = "active";
        var arr = new JsonArray();
        arr.add(new JsonBoolean(true));
        var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        var idx = List.of(new FieldIndexEntry<>(dbName, collName, true, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, false, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertFalse(result.contains("id2"));
    }

    // getIdsFromIndex with JsonArray containing a non-primitive first element returns null
    @Test
    public void test_get_ids_from_index_with_json_array_non_primitive_returns_null() throws IOException {
        var cache = new UserCache();
        var arr = new JsonArray();
        JsonObject nested = new JsonObject();
        nested.addProperty("key", "value");
        arr.add(nested);
        var operator = new FieldOperator(FieldOperatorType.IN, "field", arr);

        var result = cache.getIdsFromIndex("db", "coll", "field", operator, arr);

        assertNull(result);
    }

    @Test
    public void test_listCacheableResources_includes_pk_and_field_indexes() throws Exception {
        UserCache cache = IocContainer.get(UserCache.class);
        final var pkType = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", pkType);
        pkIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"),
                List.of(new PkIndexEntry("db1", "c1", "id1", 0, 1, 0)));
        final var fieldType = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var fieldIndexMap = TestUtils.getPrivateField(cache, "fieldIndexMap", fieldType);
        final Map<String, List<FieldIndexEntry<?>>> indexes = new ConcurrentHashMap<>();
        indexes.put("f|String", List.of(new FieldIndexEntry<>("db1", "c1", "v", Set.of("id1"))));
        fieldIndexMap.put(Cache.getCollectionIdentifier("db1", "c1"), indexes);
        final var resources = cache.listCacheableResources();
        assertTrue(resources.stream().anyMatch(r -> r.kind() == org.techhouse.cache.AccessKind.PK_INDEX));
        assertTrue(resources.stream().anyMatch(r -> r.kind() == org.techhouse.cache.AccessKind.FIELD_INDEX));
    }

    // ── getEntriesByIds / streamCollection (page-streaming read path) ─────────

    private static void injectPkIndex(UserCache cache, String collId, List<PkIndexEntry> entries)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var pkIndexMap = TestUtils.getPrivateField(cache, "pkIndexMap", type);
        pkIndexMap.put(collId, new ArrayList<>(entries));
    }

    @Test
    public void test_getEntriesByIds_skips_ids_not_in_pk_index() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        injectPkIndex(cache, collId, List.of(new PkIndexEntry("userDb", "c1", "id1", 0, 50, 0)));

        final var result = cache.getEntriesByIds("userDb", "c1", Set.of("missing"));

        // An id absent from the PK index resolves to nothing, so no targeted read happens.
        assertTrue(result.isEmpty());
        verify(fsMock, never()).getByIndexEntries(anyList());
    }

    // getEntriesByIds must pass a detached PkIndexEntry copy to FileSystem so a concurrent
    // shiftPkPositionsAfterCompaction cannot move the offset between resolution and the read.
    @Test
    public void test_getEntriesByIds_returns_detached_pk_copy() throws Exception {
        UserCache cache = new UserCache();
        FileSystem fsMock = mock(FileSystem.class);
        TestUtils.setPrivateField(cache, "fs", fsMock);

        final var collId = Cache.getCollectionIdentifier("userDb", "c1");
        final var livePk = new PkIndexEntry("userDb", "c1", "id1", 100L, 50, 0);
        injectPkIndex(cache, collId, List.of(livePk));

        final var readObj = new JsonObject();
        readObj.addProperty(Globals.PK_FIELD, "id1");
        when(fsMock.getByIndexEntries(anyList())).thenReturn(List.of(DbEntry.fromJsonObject("userDb", "c1", readObj)));

        // Simulate a compaction shifting the live entry's position after getEntriesByIds resolves it.
        final var captor = org.mockito.ArgumentCaptor.forClass(List.class);

        cache.getEntriesByIds("userDb", "c1", Set.of("id1"));

        //noinspection unchecked
        verify(fsMock).getByIndexEntries(captor.capture());
        //noinspection unchecked
        final List<PkIndexEntry> requested = captor.getValue();
        assertEquals(1, requested.size());
        // The entry passed to FileSystem must be a different object than the live cached entry.
        assertNotSame(livePk, requested.getFirst());
        // And it must carry the original position (100), not any post-compaction value.
        assertEquals(100L, requested.getFirst().getPosition());

        // Now simulate a compaction that moves the live entry.
        livePk.setPosition(50L);
        // The copy already passed to FileSystem is unaffected.
        assertEquals(100L, requested.getFirst().getPosition());
    }
}
