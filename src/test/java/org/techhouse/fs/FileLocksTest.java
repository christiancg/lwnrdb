package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileLocksTest {

    @Test
    public void test_truncate_to_restores_the_recorded_length(@TempDir File tmp) throws IOException {
        final var file = new File(tmp, "page.dat");
        Files.writeString(file.toPath(), "kept", StandardCharsets.UTF_8);
        final var lengthBeforeAppend = file.length();
        Files.writeString(file.toPath(), "kept-and-appended", StandardCharsets.UTF_8);

        FileLocks.truncateTo(file, lengthBeforeAppend);

        assertEquals(lengthBeforeAppend, file.length());
        assertEquals("kept", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_truncate_to_swallows_a_failure_so_the_original_error_still_surfaces(@TempDir File tmp) {
        final var unwritable = new File(tmp, "not-a-file");
        assertTrue(unwritable.mkdirs(), "the test needs a path that cannot be opened as a file");

        assertDoesNotThrow(() -> FileLocks.truncateTo(unwritable, 0L, "rolling back a pk index append"),
                "the rollback is best effort; throwing here would mask the failure that triggered it");
    }

    private static FileLocks.Parser<List<String>> parsesInTurn(List<FileLocks.Parsed<List<String>>> answers) {
        final var queue = new ArrayDeque<>(answers);
        return _ -> queue.poll();
    }

    @Test
    public void test_read_healing_rewrites_the_lines_that_still_parse(@TempDir File tmp) throws IOException {
        final var file = new File(tmp, "index.idx");
        Files.writeString(file.toPath(), "kept\ntorn", StandardCharsets.UTF_8);
        final var dropped = new FileLocks.Parsed<>(List.of("kept"), List.of("kept"), true, false);

        final var parsed = FileLocks.readHealing(file, parsesInTurn(List.of(dropped, dropped)));

        assertEquals(List.of("kept"), parsed.entries());
        assertEquals("kept\n", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_read_healing_never_rewrites_a_file_the_second_parse_did_not_recognise(@TempDir File tmp)
            throws IOException {
        final var file = new File(tmp, "index.idx");
        Files.writeString(file.toPath(), "kept\ntorn", StandardCharsets.UTF_8);
        final var dropped = new FileLocks.Parsed<>(List.of("kept"), List.of("kept"), true, false);
        final var unrecognised = new FileLocks.Parsed<List<String>>(List.of(), List.of(), true, true);

        final var parsed = FileLocks.readHealing(file, parsesInTurn(List.of(dropped, unrecognised)));

        assertTrue(parsed.unrecognised());
        assertEquals("kept\ntorn", Files.readString(file.toPath(), StandardCharsets.UTF_8),
                "a file in which no line parsed is never rewritten, even when an earlier read understood it");
    }
}
