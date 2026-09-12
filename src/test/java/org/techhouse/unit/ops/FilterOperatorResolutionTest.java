package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterOperatorResolutionTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_process_operator_with_index_returns_indexed_results() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("idx1"));
        obj1.addProperty("score", 100);
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("idx2"));
        obj2.addProperty("score", 200);

        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj1);
        e1.set_id("idx1");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj2);
        e2.set_id("idx2");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e1);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e2);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "idx1", 0, 100, 0));

        org.techhouse.ops.IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "score");
        final var coll = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        coll.setIndexes(java.util.Set.of("score"));

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "score", new JsonNumber(100));
        List<JsonObject> result = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals(100, result.getFirst().get("score").asJsonNumber().asInteger());
    }

    @Test
    public void test_process_operator_with_index_and_existing_stream() throws Exception {
        final var cache = IocContainer.get(Cache.class);

        JsonObject obj1 = new JsonObject();
        obj1.add(Globals.PK_FIELD, new JsonString("is1"));
        obj1.addProperty("level", 5);
        JsonObject obj2 = new JsonObject();
        obj2.add(Globals.PK_FIELD, new JsonString("is2"));
        obj2.addProperty("level", 10);

        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj1);
        e1.set_id("is1");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj2);
        e2.set_id("is2");
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e1);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, e2);
        final var adminCollEntry2 = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        cache.putAdminCollectionEntry(adminCollEntry2,
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "is1", 0, 100, 0));

        org.techhouse.ops.IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "level");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(java.util.Set.of("level"));

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(5));
        java.util.stream.Stream<JsonObject> existing = java.util.stream.Stream.of(obj1, obj2);
        List<JsonObject> result = FilterOperatorHelper.processOperator(op, existing, TestGlobals.DB, TestGlobals.COLL)
                .toList();

        assertEquals(1, result.size());
        assertEquals(5, result.getFirst().get("level").asJsonNumber().asInteger());
    }

    private void addIndexedDoc(Cache cache, String id, JsonBaseElement value) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("status", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void index(Cache cache, String... fields) {
        for (final var field : fields) {
            org.techhouse.ops.IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        }
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(java.util.Set.of(fields));
    }

    // Populates the PK index in the user cache directly so NOR/NAND can compute the full id universe
    // without a real on-disk collection.
    private void injectPkIndex() throws Exception {
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        final var list = new ArrayList<PkIndexEntry>();
        for (final var id : new String[]{"r1", "r2", "r3"}) {
            list.add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, id, 0, 100, 0));
        }
        final var pkMap = new java.util.concurrent.ConcurrentHashMap<String, List<PkIndexEntry>>();
        pkMap.put(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL), list);
        TestUtils.setPrivateField(userCache, "pkIndexMap", pkMap);
    }

    @Test
    public void test_resolve_ids_single_field_returns_index_ids() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addIndexedDoc(cache, "r1", new JsonString("active"));
        addIndexedDoc(cache, "r2", new JsonString("inactive"));
        addIndexedDoc(cache, "r3", new JsonString("active"));
        index(cache, "status");

        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        final var ids = FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL);

        assertEquals(java.util.Set.of("r1", "r3"), ids);
    }

    @Test
    public void test_resolve_ids_unindexed_field_returns_null() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addIndexedDoc(cache, "r1", new JsonString("active"));

        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_and_intersects_child_sets() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 1);
        index(cache, "status", "level");

        final var and = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertEquals(java.util.Set.of("r1"),
                FilterOperatorHelper.resolveIdsViaIndex(and, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_or_unions_child_sets() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 3);
        index(cache, "status", "level");

        final var or = new ConjunctionOperator(ConjunctionOperatorType.OR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(3))));
        assertEquals(java.util.Set.of("r1", "r2", "r3"),
                FilterOperatorHelper.resolveIdsViaIndex(or, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_xor_keeps_exactly_one() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 1);
        index(cache, "status", "level");

        final var xor = new ConjunctionOperator(ConjunctionOperatorType.XOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertEquals(java.util.Set.of("r2", "r3"),
                FilterOperatorHelper.resolveIdsViaIndex(xor, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_nor_complements_against_pk_index() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 3);
        index(cache, "status", "level");
        injectPkIndex();

        final var nor = new ConjunctionOperator(ConjunctionOperatorType.NOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(3))));
        assertEquals(java.util.Set.of(),
                FilterOperatorHelper.resolveIdsViaIndex(nor, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_nand_complements_against_pk_index() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        addTwoFieldDoc(cache, "r2", "active", 2);
        addTwoFieldDoc(cache, "r3", "inactive", 1);
        index(cache, "status", "level");
        injectPkIndex();

        final var nand = new ConjunctionOperator(ConjunctionOperatorType.NAND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertEquals(java.util.Set.of("r2", "r3"),
                FilterOperatorHelper.resolveIdsViaIndex(nand, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_conjunction_with_unindexed_leaf_returns_null() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addTwoFieldDoc(cache, "r1", "active", 1);
        index(cache, "status");

        final var and = new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")),
                        new FieldOperator(FieldOperatorType.EQUALS, "level", new JsonNumber(1))));
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(and, TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_resolve_ids_no_matches_returns_empty_set() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        addIndexedDoc(cache, "r1", new JsonString("active"));
        index(cache, "status");

        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("missing"));
        final var ids = FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    private void addTwoFieldDoc(Cache cache, String id, String status, int level) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.addProperty("status", status);
        obj.addProperty("level", level);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private static JsonObject objField(int n) {
        final var inner = new JsonObject();
        inner.addProperty("n", n);
        return inner;
    }

    private static JsonArray arrField(String... items) {
        final var arr = new JsonArray();
        for (var item : items) {
            arr.add(new JsonString(item));
        }
        return arr;
    }

    // resolveIdsViaIndex disqualifies an object operand (hash hits are unconfirmed candidates), so the
    // index-only COUNT falls back; the document FILTER path still resolves it via the Object hash index.
    @Test
    public void test_resolve_ids_via_index_object_equals_disqualified_but_filter_resolves() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var pk = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "o1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, pk);
        addObjEntry(cache, "o1", 1);
        addObjEntry(cache, "o2", 1);
        addObjEntry(cache, "o3", 2);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "data", objField(1));
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL),
                "object operands are disqualified from the index-only COUNT (hash hits are candidates)");

        final var matched = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("o1", "o2"), matched);
    }

    @Test
    public void test_resolve_ids_via_index_array_equals_disqualified_but_filter_resolves() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var pk = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "a1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, pk);
        addArrEntry(cache, "a1", "x", "y");
        addArrEntry(cache, "a2", "x", "y");
        addArrEntry(cache, "a3", "z");
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "data", arrField("x", "y"));
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(op, TestGlobals.DB, TestGlobals.COLL),
                "array operands are disqualified from the index-only COUNT (hash hits are candidates)");

        final var matched = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("a1", "a2"), matched);
    }

    private void addObjEntry(Cache cache, String id, int n) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("data", objField(n));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void addArrEntry(Cache cache, String id, String... items) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("data", arrField(items));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }
}
