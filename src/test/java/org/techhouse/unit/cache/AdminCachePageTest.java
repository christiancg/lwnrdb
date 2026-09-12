package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AdminCache;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class AdminCachePageTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_shift_pk_positions_for_collections_map() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var before = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, "c1", 0,
                10, 0);
        final var after = new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, "c2", 10,
                10, 0);
        final var type = new ReflectionUtils.TypeToken<Map<String, PkIndexEntry>>() {
        };
        final var map = TestUtils.getPrivateField(cache, "collectionsPkIndex", type);
        map.put("c1", before);
        map.put("c2", after);

        cache.shiftPkPositionsAfterCompaction(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, 0, 0, 10);

        assertEquals(0, before.getPosition());
        assertEquals(0, after.getPosition(), "entry after removed position shifts left by removed length");
    }

    @Test
    public void test_shift_pk_positions_for_pages_collection() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, "db", "coll");
        final var entry = new PkIndexEntry(Globals.ADMIN_PAGES_DB_NAME, pagesCollName, "p1", 30, 10, 0);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        TestUtils.getPrivateField(TestUtils.pageCacheOf(cache), "pagesPkIndexes", type).put(
                Cache.getCollectionIdentifier(Globals.ADMIN_PAGES_DB_NAME, pagesCollName),
                new ArrayList<>(List.of(entry)));

        cache.shiftPkPositionsAfterCompaction(pagesCollName, 0, 0, 10);

        assertEquals(20, entry.getPosition());
    }

    @Test
    public void test_shift_pk_positions_for_unknown_pages_collection_is_noop() {
        AdminCache cache = new AdminCache();
        assertDoesNotThrow(() -> cache.shiftPkPositionsAfterCompaction(
                String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, "no", "coll"), 0, 0, 10));
    }

    @Test
    public void test_select_page_for_insert_returns_zero_when_empty() {
        AdminCache cache = new AdminCache();
        long target = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(0L, target);
    }

    @Test
    public void test_select_page_for_insert_first_fit_picks_first_page_with_room()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var p0 = new AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(Long.parseLong(System.getProperty("maxPageBytesOverride", "2097150")));
        p0.setEntryCount(10);
        final var p1 = new AdminPageEntry("myDb", "myColl", 1L);
        p1.setPageSize(200L);
        p1.setEntryCount(1);
        final var p2 = new AdminPageEntry("myDb", "myColl", 2L);
        p2.setPageSize(Long.parseLong(System.getProperty("maxPageBytesOverride", "2097100")));
        p2.setEntryCount(5);

        final var list = new ArrayList<AdminPageEntry>();
        list.add(p0);
        list.add(p1);
        list.add(p2);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(TestUtils.pageCacheOf(cache), "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        long target = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(1L, target, "Should pick first page with room");
    }

    @Test
    public void test_select_page_for_insert_allocates_new_page_when_none_fit()
            throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var p0 = new AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(2_097_150L);
        p0.setEntryCount(10);
        final var p1 = new AdminPageEntry("myDb", "myColl", 1L);
        p1.setPageSize(2_097_150L);
        p1.setEntryCount(10);

        final var list = new ArrayList<AdminPageEntry>();
        list.add(p0);
        list.add(p1);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(TestUtils.pageCacheOf(cache), "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        long target = cache.selectPageForInsert("myDb", "myColl", 100_000);
        assertEquals(2L, target, "Should allocate new page when no existing page has room");
    }

    @Test
    public void test_select_page_for_insert_with_pending_bytes() throws NoSuchFieldException, IllegalAccessException {
        AdminCache cache = new AdminCache();
        final var p0 = new AdminPageEntry("myDb", "myColl", 0L);
        p0.setPageSize(1_000_000L);
        p0.setEntryCount(10);

        final var list = new ArrayList<AdminPageEntry>();
        list.add(p0);
        final var type = new ReflectionUtils.TypeToken<Map<String, List<AdminPageEntry>>>() {
        };
        final var pagesMap = TestUtils.getPrivateField(TestUtils.pageCacheOf(cache), "pages", type);
        pagesMap.put(Cache.getCollectionIdentifier("myDb", "myColl"), list);

        // 1MB existing + 500KB pending + 100KB new = 1.6MB, still under 2MB cap
        long target = cache.selectPageForInsert("myDb", "myColl", 100_000, Map.of(0L, 500_000L));
        assertEquals(0L, target, "Within-batch pending bytes still leave room on page 0");

        // 1MB existing + 1.5MB pending + 100KB new = 2.6MB, exceeds 2MB cap
        long target2 = cache.selectPageForInsert("myDb", "myColl", 100_000, Map.of(0L, 1_500_000L));
        assertEquals(1L, target2, "Pending bytes can push selection to a new page");
    }

    @Test
    public void test_remove_admin_page_entries_clears_both_maps() {
        AdminCache cache = new AdminCache();
        cache.addAdminPageEntries("myDb", "myColl", new AdminPageEntry("myDb", "myColl", 0L));
        cache.getAdminPagePkIndexes("myDb", "myColl")
                .add(new PkIndexEntry("admin", "pages_myColl", "myDb|myColl|0", 0L, 10L, 0L));

        assertNotNull(cache.getAdminPageEntries("myDb", "myColl"));
        cache.removeAdminPageEntries("myDb", "myColl");
        assertNull(cache.getAdminPageEntries("myDb", "myColl"));
    }

    @Test
    public void test_get_admin_page_entry_returns_correct_entry() {
        AdminCache cache = new AdminCache();
        cache.updatePageSizeInMemory("db", "coll", 0L, 100L);
        var entry = cache.getAdminPageEntry("db", "coll", 0L);
        assertNotNull(entry);
        assertEquals(0L, entry.getPage());
    }

    @Test
    public void test_get_admin_page_entry_returns_null_for_missing_page() {
        AdminCache cache = new AdminCache();
        cache.updatePageSizeInMemory("db", "coll", 0L, 100L);
        var entry = cache.getAdminPageEntry("db", "coll", 99L);
        assertNull(entry);
    }

    @Test
    public void test_get_admin_page_entry_returns_null_when_no_pages() {
        AdminCache cache = new AdminCache();
        var entry = cache.getAdminPageEntry("db", "coll", 0L);
        assertNull(entry);
    }

    @Test
    public void test_select_page_for_insert_packs_into_pending_only_page() {
        AdminCache cache = new AdminCache();
        long first = cache.selectPageForInsert("myDb", "myColl", 100);
        assertEquals(0L, first, "First insert allocates page 0");
        long second = cache.selectPageForInsert("myDb", "myColl", 100, Map.of(0L, 100L));
        assertEquals(0L, second, "Second insert reuses the pending (not-yet-committed) page 0");
    }

    @Test
    public void test_select_page_for_insert_allocates_new_when_pending_only_page_full() {
        AdminCache cache = new AdminCache();
        long target = cache.selectPageForInsert("myDb", "myColl", 100_000, Map.of(0L, 2_097_100L));
        assertEquals(1L, target, "Full pending-only page forces a new page allocation");
    }

    @Test
    public void test_select_page_for_insert_prefers_lowest_pending_only_page() {
        AdminCache cache = new AdminCache();
        long target = cache.selectPageForInsert("myDb", "myColl", 100, Map.of(0L, 2_097_150L, 1L, 100L));
        assertEquals(1L, target, "First pending page with room is chosen in ascending order");
    }
}
