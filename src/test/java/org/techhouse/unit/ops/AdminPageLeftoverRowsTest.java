package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestUtils;

public class AdminPageLeftoverRowsTest {
    private static final String DB = "pgl";
    private static final String COLL = "leftover";
    private static final String LONG_DB = "pgx_one";
    private static final String SHORT_COLL = "two";
    private static final String SHORT_DB = "pgx";
    private static final String LONG_COLL = "one_two";
    private static final String SHARED_FOLDER = "pgx_one_two";
    private static final String PAGES_FOLDER = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, DB, COLL);

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void createCollection(String dbName, String collName) {
        processor.processMessage(new CreateDatabaseRequest(dbName));
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(dbName, collName)).getStatus());
    }

    private void save(String dbName, String collName, String id) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", "value-" + id);
        final var request = new SaveRequest(dbName, collName);
        request.setObject(object);
        request.set_id(id);
        SaveOperationHelper.executeSave(request);
    }

    private void persistRows(String dbName, String collName) throws Exception {
        cache.removeAdminPageEntries(dbName, collName);
        PageOccupancyReconciler.reconcile(dbName, collName);
    }

    private void leaveRowsBehindAKilledDrop(String dbName, String collName) throws Exception {
        assertTrue(fs.deleteCollectionFiles(dbName, collName));
        cache.evictCollection(dbName, collName);
        AdminOperationHelper.deleteCollectionEntry(dbName, collName);
        cache.removeAdminPageEntries(dbName, collName);
    }

    private List<String> rowIdsOnDisk(String pagesFolder, String dbName, String collName) throws Exception {
        final var prefix = Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR;
        final var ids = new ArrayList<String>();
        try (var pages = fs.streamPages(Globals.ADMIN_PAGES_DB_NAME, pagesFolder)) {
            pages.forEach(page -> page.keySet().stream().filter(id -> id.startsWith(prefix)).forEach(ids::add));
        }
        return ids;
    }

    private List<String> indexedRowIds() throws Exception {
        return fs.readWholePkIndexFile(Globals.ADMIN_PAGES_DB_NAME, PAGES_FOLDER).stream().map(PkIndexEntry::getValue)
                .toList();
    }

    @Test
    public void a_new_registration_removes_a_leftover_page_row_folder() throws Exception {
        createCollection(DB, COLL);
        save(DB, COLL, "a");
        persistRows(DB, COLL);
        assertFalse(indexedRowIds().isEmpty(), "the fixture wrote no page rows");
        leaveRowsBehindAKilledDrop(DB, COLL);
        cache.removeAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, PAGES_FOLDER);

        createCollection(DB, COLL);

        assertTrue(indexedRowIds().isEmpty());
        assertTrue(rowIdsOnDisk(PAGES_FOLDER, DB, COLL).isEmpty());
    }

    @Test
    public void a_recreated_collection_pk_index_holds_each_row_id_once() throws Exception {
        createCollection(DB, COLL);
        save(DB, COLL, "a");
        persistRows(DB, COLL);
        leaveRowsBehindAKilledDrop(DB, COLL);
        cache.removeAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, PAGES_FOLDER);

        createCollection(DB, COLL);
        save(DB, COLL, "b");
        persistRows(DB, COLL);

        final var rowIds = indexedRowIds();
        assertFalse(rowIds.isEmpty());
        assertEquals(rowIds.size(), new HashSet<>(rowIds).size(), "duplicate page row ids: " + rowIds);
        assertFalse(PageOccupancyReconciler.reconcile(DB, COLL), "the rows must agree with the page files");
    }

    @Test
    public void a_new_registration_in_a_shared_page_folder_removes_only_its_own_rows() throws Exception {
        createCollection(LONG_DB, SHORT_COLL);
        createCollection(SHORT_DB, LONG_COLL);
        save(LONG_DB, SHORT_COLL, "a");
        save(SHORT_DB, LONG_COLL, "b");
        persistRows(LONG_DB, SHORT_COLL);
        persistRows(SHORT_DB, LONG_COLL);
        final var survivorRows = rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL);
        leaveRowsBehindAKilledDrop(LONG_DB, SHORT_COLL);

        createCollection(LONG_DB, SHORT_COLL);

        assertTrue(rowIdsOnDisk(SHARED_FOLDER, LONG_DB, SHORT_COLL).isEmpty());
        assertEquals(survivorRows, rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL));
    }
}
