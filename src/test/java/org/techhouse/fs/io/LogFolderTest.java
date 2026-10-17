package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LogFolderTest {
    @TempDir
    Path tmp;

    @Test
    public void move_replaces_the_target() throws IOException {
        final var source = Files.writeString(tmp.resolve("2026-10-16.log"), "new");
        final var target = Files.writeString(tmp.resolve("2026-10-16.1.log"), "old");

        LogFolder.move(source, target);

        assertFalse(Files.exists(source));
        assertEquals("new", Files.readString(target));
    }

    @Test
    public void listing_skips_directories() throws IOException {
        final var file = Files.createFile(tmp.resolve("2026-10-16.log"));
        Files.createDirectory(tmp.resolve("2000-01-01.log"));

        assertEquals(List.of(file), LogFolder.listRegularFiles(tmp));
    }

    @Test
    public void listing_a_file_throws() throws IOException {
        final var file = Files.createFile(tmp.resolve("plain"));

        assertThrows(IOException.class, () -> LogFolder.listRegularFiles(file));
    }

    @Test
    public void deleting_a_missing_file_reports_false() throws IOException {
        final var file = Files.createFile(tmp.resolve("2000-01-01.log"));

        assertTrue(LogFolder.delete(file));
        assertFalse(LogFolder.delete(file));
    }

    @Test
    public void deleting_a_non_empty_folder_reports_false() throws IOException {
        final var folder = Files.createDirectory(tmp.resolve("full"));
        Files.createFile(folder.resolve("inner"));

        assertFalse(LogFolder.delete(folder));
    }

    @Test
    public void create_file_if_absent_creates_once_and_keeps_an_existing_file() throws IOException {
        final var file = tmp.resolve("2026-10-16.log");

        assertTrue(LogFolder.createFileIfAbsent(file));
        Files.writeString(file, "kept");
        assertTrue(LogFolder.createFileIfAbsent(file));

        assertEquals("kept", Files.readString(file));
    }

    @Test
    public void create_file_if_absent_in_a_missing_folder_throws() {
        assertThrows(IOException.class,
                () -> LogFolder.createFileIfAbsent(tmp.resolve("missing").resolve("2026-10-16.log")));
    }
}
