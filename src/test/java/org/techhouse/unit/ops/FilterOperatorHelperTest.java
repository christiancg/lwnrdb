package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterOperatorHelperTest {
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
    public void test_missing_field_returns_false() {
        JsonObject obj = new JsonObject();
        obj.addProperty("other", "value");

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "missing", new JsonString("value"));
        BiPredicate<JsonObject, String> tester = FilterOperatorHelper.getTester(op, FieldOperatorType.EQUALS);

        assertFalse(tester.test(obj, "missing"));
    }

    private static JsonObject objField(int n) {
        final var inner = new JsonObject();
        inner.addProperty("n", n);
        return inner;
    }

    // Path 1: a stale Object hash-index hit (the document's value changed but the index was not updated,
    // as after a background-processing failure) is re-tested against the document and dropped, so the
    // FILTER never returns a false positive.
    @Test
    public void test_filter_rejects_stale_object_hash_hit() throws IOException {
        final var cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var pk = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "o1", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, pk);
        addObjEntry(cache, "o1", 1);
        addObjEntry(cache, "o2", 1);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "data");

        addObjEntry(cache, "o1", 99);

        FieldOperator op = new FieldOperator(FieldOperatorType.EQUALS, "data", objField(1));
        final var matched = FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("o2"), matched, "the stale hash hit o1 must be dropped after re-testing the document");
    }

    private void addObjEntry(Cache cache, String id, int n) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add("data", objField(n));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
    }
}
