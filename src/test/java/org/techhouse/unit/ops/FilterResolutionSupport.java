package org.techhouse.unit.ops;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

abstract class FilterResolutionSupport {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    protected void addTyped(Cache cache, String id, String field, JsonBaseElement value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    protected Cache mixedTypeFixture() {
        final var cache = IocContainer.get(Cache.class);
        cache.putAdminCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "seed", 0, 100, 0));
        return cache;
    }

    protected void indexField(Cache cache, String field) throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(field));
    }

    protected Set<String> matched(FieldOperator op) throws IOException {
        return FilterOperatorHelper.processOperator(op, null, TestGlobals.DB, TestGlobals.COLL)
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(java.util.stream.Collectors.toSet());
    }

    protected static JsonArray arrayOf(JsonBaseElement... values) {
        final var array = new JsonArray();
        for (final var value : values) {
            array.add(value);
        }
        return array;
    }
}
