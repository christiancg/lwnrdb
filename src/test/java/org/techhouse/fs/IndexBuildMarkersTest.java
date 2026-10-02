package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class IndexBuildMarkersTest {
    private static final String DB = "markerDb";
    private static final String COLL = "markerColl";

    @TempDir
    File tmp;

    private FilePaths paths;
    private IndexBuildMarkers markers;

    @BeforeEach
    public void setUp() {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(DB, COLL).mkdirs());
        markers = new IndexBuildMarkers(paths);
    }

    @Test
    public void test_a_marked_field_stays_marked_until_cleared() throws IOException {
        markers.mark(DB, COLL, "name");

        assertTrue(markers.isMarked(DB, COLL, "name"));
        assertFalse(markers.isMarked(DB, COLL, "other"));

        markers.clear(DB, COLL, "name");

        assertFalse(markers.isMarked(DB, COLL, "name"));
        assertFalse(new File(paths.collectionFolder(DB, COLL), COLL + "-name.building").exists());
    }

    @Test
    public void test_markers_survive_a_restart() throws IOException {
        markers.mark(DB, COLL, "a.building");
        markers.mark(DB, COLL, "name");

        final var reloaded = new IndexBuildMarkers(paths);

        assertTrue(reloaded.isMarked(DB, COLL, "a.building"));
        assertTrue(reloaded.isMarked(DB, COLL, "name"));
        assertEquals(List.of(DB + "|" + COLL + "|a.building", DB + "|" + COLL + "|name"), reloaded.listMarked());
    }

    @Test
    public void test_a_marker_whose_file_vanished_with_its_collection_is_forgotten() throws IOException {
        markers.mark(DB, COLL, "name");
        assertTrue(new File(paths.collectionFolder(DB, COLL), COLL + "-name.building").delete());

        assertFalse(markers.isMarked(DB, COLL, "name"));
        assertTrue(markers.listMarked().isEmpty());
    }

    @Test
    public void test_a_marker_belongs_only_to_its_own_collection_folder() throws IOException {
        final var hyphenated = COLL + "-name";
        assertTrue(paths.collectionFolder(DB, hyphenated).mkdirs());
        markers.mark(DB, COLL, "name");

        final var reloaded = new IndexBuildMarkers(paths);

        assertFalse(reloaded.isMarked(DB, hyphenated, "building"));
        assertTrue(reloaded.isMarked(DB, COLL, "name"));
    }

    @Test
    public void test_an_index_drop_leaves_the_marker_in_place() throws IOException {
        markers.mark(DB, COLL, "name");

        new FieldIndexStore(paths).dropIndex(DB, COLL, "name");

        assertTrue(markers.isMarked(DB, COLL, "name"));
    }

    @Test
    public void test_a_collection_with_no_folder_yet_has_no_files_to_protect() throws IOException {
        markers.mark(DB, "not_written_yet", "name");

        assertFalse(markers.isMarked(DB, "not_written_yet", "name"));
        assertFalse(paths.collectionFolder(DB, "not_written_yet").exists());
    }
}
