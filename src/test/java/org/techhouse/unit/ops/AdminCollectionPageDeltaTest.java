package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminCollectionPageDeltaTest {
    private static final long MAX_PAGE_SIZE = 1024L;
    private static final int COLLECTIONS = 40;
    private static final String PREFIX = "page-delta-collection-";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "maxPageSize", MAX_PAGE_SIZE);
        for (var i = 0; i < COLLECTIONS; i++) {
            AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, PREFIX + i));
        }
        cache.loadAdminData();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private record PageRow(long entryCount, long pageSize) {
    }

    private Map<Long, PageRow> pageRows() {
        return cache.getAdminPageEntries(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME).stream()
                .collect(Collectors.toMap(AdminPageEntry::getPage,
                        row -> new PageRow(row.getEntryCount(), row.getPageSize())));
    }

    private String collectionStoredPastPageZero() {
        for (var i = COLLECTIONS - 1; i >= 0; i--) {
            final var pk = cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.DB, PREFIX + i));
            if (pk != null && pk.getPage() > 0) {
                return PREFIX + i;
            }
        }
        throw new IllegalStateException("admin/collections never spilled past page 0");
    }

    @Test
    public void test_dropping_a_collection_loaded_from_disk_books_its_delta_on_its_own_page() throws Exception {
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
        final var pk = cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.DB, PREFIX + 0));
        assertEquals(0L, pk.getPage());
        final var before = pageRows();

        AdminOperationHelper.deleteCollectionEntry(TestGlobals.DB, PREFIX + 0);

        final var after = pageRows();
        assertEquals(before.get(0L).entryCount() - 1, after.get(0L).entryCount());
        assertEquals(before.get(0L).pageSize() - pk.getLength(), after.get(0L).pageSize());
    }
}
