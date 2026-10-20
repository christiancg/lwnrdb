package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.techhouse.config.Globals;

public class PageTailsTest {
    private static final byte[] NONE = new byte[0];
    private static final byte[] NEWLINE = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] LINE = "{\"_id\":\"a\"}\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File tmp;

    private File page;

    @BeforeEach
    public void setUp() throws IOException {
        page = new File(tmp, "coll-0.dat");
        Files.write(page.toPath(), LINE);
        PageTails.forget(page);
    }

    @FunctionalInterface
    private interface Step {
        void run() throws IOException;
    }

    private static int tailReadsDuring(Step step) throws IOException {
        final var reads = new AtomicInteger();
        try (MockedStatic<FileLocks> locks = mockStatic(FileLocks.class, CALLS_REAL_METHODS)) {
            locks.when(() -> FileLocks.separatorBeforeAppend(any(File.class))).thenAnswer(invocation -> {
                reads.incrementAndGet();
                return invocation.callRealMethod();
            });
            step.run();
        }
        return reads.get();
    }

    private void appendThroughFs(byte[] line) throws IOException {
        Files.write(page.toPath(), line, StandardOpenOption.APPEND);
        PageTails.appended(page, line);
    }

    @Test
    public void test_second_append_does_not_read_the_tail() throws IOException {
        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NONE, PageTails.separatorBefore(page))));
        appendThroughFs(LINE);

        assertEquals(0, tailReadsDuring(() -> assertArrayEquals(NONE, PageTails.separatorBefore(page))));
    }

    @Test
    public void test_length_change_falls_back_to_reading() throws IOException {
        appendThroughFs(LINE);
        Files.writeString(page.toPath(), "{\"_id\"", StandardOpenOption.APPEND);

        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NEWLINE, PageTails.separatorBefore(page))));
    }

    @Test
    public void test_torn_tail_after_restart_is_still_detected() throws IOException {
        appendThroughFs(LINE);
        final var bytes = Files.readAllBytes(page.toPath());
        bytes[bytes.length - 1] = 'x';
        Files.write(page.toPath(), bytes);
        Files.setLastModifiedTime(page.toPath(), FileTime.fromMillis(page.lastModified() + 5_000));

        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NEWLINE, PageTails.separatorBefore(page))),
                "a same-length rewrite is caught by its modification time");
    }

    @Test
    public void aLineWithoutATerminatorIsNotRemembered() throws IOException {
        appendThroughFs("{\"_id\":\"b\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NEWLINE, PageTails.separatorBefore(page))));
    }

    @Test
    public void aBlankKeepsARememberedTail() throws IOException {
        appendThroughFs(LINE);
        Files.setLastModifiedTime(page.toPath(), FileTime.fromMillis(page.lastModified() + 5_000));
        PageTails.blanked(page);

        assertEquals(0, tailReadsDuring(() -> assertArrayEquals(NONE, PageTails.separatorBefore(page))));
    }

    @Test
    public void aBlankDoesNotRememberAnUnknownTail() throws IOException {
        PageTails.blanked(page);

        assertEquals(1, tailReadsDuring(() -> PageTails.separatorBefore(page)));
    }

    @Test
    public void test_vacuum_truncate_and_heal_forget_the_tail() throws IOException {
        final var length = page.length();
        appendThroughFs(LINE);
        FileLocks.truncateTo(page, length);
        assertEquals(1, tailReadsDuring(() -> PageTails.separatorBefore(page)), "truncateTo");

        appendThroughFs(LINE);
        FileLocks.cutUnterminatedTail(page);
        assertEquals(1, tailReadsDuring(() -> PageTails.separatorBefore(page)), "cutUnterminatedTail");

        appendThroughFs(LINE);
        final var rewritten = new File(tmp, "coll-0.vacuum");
        Files.write(rewritten.toPath(), LINE);
        FileLocks.moveIntoPlace(rewritten.toPath(), page.toPath());
        assertEquals(1, tailReadsDuring(() -> PageTails.separatorBefore(page)), "moveIntoPlace");
    }

    @Test
    public void droppingTheFolderForgetsEveryPageUnderIt() throws IOException {
        final var folder = new File(tmp, "coll");
        final var nested = new File(folder, "coll-0.dat");
        Files.createDirectories(folder.toPath());
        Files.write(nested.toPath(), LINE);
        PageTails.appended(nested, LINE);

        FileTreeDeleter.delete(folder);
        Files.createDirectories(folder.toPath());
        Files.write(nested.toPath(), LINE);

        assertEquals(1, tailReadsDuring(() -> PageTails.separatorBefore(nested)));
    }
}
