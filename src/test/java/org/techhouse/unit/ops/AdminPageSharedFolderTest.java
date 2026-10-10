package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
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
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPageSharedFolderTest {
    private static final String LONG_DB = "pgx_one";
    private static final String SHORT_COLL = "two";
    private static final String SHORT_DB = "pgx";
    private static final String LONG_COLL = "one_two";
    private static final String SHARED_FOLDER = "pgx_one_two";

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        createCollection(LONG_DB, SHORT_COLL);
        createCollection(SHORT_DB, LONG_COLL);
        save(LONG_DB, SHORT_COLL, "a");
        save(SHORT_DB, LONG_COLL, "b");
        save(SHORT_DB, LONG_COLL, "c");
        persistRows(LONG_DB, SHORT_COLL);
        persistRows(SHORT_DB, LONG_COLL);
    }

    private void persistRows(String dbName, String collName) throws Exception {
        cache.removeAdminPageEntries(dbName, collName);
        assertTrue(PageOccupancyReconciler.reconcile(dbName, collName));
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
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private static String rowPrefix(String dbName, String collName) {
        return Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR;
    }

    private List<String> rowIdsOnDisk(String dbName, String collName) throws Exception {
        final var prefix = rowPrefix(dbName, collName);
        final var ids = new ArrayList<String>();
        try (var pages = fs.streamPages(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER)) {
            pages.forEach(page -> page.keySet().stream().filter(id -> id.startsWith(prefix)).forEach(ids::add));
        }
        return ids;
    }

    private List<String> indexedRowIds(String dbName, String collName) throws Exception {
        final var prefix = rowPrefix(dbName, collName);
        return fs.readWholePkIndexFile(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER).stream().map(PkIndexEntry::getValue)
                .filter(id -> id.startsWith(prefix)).toList();
    }

    private File sharedFolder() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER + Globals.FILE_SEPARATOR + SHARED_FOLDER);
    }

    @Test
    public void bothCollectionsWriteTheirRowsIntoTheSameFolder() throws Exception {
        assertFalse(rowIdsOnDisk(LONG_DB, SHORT_COLL).isEmpty());
        assertFalse(rowIdsOnDisk(SHORT_DB, LONG_COLL).isEmpty());
    }

    @Test
    public void droppingOneOfTwoCollidingCollectionsKeepsTheOthersRowsOnDisk() throws Exception {
        final var survivorRows = rowIdsOnDisk(SHORT_DB, LONG_COLL);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL)).getStatus());

        assertTrue(sharedFolder().exists());
        assertEquals(survivorRows, rowIdsOnDisk(SHORT_DB, LONG_COLL));
        assertEquals(survivorRows, indexedRowIds(SHORT_DB, LONG_COLL));
        assertTrue(rowIdsOnDisk(LONG_DB, SHORT_COLL).isEmpty());
        assertTrue(indexedRowIds(LONG_DB, SHORT_COLL).isEmpty());
    }

    @Test
    public void droppingOneOfTwoCollidingCollectionsKeepsTheSharedPkListAndPagination() throws Exception {
        final var survivorRows = rowIdsOnDisk(SHORT_DB, LONG_COLL);

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
        processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL));

        assertFalse(PageOccupancyReconciler.reconcile(SHORT_DB, LONG_COLL));
    }

    @Test
    public void droppingTheLastCollectionOfAFolderDeletesTheFolder() {
        processor.processMessage(new DropCollectionRequest(LONG_DB, SHORT_COLL));
        processor.processMessage(new DropCollectionRequest(SHORT_DB, LONG_COLL));

        assertFalse(sharedFolder().exists());
        assertTrue(cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_DB_NAME, SHARED_FOLDER).isEmpty());
    }

    @Test
    public void dropDatabaseOfOneSideKeepsTheOtherSidesRows() throws Exception {
        final var survivorRows = rowIdsOnDisk(SHORT_DB, LONG_COLL);

        assertEquals(OperationStatus.OK, processor.processMessage(new DropDatabaseRequest(LONG_DB)).getStatus());

        assertEquals(survivorRows, rowIdsOnDisk(SHORT_DB, LONG_COLL));
    }

    @Test
    public void theQuarantineDropKeepsTheOtherSidesRows() throws Exception {
        final var survivorRows = rowIdsOnDisk(SHORT_DB, LONG_COLL);

        AdminOperationHelper.deletePageCollections(LONG_DB, SHORT_COLL);

        assertEquals(survivorRows, rowIdsOnDisk(SHORT_DB, LONG_COLL));
        assertTrue(rowIdsOnDisk(LONG_DB, SHORT_COLL).isEmpty());
    }
}
