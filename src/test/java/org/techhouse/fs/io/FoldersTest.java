package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FoldersTest {
    @TempDir
    Path tmp;

    @Test
    public void a_missing_folder_is_created_owner_only() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported());
        final var folder = tmp.resolve("db").resolve("data");

        final var check = Folders.ensureWritable(folder);

        assertTrue(check.ok());
        assertTrue(check.created());
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)));
    }

    @Test
    public void an_existing_writable_folder_is_ok() {
        final var check = Folders.ensureWritable(tmp);

        assertTrue(check.ok());
        assertFalse(check.created());
        assertNull(check.failure());
    }

    @Test
    public void a_read_only_folder_is_not_writable() throws IOException {
        final var folder = Files.createDirectory(tmp.resolve("readonly"));
        assumeTrue(folder.toFile().setWritable(false));
        try {
            assumeTrue(!Files.isWritable(folder));
            final var check = Folders.ensureWritable(folder);
            assertFalse(check.ok());
            assertFalse(check.writable());
            assertNull(check.failure());
        } finally {
            assertTrue(folder.toFile().setWritable(true));
        }
    }

    @Test
    public void a_path_under_a_file_fails_with_its_message() throws IOException {
        final var file = Files.writeString(tmp.resolve("plain"), "x");

        final var check = Folders.ensureWritable(file.resolve("child"));

        assertFalse(check.ok());
        assertFalse(check.created());
        assertNotNull(check.failure());
    }
}
