package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.PageOccupancyReconciler;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPageTailHealTest {
    private static final byte[] TORN_BYTES = "{\"_id\":\"torn\",\"pad\":\"interrupted mid-wri"
            .getBytes(StandardCharsets.UTF_8);
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

    private static File adminFolder() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_DB_NAME);
    }

    private static File pageRowFolder(String dbName, String collName) {
        return new File(adminFolder(), Globals.ADMIN_PAGES_FOLDER + Globals.FILE_SEPARATOR
                + String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    private static File lastPage(File folder) {
        final var pages = folder.listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION));
        assertNotNull(pages, folder + " has no pages");
        assertTrue(pages.length > 0, folder + " has no pages");
        return Arrays.stream(pages).max(Comparator.comparingLong(AdminPageTailHealTest::pageNumber)).orElseThrow();
    }

    private static long pageNumber(File page) {
        final var name = page.getName();
        return Long.parseLong(name.substring(name.lastIndexOf('-') + 1, name.length() - 4));
    }

    private static long tear(File page) throws Exception {
        final var before = page.length();
        try (var out = new FileOutputStream(page, true)) {
            out.write(TORN_BYTES);
        }
        return before;
    }

    private static boolean endsAtARecordBoundary(File page) throws Exception {
        final var bytes = Files.readAllBytes(page.toPath());
        return bytes.length == 0 || bytes[bytes.length - 1] == '\n';
    }

    private void assertHealed(File page) throws Exception {
        final var before = tear(page);

        PageOccupancyReconciler.reconcileAll();

        assertTrue(endsAtARecordBoundary(page), page + " still ends in a torn record");
        assertEquals(before, page.length());
    }

    @Test
    public void test_a_torn_admin_page_tail_is_healed() throws Exception {
        assertHealed(lastPage(new File(adminFolder(), Globals.ADMIN_DATABASES_COLLECTION_NAME)));
    }

    @Test
    public void test_a_torn_admin_pages_row_tail_is_healed() throws Exception {
        assertHealed(lastPage(pageRowFolder(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME)));
    }

    @Test
    public void test_a_torn_user_page_row_collection_is_healed() throws Exception {
        final var folder = pageRowFolder(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        final var page = new File(folder,
                folder.getName() + Globals.FILE_PAGE_SEPARATOR + "0" + Globals.DB_FILE_EXTENSION);
        Files.writeString(page.toPath(), "{\"_id\":\"row\"}\n", StandardCharsets.UTF_8);

        assertHealed(page);
    }

    @Test
    public void test_an_admin_append_after_the_heal_reads_back_from_disk() throws Exception {
        tear(lastPage(new File(adminFolder(), Globals.ADMIN_DATABASES_COLLECTION_NAME)));
        PageOccupancyReconciler.reconcileAll();

        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("after_tear", new ArrayList<>(), List.of()));

        final var ids = new ArrayList<String>();
        try (var pages = fs.streamPages(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME)) {
            pages.forEach(page -> ids.addAll(page.keySet()));
        }
        assertTrue(ids.contains("after_tear"), "the acknowledged entry was glued onto the torn tail: " + ids);
    }
}
