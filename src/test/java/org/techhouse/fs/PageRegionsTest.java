package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PageRegionsTest {

    @Test
    public void test_truncate_to_restores_the_recorded_length(@TempDir File tmp) throws IOException {
        final var file = new File(tmp, "page.dat");
        Files.writeString(file.toPath(), "kept", StandardCharsets.UTF_8);
        final var lengthBeforeAppend = file.length();
        Files.writeString(file.toPath(), "kept-and-appended", StandardCharsets.UTF_8);

        PageRegions.truncateTo(file, lengthBeforeAppend);

        assertEquals(lengthBeforeAppend, file.length());
        assertEquals("kept", Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_truncate_to_swallows_a_failure_so_the_original_error_still_surfaces(@TempDir File tmp) {
        final var unwritable = new File(tmp, "not-a-file");
        assertTrue(unwritable.mkdirs(), "the test needs a path that cannot be opened as a file");

        assertDoesNotThrow(() -> PageRegions.truncateTo(unwritable, 0L, "rolling back a pk index append"),
                "the rollback is best effort; throwing here would mask the failure that triggered it");
    }
}
