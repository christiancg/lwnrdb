package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AppendingFileTest {
    private static final int BUFFER = 64;

    @TempDir
    Path tmp;

    @Test
    public void append_tracks_length() throws IOException {
        final var file = tmp.resolve("2026-10-16.log");
        try (var appender = AppendingFile.open(file, BUFFER)) {
            assertEquals(0, appender.length());
            appender.append("café");
            appender.flush();
            assertEquals(5, appender.length());
            assertEquals(5, Files.size(file));
        }
    }

    @Test
    public void a_new_file_is_owner_only() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported());
        final var file = tmp.resolve("2026-10-16.log");
        try (var appender = AppendingFile.open(file, BUFFER)) {
            appender.append("x");
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    public void reopening_appends_rather_than_truncates() throws IOException {
        final var file = tmp.resolve("2026-10-16.log");
        try (var appender = AppendingFile.open(file, BUFFER)) {
            appender.append("first");
        }
        try (var appender = AppendingFile.open(file, BUFFER)) {
            assertEquals(5, appender.length());
            appender.append("-second");
        }
        assertEquals("first-second", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void presence_follows_the_file_on_disk() throws IOException {
        final var file = tmp.resolve("2026-10-16.log");
        try (var appender = AppendingFile.open(file, BUFFER)) {
            assertTrue(appender.isPresent());
            Files.delete(file);
            assertFalse(appender.isPresent());
        }
    }
}
