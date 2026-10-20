package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.test.TestUtils;

public class SyncedWriteOrderTest {
    private static final String DB = "orderDb";
    private static final String COLL = "orderColl";
    private static final String PAGE = COLL + "-0.dat";
    private static final String PK = COLL + "-pk.idx";
    private static final String INTENTS = COLL + Globals.INTENT_LOG_EXTENSION;

    @TempDir
    File tmp;

    private ReplacerFixture rf;
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        recorder = RecordingForcer.install(true);
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
        recorder.clear();
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
    }

    private static void assertInOrder(List<String> forced, String... expected) {
        var from = 0;
        for (final var name : expected) {
            final var at = forced.subList(from, forced.size()).indexOf(name);
            assertTrue(at >= 0, name + " is not forced after position " + from + " in " + forced);
            from += at + 1;
        }
    }

    private static int count(List<String> forced, String name) {
        return (int) forced.stream().filter(name::equals).count();
    }

    private static List<String> filesOnly(List<String> forced) {
        return forced.stream().filter(name -> !name.equals(COLL) && !name.equals(DB)).toList();
    }

    private int openIntents() throws IOException {
        return rf.fx.openIntents();
    }

    @Test
    public void test_insert_forces_marker_then_page_then_pk() throws Exception {
        new IndexMarkers(rf.fx.paths).markDirty(DB, COLL);
        rf.appender.insert(rf.entry("d", "new-d", 0), ReplacerFixture.line("d", "new-d"));

        assertInOrder(recorder.forcedNames(), COLL + "-indexes.dirty", PAGE, PK);
    }

    @Test
    public void test_update_forces_intent_and_page_together_then_pk_and_defers_the_blank() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        final var forced = filesOnly(recorder.forcedNames());
        assertEquals(Set.of(INTENTS, PAGE), Set.copyOf(forced.subList(0, 2)), forced.toString());
        assertEquals(List.of(PK), forced.subList(2, forced.size()), "the blank is not forced before the OK");
        assertEquals(1, openIntents(), "the intent stays open until its blank is durable");
    }

    @Test
    public void test_delete_forces_intent_then_pk_and_defers_the_blank() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));

        assertEquals(List.of(INTENTS, PK), filesOnly(recorder.forcedNames()));
        assertEquals(1, openIntents());
    }

    @Test
    public void test_the_next_write_forces_the_previous_blank_and_ends_its_intent() throws Exception {
        rf.replace("b", "moved-b", 1, null);
        recorder.clear();

        rf.replace("c", "new-c", 1, PagePlacement.APPEND);

        final var forced = filesOnly(recorder.forcedNames());
        assertEquals(Set.of(INTENTS, COLL + "-1.dat", PAGE), Set.copyOf(forced.subList(0, 3)), forced.toString());
        assertEquals(PK, forced.get(3));
        assertEquals(1, openIntents(), "the earlier intent ended once its blank was forced; only the new one is open");
    }

    @Test
    public void test_a_blank_on_the_page_the_next_write_appends_to_costs_no_extra_force() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        recorder.clear();

        rf.replace("c", "new-c", 0, PagePlacement.APPEND);

        assertEquals(1, count(recorder.forcedNames(), PAGE));
    }

    @Test
    public void test_shutdown_settles_every_deferred_blank() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        rf.replacer.delete(rf.fx.indexed("c"));
        recorder.clear();

        WriteIntentJournal.settleEveryDeferredEnd(Long.MAX_VALUE);

        assertEquals(List.of(PAGE), filesOnly(recorder.forcedNames()));
        assertEquals(0, openIntents());
        assertEquals(0L, rf.fx.paths.intentLog(DB, COLL).length());
    }

    @Test
    public void test_shutdown_out_of_time_leaves_the_deferred_blanks_to_startup() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));
        recorder.clear();

        WriteIntentJournal.settleEveryDeferredEnd(0L);

        assertEquals(List.of(), filesOnly(recorder.forcedNames()));
        assertEquals(1, openIntents());
    }

    @Test
    public void test_a_collection_settles_its_deferred_blanks_on_demand() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));

        rf.fx.journal.settleDeferredEnds(DB, COLL);

        assertEquals(0, openIntents());
    }

    @Test
    public void test_a_dropped_collection_discards_its_deferred_blanks() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        assertTrue(FileTreeDeleter.delete(folder));

        WriteIntentJournal.settleEveryDeferredEnd(Long.MAX_VALUE);

        assertFalse(folder.exists(), "settling must not recreate a dropped collection's intent log");
    }

    @Test
    public void test_unsynced_writes_end_the_intent_at_once() throws Exception {
        recorder.close();
        recorder = RecordingForcer.install(false);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(0, openIntents());
        assertEquals(List.of(), recorder.forced());
    }

    @Test
    public void test_a_group_forces_its_files_at_the_same_time() throws Exception {
        final var inFlight = new CountDownLatch(2);
        recorder.close();
        recorder = RecordingForcer.install(true);
        TestUtils.setPrivateStaticField(SyncedWrites.class, "forcer", (Forcer) (_, _) -> {
            inFlight.countDown();
            try {
                if (!inFlight.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("the other force never started while this one was in flight");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        });
        final var group = new ForceGroup();
        group.add(rf.fx.page(0).toPath());
        group.add(rf.fx.paths.pkIndexFile(DB, COLL).toPath());

        group.force();

        assertEquals(0L, inFlight.getCount());
    }

    @Test
    public void test_bulk_save_forces_pages_before_the_single_pk_append() throws Exception {
        final var entries = new ArrayList<>(List.of(rf.entry("x", "x", 0), rf.entry("y", "y", 1)));

        rf.appender.bulkInsert(DB, COLL, entries, DbEntry::getPage,
                entry -> ReplacerFixture.line(entry.get_id(), entry.get_id()));

        final var forced = recorder.forcedNames();
        assertEquals(1, count(forced, PK));
        assertInOrder(forced, PAGE, PK);
        assertInOrder(forced, COLL + "-1.dat", PK);
    }

    @Test
    public void test_atomic_rewrite_forces_tmp_before_the_move_and_the_folder_after() throws Exception {
        final var schema = new File(rf.fx.paths.collectionFolder(DB, COLL), COLL + "-schema.json");
        MetadataFileStore.write(schema, "{}");
        rf.fx.store.rewriteRows(DB, COLL, ArrayList::new);

        final var forced = recorder.forcedNames();
        assertInOrder(forced, schema.getName() + ".repair", COLL, PK + ".repair", COLL);
        assertFalse(forced.contains(schema.getName()), "the move, not a rewrite in place, publishes the file");
    }

    @Test
    public void test_vacuum_forces_the_vacuum_file_before_the_rename() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        recorder.clear();

        rf.vacuum.vacuumIfNeeded(DB, COLL, 0, 10, PagePlacement.VACUUM_THEN_APPEND, null);

        assertInOrder(recorder.forcedNames(), INTENTS, COLL + "-0" + Globals.VACUUM_FILE_EXTENSION, COLL);
    }

    @Test
    public void test_tombstone_append_is_forced() throws Exception {
        new TombstoneStore(rf.fx.paths).append(DB, COLL, "gone", 7L);

        assertTrue(recorder.forcedNames().stream().anyMatch(name -> name.contains(Globals.TOMBSTONE_FILE_NAME)),
                recorder.forcedNames().toString());
    }

    @Test
    public void test_index_log_append_is_forced_before_the_marker_clear() throws Exception {
        final var markers = new IndexMarkers(rf.fx.paths);
        markers.markDirty(DB, COLL);
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);
        final var log = new File(folder, COLL + "-price-Number.idxlog");
        recorder.clear();

        FieldIndexLog.append(new File(folder, COLL + "-price-Number.idx"), log,
                List.of(new IndexLogChange("1", List.of("a"), List.of())));
        markers.clearDirty(DB, COLL);

        assertEquals(List.of(log.getName()),
                recorder.forcedNames().stream().filter(name -> !name.equals(COLL)).toList());
    }

    @Test
    public void test_intent_end_and_marker_clear_are_not_forced() throws Exception {
        final var markers = new IndexMarkers(rf.fx.paths);
        markers.markDirty(DB, COLL);
        final var intent = rf.fx.journal.begin(DB, COLL, List.of(new WriteIntent.Vacuum(0)));
        recorder.clear();

        rf.fx.journal.end(intent);
        markers.clearDirty(DB, COLL);

        assertEquals(List.of(), recorder.forced());
        assertEquals(0L, rf.fx.paths.intentLog(DB, COLL).length());
    }

    @Test
    public void test_drop_collection_forces_the_database_folder() {
        final var collectionFolder = rf.fx.paths.collectionFolder(DB, COLL);

        assertTrue(FileTreeDeleter.delete(collectionFolder));

        assertEquals(List.of(collectionFolder.getParentFile().toPath().toAbsolutePath().normalize()),
                recorder.forced());
    }

    @Test
    public void test_truncate_on_failure_is_forced() {
        final var pageBefore = rf.fx.page(0).length();
        recorder.failingFor(PK);

        assertThrows(IOException.class,
                () -> rf.appender.insert(rf.entry("d", "new-d", 0), ReplacerFixture.line("d", "new-d")));

        assertEquals(pageBefore, rf.fx.page(0).length());
        final var forced = recorder.forcedNames();
        assertEquals(PAGE, forced.getLast(), "the page is cut back and that cut is forced: " + forced);
        assertEquals(2, count(forced, PAGE));
    }
}
