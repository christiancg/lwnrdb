package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.log.LogWriter;

public class OwnerOnlyTest {
    private static final String FALLBACK = "as owner-only; creating it with default permissions";

    @TempDir
    Path tmp;

    @BeforeEach
    public void requirePosix() {
        assumeTrue(OwnerOnlyFiles.supported());
    }

    @Test
    public void a_default_permissions_fallback_is_logged_once() throws IOException {
        final var unreachable = tmp.resolve("missing").resolve("page.dat");
        try (var logWriter = mockStatic(LogWriter.class)) {
            OwnerOnly.ensureOwnerOnlyFile(unreachable);
            logWriter.verify(
                    () -> LogWriter.writeLogEntry(
                            argThat(entry -> entry.contains("Could not create " + unreachable + " " + FALLBACK))),
                    times(1));
        }
    }

    @Test
    public void an_owner_only_creation_logs_nothing() throws IOException {
        try (var logWriter = mockStatic(LogWriter.class)) {
            OwnerOnly.createDirectories(tmp.resolve("a").resolve("b"));
            OwnerOnly.ensureOwnerOnlyFile(tmp.resolve("a").resolve("file.dat"));
            assertTrue(OwnerOnly.createDirectory(tmp.resolve("single")));
            logWriter.verify(() -> LogWriter.writeLogEntry(any()), never());
        }
    }

    @Test
    public void create_new_owner_only_file_reports_whether_it_created_the_file() throws IOException {
        final var file = tmp.resolve("collection-0.dat");
        assertTrue(OwnerOnly.createNewOwnerOnlyFile(file));
        assertTrue(Files.exists(file));
        assertFalse(OwnerOnly.createNewOwnerOnlyFile(file));
    }
}
