package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LandedSyncTest {
    private static final String DB = "landedDb";
    private static final String COLL = "landedColl";

    @TempDir
    File tmp;

    private ReplacerFixture rf;
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        recorder = RecordingForcer.install(true);
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
    }

    private File folder() {
        return rf.fx.paths.collectionFolder(DB, COLL);
    }

    @Test
    public void test_a_rewrite_whose_folder_sync_fails_after_the_move_still_lands() throws IOException {
        recorder.failingTheSecondFolderSync();
        final var schema = new File(folder(), COLL + "-schema.json");

        MetadataFileStore.write(schema, "{\"type\":\"object\"}");

        assertEquals("{\"type\":\"object\"}", Files.readString(schema.toPath()).trim());
    }

    @Test
    public void test_a_delete_whose_folder_sync_fails_is_reported_done() throws IOException {
        final var schema = new File(folder(), COLL + "-schema.json");
        MetadataFileStore.write(schema, "{}");
        recorder.failingFolders();

        assertTrue(MetadataFileStore.delete(schema));
        assertFalse(schema.exists());
    }

    @Test
    public void test_a_temp_file_whose_folder_cannot_be_synced_stops_the_rewrite_before_it_lands() throws IOException {
        final var schema = new File(folder(), COLL + "-schema.json");
        MetadataFileStore.write(schema, "{}");
        recorder.failingFolders();

        assertThrows(IOException.class, () -> MetadataFileStore.write(schema, "{\"changed\":true}"));

        assertEquals("{}", Files.readString(schema.toPath()).trim());
    }

    @Test
    public void test_a_created_folder_whose_sync_fails_is_reported_created() {
        recorder.failingFolders();

        assertTrue(OwnerOnly.createDirectory(new File(tmp, "freshDb").toPath()));
    }

    @Test
    public void test_a_vacuum_whose_folder_sync_fails_still_moves_its_rows_and_tells_the_cache() throws Exception {
        rf.replacer.delete(rf.fx.indexed("b"));
        WriteIntentJournal.settleEveryDeferredEnd(Long.MAX_VALUE);
        recorder.failingTheSecondFolderSync();

        final var vacuumed = rf.vacuum.vacuum(DB, COLL, 0, PageRows.of(rf.fx.store, null, DB, COLL, 0));

        assertEquals(List.of(vacuumed), rf.vacuumed);
        assertTrue(rf.fx.everyRowReadsItsOwnRecord());
        assertEquals(0, rf.fx.openIntents());
    }

    @Test
    public void test_a_rollback_that_cannot_cut_its_copy_back_keeps_the_intent() throws IOException {
        recorder.failingFor(".dat");

        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));

        assertEquals(1, rf.fx.openIntents(), "the copy may still be on the page, so startup must settle it");
    }
}
