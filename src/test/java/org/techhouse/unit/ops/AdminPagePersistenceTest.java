package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPagePersistenceTest {
    private final Cache cache = IocContainer.get(Cache.class);

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

    private File pagesFolder() {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, TestGlobals.DB,
                TestGlobals.COLL);
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER + Globals.FILE_SEPARATOR + pagesCollName);
    }

    private long persistedBytes() throws Exception {
        final var folder = pagesFolder();
        final var files = folder.listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
        if (files == null) {
            return 0;
        }
        var total = 0L;
        for (final var file : files) {
            total += Files.size(file.toPath());
        }
        return total;
    }

    private DbEntry entryOnPage(String id, long page) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("payload", "value");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.setPage(page);
        return entry;
    }

    @Test
    public void test_first_touch_page_is_persisted_after_an_in_memory_pre_seed() throws Exception {
        final var entry = entryOnPage("p1", 0L);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0L, entry.byteSize());
        AdminPageHelper.baseUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(entry), true);

        assertTrue(persistedBytes() > 0,
                "page metadata for a user collection must reach disk even when the in-memory entry already exists");
    }

    @Test
    public void test_persisted_page_row_describes_its_own_page() throws Exception {
        final var first = entryOnPage("p1", 0L);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0L, first.byteSize());
        AdminPageHelper.baseUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(first), true);

        final var second = entryOnPage("p2", 1L);
        cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 1L, second.byteSize());
        AdminPageHelper.baseUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(second),
                true);

        final var entries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(entries.stream().anyMatch(e -> e.getPage() == 0L), "page 0 must still describe page 0");
        assertTrue(entries.stream().anyMatch(e -> e.getPage() == 1L), "page 1 must still describe page 1");
    }

    @Test
    public void test_page_list_is_not_partial_after_two_pages() throws Exception {
        for (long page = 0; page < 2; page++) {
            final var entry = entryOnPage("p" + page, page);
            cache.updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, page, entry.byteSize());
            AdminPageHelper.baseUpdateEntryCount(TestGlobals.DB, TestGlobals.COLL, EventType.CREATED, List.of(entry),
                    true);
        }
        final var entries = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL);
        assertFalse(entries.isEmpty());
        assertTrue(entries.size() >= 2, "both touched pages must be tracked");
    }
}
