package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPageLeftoverRowsTest {
    private static final String DB = "pgl";
    private static final String COLL = "leftover";
    private static final String LONG_DB = "pgx_one";
    private static final String SHORT_COLL = "two";
    private static final String SHORT_DB = "pgx";
    private static final String LONG_COLL = "one_two";
    private static final String SHARED_FOLDER = "pgx_one_two";
    private static final String DROPPED_DB = "droppedWithCollections";
    private static final int DROPPED_COLLECTIONS = 5;
    private static final long DELTA_MAX_PAGE_SIZE = 1024L;
    private static final int DELTA_COLLECTIONS = 40;
    private static final String DELTA_PREFIX = "page-delta-collection-";
    private static final Configuration configuration = Configuration.getInstance();
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
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
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
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private boolean persistRows(String dbName, String collName) throws Exception {
        cache.removeAdminPageEntries(dbName, collName);
        return PageOccupancyReconciler.reconcile(dbName, collName);
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

    private void twoCollectionsSharingAFolder() throws Exception {
        createCollection(LONG_DB, SHORT_COLL);
        createCollection(SHORT_DB, LONG_COLL);
        save(LONG_DB, SHORT_COLL, "a");
        save(SHORT_DB, LONG_COLL, "b");
        save(SHORT_DB, LONG_COLL, "c");
        assertTrue(persistRows(LONG_DB, SHORT_COLL));
        assertTrue(persistRows(SHORT_DB, LONG_COLL));
    }

    private List<String> indexedSharedRowIds(String dbName, String collName) throws Exception {
        final var prefix = Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR;
        return fs.readWholePkIndexFile(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER).stream().map(PkIndexEntry::getValue)
                .filter(id -> id.startsWith(prefix)).toList();
    }

    private File sharedFolder() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER + Globals.FILE_SEPARATOR + SHARED_FOLDER);
    }

    @Test
    public void bothCollectionsWriteTheirRowsIntoTheSameFolder() throws Exception {
        twoCollectionsSharingAFolder();
        assertFalse(rowIdsOnDisk(SHARED_FOLDER, LONG_DB, SHORT_COLL).isEmpty());
        assertFalse(rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL).isEmpty());
    }

    @Test
    public void droppingOneOfTwoCollidingCollectionsKeepsTheOthersRowsOnDisk() throws Exception {
        twoCollectionsSharingAFolder();
        final var survivorRows = rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL)).getStatus());

        assertTrue(sharedFolder().exists());
        assertEquals(survivorRows, rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL));
        assertEquals(survivorRows, indexedSharedRowIds(SHORT_DB, LONG_COLL));
        assertTrue(rowIdsOnDisk(SHARED_FOLDER, LONG_DB, SHORT_COLL).isEmpty());
        assertTrue(indexedSharedRowIds(LONG_DB, SHORT_COLL).isEmpty());
    }

    @Test
    public void droppingOneOfTwoCollidingCollectionsKeepsTheSharedPkListAndPagination() throws Exception {
        twoCollectionsSharingAFolder();
        final var survivorRows = rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL);

        processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL));

        final var cachedIds = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER).stream()
                .map(PkIndexEntry::getValue).toList();
        assertEquals(survivorRows, cachedIds);
        final var pagination = cache.getAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER);
        assertNotNull(pagination);
        assertEquals(survivorRows.size(), pagination.stream().mapToInt(AdminPageEntry::getEntryCount).sum());
        assertNull(cache.getAdminPageEntries(LONG_DB, SHORT_COLL));
        assertNotNull(cache.getAdminPageEntries(SHORT_DB, LONG_COLL));
    }

    @Test
    public void theRemainingCollectionsRowsStillAgreeWithItsPageFiles() throws Exception {
        twoCollectionsSharingAFolder();
        processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL));

        assertFalse(PageOccupancyReconciler.reconcile(SHORT_DB, LONG_COLL));
    }

    @Test
    public void droppingTheLastCollectionOfAFolderDeletesTheFolder() throws Exception {
        twoCollectionsSharingAFolder();
        processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL));
        processor.processMessage(new DropCollectionRequest(SHORT_DB, LONG_COLL));

        assertFalse(sharedFolder().exists());
        assertTrue(cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER).isEmpty());
    }

    @Test
    public void dropDatabaseOfOneSideKeepsTheOtherSidesRows() throws Exception {
        twoCollectionsSharingAFolder();
        final var survivorRows = rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL);

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(LONG_DB)).getStatus());

        assertEquals(survivorRows, rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL));
    }

    @Test
    public void theQuarantineDropKeepsTheOtherSidesRows() throws Exception {
        twoCollectionsSharingAFolder();
        final var survivorRows = rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL);

        AdminOperationHelper.deletePageCollections(LONG_DB, SHORT_COLL);

        assertEquals(survivorRows, rowIdsOnDisk(SHARED_FOLDER, SHORT_DB, LONG_COLL));
        assertTrue(rowIdsOnDisk(SHARED_FOLDER, LONG_DB, SHORT_COLL).isEmpty());
    }

    private long recordedDatabasesPageSize() {
        final var row = cache.getAdminPageEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, 0);
        assertNotNull(row, "admin/databases must have a page row");
        return row.getPageSize();
    }

    private long databasesPageLiveBytes() throws Exception {
        return fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME).stream()
                .filter(row -> row.getPage() == 0L).mapToLong(PkIndexEntry::getLength).sum();
    }

    @Test
    public void test_dropping_a_database_with_collections_subtracts_the_row_it_actually_erases() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(DROPPED_DB));
        for (var i = 0; i < DROPPED_COLLECTIONS; i++) {
            AdminOperationHelper
                    .saveCollectionEntry(new AdminCollEntry(DROPPED_DB, "collection-with-a-long-name-" + i));
        }
        assertEquals(databasesPageLiveBytes(), recordedDatabasesPageSize(),
                "the page row must agree with the live bytes before the drop, or this test covers nothing");

        AdminOperationHelper.deleteDatabaseEntry(DROPPED_DB);

        assertEquals(databasesPageLiveBytes(), recordedDatabasesPageSize(),
                "the erased row is the one left after its collections were unregistered, not the longer original");
    }

    private void seedCollectionsPastPageZero() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "maxPageSize", DELTA_MAX_PAGE_SIZE);
        for (var i = 0; i < DELTA_COLLECTIONS; i++) {
            AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, DELTA_PREFIX + i));
        }
        PageRowWriteBack.flushAll();
        cache.loadAdminData();
    }

    private record PageRow(long entryCount, long pageSize) {
    }

    private Map<Long, PageRow> pageRows() {
        return cache.getAdminPageEntries(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME).stream()
                .collect(Collectors.toMap(AdminPageEntry::getPage,
                        row -> new PageRow(row.getEntryCount(), row.getPageSize())));
    }

    private String collectionStoredPastPageZero() {
        for (var i = DELTA_COLLECTIONS - 1; i >= 0; i--) {
            final var pk = cache
                    .getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.DB, DELTA_PREFIX + i));
            if (pk != null && pk.getPage() > 0) {
                return DELTA_PREFIX + i;
            }
        }
        throw new IllegalStateException("admin/collections never spilled past page 0");
    }

    @Test
    public void test_dropping_a_collection_loaded_from_disk_books_its_delta_on_its_own_page() throws Exception {
        seedCollectionsPastPageZero();
        final var coll = collectionStoredPastPageZero();
        final var pk = cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.DB, coll));
        final var before = pageRows();

        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, coll);

        final var after = pageRows();
        assertEquals(before.get(0L).entryCount(), after.get(0L).entryCount(),
                "page 0 must not lose a row that lived on page " + pk.getPage());
        assertEquals(before.get(0L).pageSize(), after.get(0L).pageSize(),
                "page 0 must not shrink for a row that lived on page " + pk.getPage());
        final var ownPage = after.get(pk.getPage());
        assertNotNull(ownPage);
        assertEquals(before.get(pk.getPage()).entryCount() - 1, ownPage.entryCount());
        assertEquals(before.get(pk.getPage()).pageSize() - pk.getLength(), ownPage.pageSize());
    }

    @Test
    public void test_dropping_a_collection_on_page_zero_still_books_page_zero() throws Exception {
        seedCollectionsPastPageZero();
        final var pk = cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.DB, DELTA_PREFIX + 0));
        assertEquals(0L, pk.getPage());
        final var before = pageRows();

        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, DELTA_PREFIX + 0);

        final var after = pageRows();
        assertEquals(before.get(0L).entryCount() - 1, after.get(0L).entryCount());
        assertEquals(before.get(0L).pageSize() - pk.getLength(), after.get(0L).pageSize());
    }
}
