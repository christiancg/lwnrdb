package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
    private static final String WAL = COLL + Globals.WAL_EXTENSION;

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

    private static List<String> filesOnly(List<String> forced) {
        return forced.stream().filter(name -> !name.equals(COLL) && !name.equals(DB)).toList();
    }

    private static List<String> writeAheadLogOnly() {
        return List.of(WAL);
    }

    @Test
    public void test_insert_forces_the_marker_then_only_the_log() throws Exception {
        new IndexMarkers(rf.fx.paths).markDirty(DB, COLL);
        rf.appender.insert(rf.entry("d", "new-d", 0), ReplacerFixture.line("d", "new-d"));

        assertEquals(List.of(COLL + "-indexes.dirty", WAL), filesOnly(recorder.forcedNames()));
    }

    @Test
    public void test_update_forces_only_the_log() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(writeAheadLogOnly(), filesOnly(recorder.forcedNames()));
        assertEquals(0, rf.fx.openIntents(), "an update writes no intent");
    }

    @Test
    public void test_delete_forces_only_the_log() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));

        assertEquals(writeAheadLogOnly(), filesOnly(recorder.forcedNames()));
        assertEquals(0, rf.fx.openIntents());
    }

    @Test
    public void test_a_relocation_forces_only_the_log() throws Exception {
        rf.replace("b", "moved-b", 1, null);

        assertEquals(writeAheadLogOnly(), filesOnly(recorder.forcedNames()));
    }

    @Test
    public void test_bulk_insert_forces_only_the_log_once() throws Exception {
        final var entries = new ArrayList<>(List.of(rf.entry("x", "x", 0), rf.entry("y", "y", 1)));

        rf.appender.bulkInsert(DB, COLL, entries, DbEntry::getPage,
                entry -> ReplacerFixture.line(entry.get_id(), entry.get_id()));

        assertEquals(writeAheadLogOnly(), filesOnly(recorder.forcedNames()));
    }

    @Test
    public void test_bulk_update_forces_only_the_log_once() throws Exception {
        rf.bulk(List.of(rf.indexed("a", "bulk-a"), rf.indexed("c", "bulk-c")));

        assertEquals(writeAheadLogOnly(), filesOnly(recorder.forcedNames()));
    }

    @Test
    public void test_unsynced_writes_force_nothing() throws Exception {
        recorder.close();
        recorder = RecordingForcer.install(false);

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(List.of(), recorder.forced());
    }

    @Test
    public void test_a_checkpoint_forces_the_touched_pages_and_pk_before_it_rewrites_the_log() throws Exception {
        rf.replace("b", "moved-b", 1, null);
        recorder.clear();

        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);

        final var forced = filesOnly(recorder.forcedNames());
        assertEquals(Set.of(PAGE, COLL + "-1.dat", PK), Set.copyOf(forced.subList(0, 3)), forced.toString());
        assertEquals(WAL + ".repair", forced.get(3));
    }

    @Test
    public void test_a_checkpoint_of_an_empty_log_forces_nothing() throws Exception {
        final var log = new WriteAheadLog(rf.fx.paths);
        log.checkpoint(DB, COLL);
        recorder.clear();

        log.checkpoint(DB, COLL);

        assertEquals(List.of(), recorder.forced());
    }

    @Test
    public void test_a_vacuum_checkpoints_before_and_after() throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        recorder.clear();

        rf.vacuum.vacuumIfNeeded(DB, COLL, 0, 10, PagePlacement.VACUUM_THEN_APPEND, null);

        assertInOrder(recorder.forcedNames(), PAGE, WAL + ".repair", INTENTS,
                COLL + "-0" + Globals.VACUUM_FILE_EXTENSION, WAL, WAL + ".repair");
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
    public void test_a_log_that_cannot_be_forced_fails_the_write_before_any_file_is_touched() throws Exception {
        final var pageBefore = Files.readAllBytes(rf.fx.page(0).toPath());
        final var pkBefore = Files.readAllBytes(rf.fx.paths.pkIndexFile(DB, COLL).toPath());
        recorder.failingFor(WAL);

        assertThrows(IOException.class,
                () -> rf.appender.insert(rf.entry("d", "new-d", 0), ReplacerFixture.line("d", "new-d")));

        assertArrayEquals(pageBefore, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertArrayEquals(pkBefore, Files.readAllBytes(rf.fx.paths.pkIndexFile(DB, COLL).toPath()));
    }
}
