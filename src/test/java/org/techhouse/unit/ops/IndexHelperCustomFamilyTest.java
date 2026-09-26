package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperCustomFamilyTest {
    private static final String FIELD = "loc";

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static DbEntry scriptWritten(String id, String value) {
        final var scriptValue = new JsObject();
        scriptValue.set(Globals.PK_FIELD, new JsString(id));
        scriptValue.set(FIELD, new JsString(value));
        final var document = (JsonObject) EJsonInterop.toHostEjson(scriptValue);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document);
        entry.set_id(id);
        return entry;
    }

    private static void cacheAndIndex(DbEntry entry) throws IOException, InterruptedException {
        final var cache = IocContainer.get(Cache.class);
        cache.putAdminCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, entry.get_id(), 0, 100, 0));
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, entry.get_id());
    }

    @Test
    public void test_a_promoted_custom_value_is_indexed_in_its_custom_family()
            throws IOException, InterruptedException {
        cacheAndIndex(scriptWritten("g1", "#geo(3.0,4.0)"));

        final var cache = IocContainer.get(Cache.class);
        final var geoFamily = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                JsonGeo.class);
        final var stringFamily = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                String.class);

        assertNotNull(geoFamily);
        assertTrue(geoFamily.stream().anyMatch(entry -> entry.getIds().contains("g1")),
                "a script-written geo value belongs in the geo family, not the string family");
        assertTrue(stringFamily == null || stringFamily.stream().noneMatch(entry -> entry.getIds().contains("g1")));
    }

    @Test
    public void test_an_ordinary_string_stays_in_the_string_family() throws IOException, InterruptedException {
        cacheAndIndex(scriptWritten("s1", "plain"));

        final var cache = IocContainer.get(Cache.class);
        final var stringFamily = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                String.class);

        assertNotNull(stringFamily);
        assertTrue(stringFamily.stream().anyMatch(entry -> entry.getIds().contains("s1")));
    }
}
