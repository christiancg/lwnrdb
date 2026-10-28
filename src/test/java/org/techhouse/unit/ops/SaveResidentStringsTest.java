package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.cache.Cache;
import org.techhouse.cache.PkIndexList;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.DirtySink;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SaveResidentStringsTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private static final DirtySink NO_LISTENERS = (_, _) -> {
    };
    private static final long APPLIED_VERSION = 1_900_000_000_000L;
    private BackgroundTaskManager originalTaskManager;
    private BackgroundTaskManager stubTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        stubTaskManager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", stubTaskManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", originalTaskManager);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void processQueuedEvents() throws Exception {
        for (final var event : TestUtils.queuedBackgroundEvents(stubTaskManager)) {
            EventProcessorHelper.processEvent(event);
        }
        TestUtils.discardQueuedBackgroundEvents(stubTaskManager);
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

    @Test
    public void test_a_versioned_apply_reuses_the_resident_id() throws Exception {
        save(fresh("versioned"), 1);
        final var resident = rowOf("versioned").getValue();
        final var request = new SaveRequest(fresh(TestGlobals.DB), fresh(TestGlobals.COLL));
        request.setObject(document(fresh("versioned"), 2));

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeSave(request, APPLIED_VERSION, NO_LISTENERS);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertEquals(APPLIED_VERSION, rowOf("versioned").getVersion());
        assertSame(resident, residentDocument("versioned").get_id());
    }

    @Test
    public void test_a_replicated_bulk_apply_reuses_the_resident_id() throws Exception {
        bulkSave(fresh("replicated"));
        final var resident = rowOf("replicated").getValue();
        final var request = new BulkSaveRequest(fresh(TestGlobals.DB), fresh(TestGlobals.COLL));
        request.setObjects(List.of(document(fresh("replicated"), 3)));

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeBulkSave(request, List.of(APPLIED_VERSION), NO_LISTENERS);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertSame(resident, rowOf("replicated").getValue());
        assertSame(resident, residentDocument("replicated").get_id());
    }

    @Test
    public void test_an_update_that_changes_the_shape_reuses_the_resident_id() throws Exception {
        save(fresh("reshaped"), 1);
        final var resident = rowOf("reshaped").getValue();
        final var reshaped = document(fresh("reshaped"), 2);
        reshaped.addProperty("added", "new member");
        reshaped.remove("version");
        final var request = new SaveRequest(fresh(TestGlobals.DB), fresh(TestGlobals.COLL));
        request.setObject(reshaped);

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeSave(request);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        final var document = residentDocument("reshaped");
        assertSame(resident, document.get_id());
        assertEquals("new member", document.getData().get("added").asJsonString().getValue());
        assertNull(document.getData().get("version"));
    }

    @Test
    public void test_an_updated_id_reaches_the_field_index_as_the_resident_instance() throws Exception {
        save(fresh("indexed"), 1);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "version");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("version"));
        final var resident = rowOf("indexed").getValue();
        TestUtils.discardQueuedBackgroundEvents(stubTaskManager);

        save(fresh("indexed"), 2);
        processQueuedEvents();

        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, "version",
                    Number.class);
            final var bucket = entries.stream().filter(entry -> entry.getValue().doubleValue() == 2.0).findFirst()
                    .orElseThrow();
            final Set<String> byIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
            byIdentity.addAll(bucket.getIds());
            assertTrue(byIdentity.contains(resident),
                    "the index holds the resident id instance, not the request's copy");
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }
}
