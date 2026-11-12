package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.test.TestGlobals;

public class SnapshotImageFileTest extends FileSystemTestBase {
    private static final String SNAPSHOT_ID = "0b6d1c8e-2f6a-4f7e-9e51-6c3a2d9b1f00";

    private static File snapshotsRoot() {
        return new File(TestGlobals.PATH, Globals.SNAPSHOT_FOLDER_NAME);
    }

    private static File images() {
        return new File(new File(snapshotsRoot(), SNAPSHOT_ID), TestGlobals.COLL + Globals.SNAPSHOT_IMAGES_EXTENSION);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void test_appended_lines_read_back_at_their_offsets() throws IOException {
        try (var images = fs.snapshotFiles().images(SNAPSHOT_ID, TestGlobals.COLL)) {
            final var first = images.append(bytes("{\"_id\":\"a\"}\n"));
            final var second = images.append(bytes("{\"_id\":\"b\",\"v\":1}\n"));

            assertEquals(0L, first);
            assertEquals(12L, second);
            assertArrayEquals(bytes("{\"_id\":\"b\",\"v\":1}\n"), images.read(second, 18));
            assertArrayEquals(bytes("{\"_id\":\"a\"}\n"), images.read(first, 12));
            assertEquals(30L, images.bytes());
        }
    }

    @Test
    public void test_nothing_is_created_until_the_first_append() throws IOException {
        try (var images = fs.snapshotFiles().images(SNAPSHOT_ID, TestGlobals.COLL)) {
            assertEquals(0L, images.bytes());
            assertFalse(images().exists());
        }
    }

    @Test
    public void test_a_read_past_the_end_is_an_io_error() throws IOException {
        try (var images = fs.snapshotFiles().images(SNAPSHOT_ID, TestGlobals.COLL)) {
            images.append(bytes("{}\n"));

            assertThrows(IOException.class, () -> images.read(0, 10));
        }
    }

    @Test
    public void test_the_folder_and_file_are_owner_only() throws IOException {
        try (var images = fs.snapshotFiles().images(SNAPSHOT_ID, TestGlobals.COLL)) {
            images.append(bytes("{}\n"));
        }

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(images().toPath()));
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(images().getParentFile().toPath()));
    }

    @Test
    public void test_deleting_a_snapshot_folder_removes_its_images() throws IOException {
        try (var images = fs.snapshotFiles().images(SNAPSHOT_ID, TestGlobals.COLL)) {
            images.append(bytes("{}\n"));
        }

        assertTrue(fs.snapshotFiles().deleteSnapshotFolder(SNAPSHOT_ID));

        assertFalse(images().getParentFile().exists());
        assertTrue(fs.snapshotFiles().deleteSnapshotFolder(SNAPSHOT_ID), "an absent folder is already deleted");
    }

    @Test
    public void test_clear_snapshot_folders_removes_leftovers_of_a_previous_process() throws IOException {
        try (var images = fs.snapshotFiles().images(SNAPSHOT_ID, TestGlobals.COLL)) {
            images.append(bytes("{}\n"));
        }

        assertTrue(fs.snapshotFiles().clearSnapshotFolders());

        assertFalse(snapshotsRoot().exists());
        assertTrue(fs.snapshotFiles().clearSnapshotFolders());
    }
}
