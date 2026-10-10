package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminCollectionRegistrationTest {
    private static final String NEW_COLL = "freshCollection";

    private Cache cache;
    private File[] databasePages;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        final var fs = IocContainer.get(FileSystem.class);
        final var folder = new File(TestUtils.getDbPath(fs),
                Globals.ADMIN_DB_NAME + File.separator + Globals.ADMIN_DATABASES_COLLECTION_NAME);
        databasePages = folder.listFiles((_, name) -> name.endsWith(".dat"));
        Assumptions.assumeTrue(databasePages != null && databasePages.length > 0);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var page : databasePages) {
            assertTrue(page.setWritable(true));
        }
        TestUtils.standardTearDown();
    }

    private void blockDatabaseRowWrites() {
        for (final var page : databasePages) {
            assertTrue(page.setWritable(false));
        }
        Assumptions.assumeFalse(databasePages[0].canWrite());
    }

    @Test
    public void test_a_failed_database_row_write_leaves_a_new_collection_unregistered_and_unlisted() {
        final var listedBefore = new ArrayList<>(cache.getAdminDbEntry(TestGlobals.DB).getCollections());
        blockDatabaseRowWrites();

        assertThrows(Exception.class,
                () -> AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, NEW_COLL)));

        assertEquals(listedBefore, cache.getAdminDbEntry(TestGlobals.DB).getCollections());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, NEW_COLL));
        assertNull(cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.DB, NEW_COLL)));
    }

    @Test
    public void test_a_failed_database_row_write_keeps_a_dropped_collection_listed_in_memory() {
        blockDatabaseRowWrites();

        assertThrows(Exception.class,
                () -> AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL));

        assertTrue(cache.getAdminDbEntry(TestGlobals.DB).getCollections().contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_successful_registration_lists_the_collection_on_a_fresh_entry() throws Exception {
        final var before = cache.getAdminDbEntry(TestGlobals.DB);
        final var listedBefore = new ArrayList<>(before.getCollections());

        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, NEW_COLL));

        assertEquals(listedBefore, before.getCollections());
        final var expected = new ArrayList<>(listedBefore);
        expected.add(NEW_COLL);
        assertEquals(expected, cache.getAdminDbEntry(TestGlobals.DB).getCollections());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, NEW_COLL));
    }

    @Test
    public void test_a_successful_drop_unlists_the_collection() throws Exception {
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(List.of(), cache.getAdminDbEntry(TestGlobals.DB).getCollections());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }
}
