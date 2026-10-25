package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageRowWriteBackTest {
    private static final String OTHER = TestGlobals.JOIN_COLL;
    private static final long INCARNATION = 200L;
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIncarnation(INCARNATION);
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static DbEntry entryOnPage(String collName, String id, long page) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("payload", new JsonString("some bytes"));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, collName, object);
        entry.set_id(id);
        entry.setPage(page);
        return entry;
    }

    private static void created(String collName, String id, long page) throws Exception {
        EventProcessorHelper.processEvent(new EntityEvent(EventType.CREATED, TestGlobals.DB, collName,
                entryOnPage(collName, id, page), CollectionIncarnation.current(TestGlobals.DB, collName)));
    }

    private static void deleteFirstOnPageZero() throws Exception {
        final var entry = entryOnPage(TestGlobals.COLL, "a", 0);
        IocContainer.get(Cache.class).updatePageSizeInMemory(TestGlobals.DB, TestGlobals.COLL, 0, -entry.byteSize(),
                -1);
        EventProcessorHelper.processEvent(new EntityEvent(EventType.DELETED, TestGlobals.DB, TestGlobals.COLL, entry,
                CollectionIncarnation.current(TestGlobals.DB, TestGlobals.COLL)));
    }

    private Map<Long, Integer> rowsOnDisk(String collName) throws IOException {
        final var pagesCollection = AdminPageEntry.pagesCollectionName(TestGlobals.DB, collName);
        final var prefix = TestGlobals.DB + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + Globals.COLL_IDENTIFIER_SEPARATOR;
        try (var rows = fs.streamEntries(Globals.ADMIN_PAGES_DB_NAME, pagesCollection)) {
            return rows.filter(row -> row.get_id().startsWith(prefix))
                    .map(row -> AdminPageEntry.fromJsonObject(TestGlobals.DB, collName, row.getData()))
                    .collect(Collectors.toMap(AdminPageEntry::getPage, AdminPageEntry::getEntryCount,
                            (_, latest) -> latest, TreeMap::new));
        }
    }

    private Map<Long, Integer> rowsInMemory() {
        return cache.getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL).stream().collect(Collectors
                .toMap(AdminPageEntry::getPage, AdminPageEntry::getEntryCount, (_, latest) -> latest, TreeMap::new));
    }

    private boolean pageRowsForced(String dbName, String collName) {
        final var log = AdminPageEntry.pagesCollectionName(dbName, collName) + Globals.WAL_EXTENSION;
        return recorder.forcedNames().contains(log);
    }

    @Test
    public void test_an_event_writes_no_page_row() throws Exception {
        recorder.clear();

        created(TestGlobals.COLL, "a", 0);

        assertFalse(pageRowsForced(TestGlobals.DB, TestGlobals.COLL), recorder.forcedNames().toString());
        assertEquals(Map.of(0L, 1), rowsInMemory());
        assertEquals(Map.of(), rowsOnDisk(TestGlobals.COLL));
    }

    @Test
    public void test_rows_dirty_past_the_interval_are_written_once() throws Exception {
        created(TestGlobals.COLL, "a", 0);
        created(TestGlobals.COLL, "c", 2);
        deleteFirstOnPageZero();
        final var now = System.currentTimeMillis();

        PageRowWriteBack.flushDue(now - Globals.PAGE_ROWS_WRITE_BACK_MS);
        assertEquals(Map.of(), rowsOnDisk(TestGlobals.COLL), "not due yet");

        PageRowWriteBack.flushDue(now + Globals.PAGE_ROWS_WRITE_BACK_MS);
        assertEquals(rowsInMemory(), rowsOnDisk(TestGlobals.COLL));
        assertEquals(Map.of(0L, 0, 2L, 1), rowsOnDisk(TestGlobals.COLL));

        recorder.clear();
        PageRowWriteBack.flushDue(now + 2 * Globals.PAGE_ROWS_WRITE_BACK_MS);
        assertFalse(pageRowsForced(TestGlobals.DB, TestGlobals.COLL), "a flushed collection is clean");
    }

    @Test
    public void test_a_later_change_updates_the_row_written_before() throws Exception {
        created(TestGlobals.COLL, "a", 0);
        PageRowWriteBack.flushAll();
        deleteFirstOnPageZero();

        PageRowWriteBack.flushAll();

        assertEquals(Map.of(0L, 0), rowsOnDisk(TestGlobals.COLL));
    }

    @Test
    public void test_flush_all_writes_every_dirty_collection() throws Exception {
        TestUtils.createTestJoinCollection();
        created(TestGlobals.COLL, "a", 0);
        created(OTHER, "x", 1);

        PageRowWriteBack.flushAll();

        assertEquals(Map.of(0L, 1), rowsOnDisk(TestGlobals.COLL));
        assertEquals(Map.of(1L, 1), rowsOnDisk(OTHER));
    }

    @Test
    public void test_a_recreated_collection_does_not_inherit_dirty_rows() throws Exception {
        created(TestGlobals.COLL, "a", 0);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIncarnation(INCARNATION + 1);

        PageRowWriteBack.flushAll();

        assertEquals(Map.of(), rowsOnDisk(TestGlobals.COLL));
    }

    @Test
    public void test_a_dropped_collections_rows_are_discarded() throws Exception {
        created(TestGlobals.COLL, "a", 0);

        AdminPageHelper.deletePageCollections(TestGlobals.DB, TestGlobals.COLL);
        recorder.clear();
        PageRowWriteBack.flushAll();

        assertFalse(pageRowsForced(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_failed_write_back_is_retried_by_the_next_flush() throws Exception {
        created(TestGlobals.COLL, "a", 0);
        recorder.failingFor(
                AdminPageEntry.pagesCollectionName(TestGlobals.DB, TestGlobals.COLL) + Globals.WAL_EXTENSION);

        PageRowWriteBack.flushAll();
        assertEquals(Map.of(), rowsOnDisk(TestGlobals.COLL), "the forced write failed and was taken back");
        recorder.failingFor(null);
        PageRowWriteBack.flushAll();

        assertEquals(Map.of(0L, 1), rowsOnDisk(TestGlobals.COLL), "the rows stayed dirty for the next flush");
    }

    @Test
    public void test_admin_collection_rows_are_written_back() {
        PageRowWriteBack.flushAll();
        recorder.clear();

        AdminPageHelper.recordLandedDelta(Globals.ADMIN_DATABASES_COLLECTION_NAME, EventType.CREATED,
                List.of(entryOnPage(Globals.ADMIN_DATABASES_COLLECTION_NAME, "landed", 0)));

        assertFalse(pageRowsForced(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME),
                recorder.forcedNames().toString());
        PageRowWriteBack.flushAll();
        assertTrue(pageRowsForced(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME),
                recorder.forcedNames().toString());
    }

    @Test
    public void test_touches_racing_a_flush_never_lose_a_page() throws Exception {
        final var pages = 40;
        final var writers = 4;
        final var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(writers + 1)) {
            try {
                final var flusher = pool.submit(() -> {
                    while (running.get()) {
                        PageRowWriteBack.flushAll();
                    }
                });
                final var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (var writer = 0; writer < writers; writer++) {
                    final var offset = writer;
                    tasks.add(pool.submit(() -> {
                        for (var page = offset; page < pages; page += writers) {
                            created(TestGlobals.COLL, "id-" + page, page);
                        }
                        return null;
                    }));
                }
                for (final var task : tasks) {
                    task.get(60, java.util.concurrent.TimeUnit.SECONDS);
                }
                running.set(false);
                flusher.get(60, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                running.set(false);
            }
        }

        PageRowWriteBack.flushAll();

        assertEquals(rowsInMemory(), rowsOnDisk(TestGlobals.COLL));
        assertEquals(pages, rowsOnDisk(TestGlobals.COLL).size());
    }

    @Test
    public void test_a_retry_keeps_pages_touched_while_the_write_back_was_failing() throws Exception {
        created(TestGlobals.COLL, "a", 0);
        recorder.failingFor(
                AdminPageEntry.pagesCollectionName(TestGlobals.DB, TestGlobals.COLL) + Globals.WAL_EXTENSION);
        PageRowWriteBack.flushAll();
        created(TestGlobals.COLL, "b", 1);

        recorder.failingFor(null);
        PageRowWriteBack.flushAll();

        assertEquals(Map.of(0L, 1, 1L, 1), rowsOnDisk(TestGlobals.COLL));
    }
}
