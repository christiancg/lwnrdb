package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestUtils;

public class AdminOperationHelperDropDatabaseTest {
    private static final String DROPPED_DB = "droppedWithCollections";
    private static final int COLLECTIONS = 5;

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private long recordedDatabasesPageSize() {
        final var row = cache.getAdminPageEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, 0);
        assertNotNull(row, "admin/databases must have a page row");
        return row.getPageSize();
    }

    private long databasesPageFileLength() throws Exception {
        return fs.pageFileLengths(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME).get(0L);
    }

    @Test
    public void test_dropping_a_database_with_collections_subtracts_the_row_it_actually_erases() throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(DROPPED_DB));
        for (var i = 0; i < COLLECTIONS; i++) {
            AdminOperationHelper
                    .saveCollectionEntry(new AdminCollEntry(DROPPED_DB, "collection-with-a-long-name-" + i));
        }
        assertEquals(databasesPageFileLength(), recordedDatabasesPageSize(),
                "the page row must agree with the file before the drop, or this test covers nothing");

        AdminOperationHelper.deleteDatabaseEntry(DROPPED_DB);

        assertEquals(databasesPageFileLength(), recordedDatabasesPageSize(),
                "the erased row is the one left after its collections were unregistered, not the longer original");
    }
}
