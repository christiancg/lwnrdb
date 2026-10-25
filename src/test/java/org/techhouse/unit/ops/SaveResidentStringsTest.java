package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.PkIndexList;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SaveResidentStringsTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String fresh(String text) {
        return new String(text.toCharArray());
    }

    private static JsonObject document(String id, int version) {
        final var document = new JsonObject();
        document.addProperty("_id", id);
        document.addProperty("version", version);
        return document;
    }

    private void save(String id, int version) throws Exception {
        final var request = new SaveRequest(fresh(TestGlobals.DB), fresh(TestGlobals.COLL));
        request.setObject(document(id, version));
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeSave(request);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private void bulkSave(String... ids) throws Exception {
        final var request = new BulkSaveRequest(fresh(TestGlobals.DB), fresh(TestGlobals.COLL));
        request.setObjects(java.util.Arrays.stream(ids).map(id -> document(id, 2)).toList());
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeBulkSave(request);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private DbEntry residentDocument(String id) {
        final var collection = cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL);
        assertNotNull(collection, "the collection is resident");
        final var document = collection.get(id);
        assertNotNull(document, id + " is resident");
        return document;
    }

    private PkIndexEntry rowOf(String id) throws IOException {
        final List<PkIndexEntry> index = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        return index.get(PkIndexList.positionOf(index, id));
    }

    @Test
    public void test_an_update_reuses_the_resident_id() throws Exception {
        save(fresh("resident"), 1);
        final var row = rowOf("resident");

        save(fresh("resident"), 2);

        final var updated = rowOf("resident");
        assertSame(row.getValue(), updated.getValue());
        final var document = residentDocument("resident");
        assertEquals(2, document.getData().get("version").asJsonNumber().getValue().intValue());
        assertSame(updated.getValue(), document.get_id());
        assertSame(updated.getValue(), document.getData().get("_id").asJsonString().getValue());
    }

    @Test
    public void test_an_update_reuses_the_canonical_names() throws Exception {
        save(fresh("named"), 1);
        save(fresh("named"), 2);

        assertSame(rowOf("named").getValue(), residentDocument("named").get_id());
        final var names = Cache.canonicalNames(TestGlobals.DB, TestGlobals.COLL);
        final var document = residentDocument("named");
        assertSame(names.dbName(), document.getDatabaseName());
        assertSame(names.collName(), document.getCollectionName());
    }

    @Test
    public void test_an_insert_uses_the_canonical_names() throws Exception {
        save(fresh("inserted"), 1);

        final var row = rowOf("inserted");
        final var names = Cache.canonicalNames(TestGlobals.DB, TestGlobals.COLL);
        assertSame(names.dbName(), row.getDatabaseName());
        assertSame(names.collName(), row.getCollectionName());
    }

    @Test
    public void test_a_bulk_update_row_reuses_the_resident_id() throws Exception {
        bulkSave(fresh("bulk-a"), fresh("bulk-b"));
        final var first = rowOf("bulk-a");

        bulkSave(fresh("bulk-a"), fresh("bulk-c"));

        final var updated = rowOf("bulk-a");
        assertSame(first.getValue(), updated.getValue());
        assertSame(updated.getValue(), residentDocument("bulk-a").get_id());
    }
}
