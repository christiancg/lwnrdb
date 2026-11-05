package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPageSelectionTest {
    private static final String COLL = "pagesel";
    private Cache cache;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static long maxPageSize() {
        return Configuration.getInstance().getMaxPageSize();
    }

    private void page(long number, long size) {
        final var entry = new AdminPageEntry(TestGlobals.DB, COLL, number);
        entry.setPageSize(size);
        entry.setEntryCount(1);
        cache.addAdminPageEntries(TestGlobals.DB, COLL, entry);
    }

    @Test
    public void test_first_fit_picks_the_lowest_page_with_room() {
        page(0, maxPageSize() - 10);
        page(1, 0);
        page(2, 0);

        assertEquals(1L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100));
    }

    @Test
    public void test_first_fit_ignores_page_insertion_order() {
        page(3, 0);
        page(0, maxPageSize() - 10);
        page(1, 0);

        assertEquals(1L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100),
                "first fit must be by page number, not by the order pages were added");
    }

    @Test
    public void test_a_full_collection_allocates_the_next_page() {
        page(0, maxPageSize());
        page(1, maxPageSize());

        assertEquals(2L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100));
    }

    @Test
    public void test_an_empty_collection_starts_at_page_zero() {
        assertEquals(0L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100));
    }

    @Test
    public void test_pending_bytes_keep_a_bulk_insert_packing_one_page() {
        final var size = (int) (maxPageSize() / 4);
        final var sizes = new int[]{size, size, size, size, size, size};

        assertArrayEquals(new long[]{0L, 0L, 0L, 0L, 1L, 1L}, cache.selectPagesForInsert(TestGlobals.DB, COLL, sizes),
                "the pending overlay must fill a page before allocating the next one");
    }

    @Test
    public void test_pending_bytes_are_added_to_a_committed_page() {
        page(0, maxPageSize() / 2);
        final var sizes = new int[]{(int) (maxPageSize() / 2), 100};

        assertArrayEquals(new long[]{0L, 1L}, cache.selectPagesForInsert(TestGlobals.DB, COLL, sizes),
                "a committed page already filled by pending bytes must be skipped");
    }

    @Test
    public void test_a_pending_only_page_beyond_the_committed_ones_is_considered() {
        page(0, maxPageSize());

        assertEquals(1L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100),
                "without the overlay the next free page is 1");
        assertArrayEquals(new long[]{1L, 1L}, cache.selectPagesForInsert(TestGlobals.DB, COLL, new int[]{10, 100}),
                "a page known only to the overlay must still be a first-fit candidate");
    }

    @Test
    public void test_page_entries_survive_a_concurrent_add_while_a_reader_iterates() throws Exception {
        for (var i = 0; i < 200; i++) {
            page(i, 10);
        }
        final var started = new CountDownLatch(1);
        final var failure = new AtomicReference<Throwable>();

        final var writer = new Thread(() -> {
            try {
                started.await();
                for (var i = 200; i < 400; i++) {
                    page(i, 10);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        final var reader = new Thread(() -> {
            try {
                started.await();
                for (var i = 0; i < 400; i++) {
                    cache.selectPageForInsert(TestGlobals.DB, COLL, 1);
                    cache.getAdminPageEntry(TestGlobals.DB, COLL, 5);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        writer.start();
        reader.start();
        started.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(20));
        reader.join(TimeUnit.SECONDS.toMillis(20));

        assertNull(failure.get(), "page selection must tolerate a concurrent page being added");
        assertEquals(400, cache.getAdminPageEntries(TestGlobals.DB, COLL).size());
    }

    private static java.io.File pageFile(long number) {
        final var folder = new java.io.File(
                TestGlobals.PATH + java.io.File.separator + TestGlobals.DB + java.io.File.separator + COLL);
        return new java.io.File(folder, COLL + Globals.FILE_PAGE_SEPARATOR + number + Globals.DB_FILE_EXTENSION);
    }

    private static void pageFileOfLength(long number, long length) throws IOException {
        final var file = pageFile(number);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (var raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }

    private static void firstPageFileOfLength(long length) throws IOException {
        pageFileOfLength(0, length);
    }

    @Test
    public void test_a_page_whose_file_is_full_is_skipped_unless_a_vacuum_frees_a_quarter() throws IOException {
        page(0, maxPageSize() * 3 / 4 + 1);
        firstPageFileOfLength(maxPageSize());

        assertEquals(1L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100),
                "live room alone is not enough while the file is full and too little of it is dead");
        assertEquals(1L, cache.selectPagesForInsert(TestGlobals.DB, COLL, new int[]{100})[0]);
    }

    @Test
    public void test_a_page_whose_file_is_full_of_dead_bytes_is_still_chosen() throws IOException {
        page(0, maxPageSize() / 2);
        firstPageFileOfLength(maxPageSize());

        assertEquals(0L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100),
                "the appender vacuums a page that is a quarter dead before appending to it");
        assertEquals(0L, cache.selectPagesForInsert(TestGlobals.DB, COLL, new int[]{100})[0]);
    }

    private record CountingLengths(FileSystem realFs, FileSystem spyFs, List<Long> asked) {
    }

    private CountingLengths countingLengths() throws NoSuchFieldException, IllegalAccessException {
        final var pageCache = TestUtils.getPrivateField(cache.adminCache(), "pageCache", Object.class);
        final var realFs = TestUtils.getPrivateField(pageCache, "fs", FileSystem.class);
        final var spyFs = Mockito.spy(realFs);
        final var asked = new ArrayList<Long>();
        Mockito.doAnswer(invocation -> {
            final var real = (LongUnaryOperator) invocation.callRealMethod();
            return (LongUnaryOperator) page -> {
                asked.add(page);
                return real.applyAsLong(page);
            };
        }).when(spyFs).pageFileLengthsOf(TestGlobals.DB, COLL);
        TestUtils.setPrivateField(pageCache, "fs", spyFs);
        return new CountingLengths(realFs, spyFs, asked);
    }

    private void restore(CountingLengths counting) throws NoSuchFieldException, IllegalAccessException {
        final var pageCache = TestUtils.getPrivateField(cache.adminCache(), "pageCache", Object.class);
        TestUtils.setPrivateField(pageCache, "fs", counting.realFs());
    }

    @Test
    public void test_a_page_without_live_room_is_never_statted() throws NoSuchFieldException, IllegalAccessException {
        page(0, maxPageSize());
        page(1, maxPageSize() - 50);
        page(2, 0);
        final var counting = countingLengths();
        try {
            assertEquals(2L, cache.selectPageForInsert(TestGlobals.DB, COLL, 100));

            assertEquals(List.of(2L), counting.asked());
            Mockito.verify(counting.spyFs(), Mockito.never()).pageFileLength(Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong());
        } finally {
            restore(counting);
        }
    }

    @Test
    public void test_a_relocation_over_many_pages_stats_nothing() throws Exception {
        final var pages = 48;
        for (var number = 0; number < pages; number++) {
            page(number, maxPageSize() * 3 / 4 + 1);
            pageFileOfLength(number, maxPageSize());
        }
        assertEquals(pages, cache.selectPageForInsert(TestGlobals.DB, COLL, 100), "every page file is full");
        final var counting = countingLengths();
        try {
            for (var number = 0; number < pages; number++) {
                assertTrue(pageFile(number).delete());
            }

            assertEquals(pages, cache.selectPageForInsert(TestGlobals.DB, COLL, 100),
                    "a stat would find every page gone and pick page 0, so the remembered lengths answered");
            assertEquals(pages, counting.asked().size());
            assertEquals(pages, cache.selectPagesForInsert(TestGlobals.DB, COLL, new int[]{100})[0]);
            Mockito.verify(counting.spyFs(), Mockito.never()).pageFileLength(Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong());
            Mockito.verify(counting.spyFs(), Mockito.never()).pageFileLengths(Mockito.anyString(), Mockito.anyString());
        } finally {
            restore(counting);
        }
    }
}
