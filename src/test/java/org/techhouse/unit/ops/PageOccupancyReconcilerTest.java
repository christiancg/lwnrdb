package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageOccupancyReconcilerTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

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

    private void save(String id) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", "value-" + id);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private AdminPageEntry row(long page) {
        final var rows = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        return rows == null ? null : rows.stream().filter(r -> r.getPage() == page).findFirst().orElse(null);
    }

    private AdminPageEntry existingRow(long page) {
        final var found = row(page);
        assertNotNull(found, "page " + page + " has no row");
        return found;
    }

    private long firstPageFileLength() throws Exception {
        return fs.pageFileLengths(TestGlobals.DB, TestGlobals.COLL).get(0L);
    }

    private File persistedRowsFolder() {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, TestGlobals.DB,
                TestGlobals.COLL);
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER + Globals.FILE_SEPARATOR + pagesCollName);
    }

    @Test
    public void test_missing_row_for_a_page_file_is_rebuilt() throws Exception {
        save("a");
        save("b");
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        final var rebuilt = row(0);
        assertNotNull(rebuilt);
        assertEquals(2, rebuilt.getEntryCount());
        assertEquals(firstPageFileLength(), rebuilt.getPageSize());
        final var files = persistedRowsFolder().listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
        assertNotNull(files);
        assertTrue(files.length > 0, "the rebuilt row must reach disk, not only the cache");
    }

    @Test
    public void test_row_with_wrong_size_is_corrected() throws Exception {
        save("a");
        existingRow(0).setPageSize(1L);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(firstPageFileLength(), existingRow(0).getPageSize());
        assertEquals(1, existingRow(0).getEntryCount());
    }

    @Test
    public void test_row_for_a_page_file_that_no_longer_exists_is_emptied() throws Exception {
        save("a");
        final var phantom = new AdminPageEntry(TestGlobals.DB, TestGlobals.COLL, 9L);
        phantom.setEntryCount(3);
        phantom.setPageSize(500L);
        cache.addAdminPageEntries(TestGlobals.DB, TestGlobals.COLL, phantom);

        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertEquals(0, existingRow(9).getEntryCount());
        assertEquals(0L, existingRow(9).getPageSize());
    }

    @Test
    public void test_consistent_collection_is_not_rewritten() throws Exception {
        save("a");
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));

        assertFalse(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_an_empty_collection_needs_nothing() throws Exception {
        assertFalse(PageOccupancyReconciler.reconcile(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_reconcile_all_covers_every_registered_collection() throws Exception {
        save("a");
        cache.removeAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);

        PageOccupancyReconciler.reconcileAll();

        assertNotNull(row(0));
        assertEquals(firstPageFileLength(), existingRow(0).getPageSize());
    }
}
