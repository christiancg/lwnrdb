package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
        PageLengths.forget(page);
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

    private void appendThroughFs(File file, byte[] line) throws IOException {
        Files.write(file.toPath(), line, StandardOpenOption.APPEND);
        PageLengths.grew(file, file.length(), line[line.length - 1] == '\n');
    }

    @Test
    public void test_second_append_does_not_read_the_tail() throws IOException {
        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NONE, PageTails.appendPoint(page).separator())));
        appendThroughFs(page, LINE);

        assertEquals(0, tailReadsDuring(() -> assertArrayEquals(NONE, PageTails.appendPoint(page).separator())));
    }

    @Test
    public void test_an_external_change_is_read_after_the_length_is_forgotten() throws IOException {
        appendThroughFs(page, LINE);
        Files.writeString(page.toPath(), "{\"_id\"", StandardOpenOption.APPEND);
        PageLengths.forget(page);

        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NEWLINE, PageTails.appendPoint(page).separator())));
    }

    @Test
    public void test_torn_tail_after_restart_is_still_detected() throws IOException {
        appendThroughFs(page, LINE);
        final var bytes = Files.readAllBytes(page.toPath());
        bytes[bytes.length - 1] = 'x';
        Files.write(page.toPath(), bytes);
        PageLengths.forget(page);

        assertEquals(1, tailReadsDuring(() -> assertArrayEquals(NEWLINE, PageTails.appendPoint(page).separator())),
                "a page this process no longer knows has its last byte read");
    }

    @Test
    public void aLineWithoutATerminatorIsNotRemembered() throws IOException {
        appendThroughFs(page, "{\"_id\":\"b\"}".getBytes(StandardCharsets.UTF_8));

        final var point = new PageTails.AppendPoint[1];
        assertEquals(1, tailReadsDuring(() -> point[0] = PageTails.appendPoint(page)));
        assertArrayEquals(NEWLINE, point[0].separator());
        assertEquals(page.length(), point[0].length());
    }

    @Test
    public void test_a_known_length_without_a_known_terminator_reads_the_last_byte() throws IOException {
        PageLengths.cut(page, page.length());

        final var point = new PageTails.AppendPoint[1];
        assertEquals(1, tailReadsDuring(() -> point[0] = PageTails.appendPoint(page)));
        assertArrayEquals(NONE, point[0].separator());
        assertEquals(page.length(), point[0].length());
        assertTrue(point[0].exists());
    }

    @Test
    public void test_vacuum_truncate_and_heal_forget_the_tail() throws IOException {
        final var length = page.length();
        appendThroughFs(page, LINE);
        FileLocks.truncateTo(page, length);
        assertEquals(1, tailReadsDuring(() -> PageTails.appendPoint(page)), "truncateTo");

        appendThroughFs(page, LINE);
        FileLocks.cutUnterminatedTail(page);
        assertEquals(1, tailReadsDuring(() -> PageTails.appendPoint(page)), "cutUnterminatedTail");

        appendThroughFs(page, LINE);
        final var rewritten = new File(tmp, "coll-0.vacuum");
        Files.write(rewritten.toPath(), LINE);
        FileLocks.moveIntoPlace(rewritten.toPath(), page.toPath());
        assertEquals(1, tailReadsDuring(() -> PageTails.appendPoint(page)), "moveIntoPlace");
    }

    @Test
    public void droppingTheFolderForgetsEveryPageUnderIt() throws IOException {
        final var folder = new File(tmp, "coll");
        final var nested = new File(folder, "coll-0.dat");
        Files.createDirectories(folder.toPath());
        Files.write(nested.toPath(), LINE);
        PageLengths.grew(nested, nested.length(), true);

        FileTreeDeleter.delete(folder);
        Files.createDirectories(folder.toPath());
        Files.writeString(nested.toPath(), "{\"_id\"");

        final var point = new PageTails.AppendPoint[1];
        assertEquals(1, tailReadsDuring(() -> point[0] = PageTails.appendPoint(nested)));
        assertArrayEquals(NEWLINE, point[0].separator());
    }

    @Test
    public void test_append_point_of_a_missing_page_is_empty_and_new() throws IOException {
        final var missing = new File(tmp, "coll-9.dat");

        final var point = new PageTails.AppendPoint[1];
        assertEquals(0, tailReadsDuring(() -> point[0] = PageTails.appendPoint(missing)));

        assertEquals(0L, point[0].length());
        assertFalse(point[0].exists());
        assertArrayEquals(NONE, point[0].separator());
    }

    @Test
    public void test_append_point_of_an_empty_page_reads_nothing() throws IOException {
        Files.write(page.toPath(), NONE);

        final var point = new PageTails.AppendPoint[1];
        assertEquals(0, tailReadsDuring(() -> point[0] = PageTails.appendPoint(page)));

        assertEquals(0L, point[0].length());
        assertTrue(point[0].exists());
    }

    @Test
    public void test_append_point_after_an_own_append_needs_no_separator() throws IOException {
        appendThroughFs(page, LINE);

        final var point = new PageTails.AppendPoint[1];
        assertEquals(0, tailReadsDuring(() -> point[0] = PageTails.appendPoint(page)));

        assertEquals(page.length(), point[0].length());
        assertTrue(point[0].exists());
        assertArrayEquals(NONE, point[0].separator());
    }

    @Test
    public void test_append_point_after_a_torn_tail_adds_the_separator() throws IOException {
        Files.writeString(page.toPath(), "{\"_id\"", StandardOpenOption.APPEND);

        final var point = PageTails.appendPoint(page);

        assertEquals(page.length(), point.length());
        assertArrayEquals(NEWLINE, point.separator());
    }
}
