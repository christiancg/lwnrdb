package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CacheSkipSourceTest {
    private static final int DOCUMENTS = 120;
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private long shippedPageSize;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        shippedPageSize = configuration.getMaxPageSize();
        TestUtils.setPrivateField(configuration, "maxPageSize", 1024L);
        TestUtils.createTestDatabaseAndCollection();
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var doc = new JsonObject();
            doc.addProperty("_id", String.format("d%03d", i));
            doc.addProperty("payload", "value-" + i + "-".repeat(i % 7 * 10));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(objects);
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeBulkSave(bulk);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", shippedPageSize);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static List<String> ids(Stream<DbEntry> entries) {
        try (entries) {
            return entries.map(DbEntry::get_id).toList();
        }
    }

    private List<String> underReadLock(SkipRead read) throws Exception {
        assertTrue(locks.tryLockRead(TestGlobals.DB, TestGlobals.COLL, 5_000));
        try {
            return read.ids();
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private interface SkipRead {
        List<String> ids() throws Exception;
    }

    private long pageCount() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).size();
    }

    @Test
    public void test_a_skip_under_the_lock_tiles_the_plain_scan_across_page_boundaries() throws Exception {
        assertTrue(pageCount() > 3, "the fixture must span several pages");
        final var whole = underReadLock(() -> ids(cache.streamCollectionSkipping(TestGlobals.DB, TestGlobals.COLL, 1)));
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
        for (var skip = 1; skip <= DOCUMENTS + 2; skip += 7) {
            final var at = skip;
            final var skipped = underReadLock(
                    () -> ids(cache.streamCollectionSkipping(TestGlobals.DB, TestGlobals.COLL, at)));
            assertEquals(whole.subList(Math.min(at - 1, whole.size()), whole.size()), skipped, "skip " + at);
            assertFalse(cache.holdsWholeCollection(TestGlobals.DB, TestGlobals.COLL), "skip " + at + " admitted");
        }
    }

    @Test
    public void test_a_skip_under_the_lock_never_reads_the_skipped_pages() throws Exception {
        final var fs = IocContainer.get(FileSystem.class);
        final var spy = Mockito.spy(fs);
        final var firstPage = cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream()
                .mapToLong(AdminPageEntry::getPage).min().orElseThrow();
        final var firstPageRows = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(row -> row.getPage() == firstPage).count();
        TestUtils.setPrivateField(cache, "fs", spy);
        try {
            final var skipped = underReadLock(
                    () -> ids(cache.streamCollectionSkipping(TestGlobals.DB, TestGlobals.COLL, firstPageRows + 1)));
            assertEquals(DOCUMENTS - firstPageRows - 1, skipped.size());
            verify(spy, never()).readWholeCollectionPage(eq(TestGlobals.DB), eq(TestGlobals.COLL), eq(firstPage));
            verify(spy, Mockito.atLeastOnce()).readWholeCollectionPage(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    anyLong());
        } finally {
            TestUtils.setPrivateField(cache, "fs", fs);
        }
    }

    @Test
    public void test_a_skip_without_the_lock_answers_like_a_plain_skip() throws Exception {
        final var plain = ids(cache.streamCollection(TestGlobals.DB, TestGlobals.COLL).skip(37));

        final var skipped = ids(cache.streamCollectionSkipping(TestGlobals.DB, TestGlobals.COLL, 37));

        assertEquals(plain, skipped);
        assertEquals(DOCUMENTS - 37, skipped.size());
    }

    @Test
    public void test_a_zero_skip_scans_everything_and_admits_the_collection() throws Exception {
        final var all = underReadLock(() -> ids(cache.streamCollectionSkipping(TestGlobals.DB, TestGlobals.COLL, 0)));

        assertEquals(DOCUMENTS, all.size());
        assertTrue(cache.holdsWholeCollection(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(ids(cache.streamCollection(TestGlobals.DB, TestGlobals.COLL).skip(5)),
                ids(cache.streamCollectionSkipping(TestGlobals.DB, TestGlobals.COLL, 5)));
    }
}
