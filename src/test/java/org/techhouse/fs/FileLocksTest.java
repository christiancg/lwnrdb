package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.test.TestUtils;

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

    private static Map<?, ?> registry() throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateStaticField(FileLocks.class, "fileLocks", Map.class);
    }

    @Test
    public void test_reading_an_absent_file_leaves_no_lock(@TempDir File tmp) throws Exception {
        final var absent = new File(tmp, "ghost-schema.json");

        assertNull(FileLocks.readAllLinesIfExists(absent));

        assertFalse(registry().containsKey(absent.getAbsolutePath()),
                "a request naming a file that does not exist must not register a lock for it forever");
    }

    @Test
    public void test_reading_a_present_file_keeps_its_lock(@TempDir File tmp) throws Exception {
        final var present = new File(tmp, "schema.json");
        Files.writeString(present.toPath(), "{}", StandardCharsets.UTF_8);

        assertEquals(List.of("{}"), FileLocks.readAllLinesIfExists(present));

        assertTrue(registry().containsKey(present.getAbsolutePath()));
    }

    @Test
    public void test_a_concurrent_holder_keeps_the_lock_of_an_absent_file(@TempDir File tmp) throws Exception {
        final var absent = new File(tmp, "held.json");
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            final var holder = pool.submit(() -> {
                final var lock = FileLocks.lockRead(absent);
                try {
                    held.countDown();
                    release.await();
                    return FileLocks.lockFor(absent).getReadLockCount();
                } finally {
                    lock.unlock();
                }
            });
            assertTrue(held.await(10, TimeUnit.SECONDS));
            final var registered = registry().get(absent.getAbsolutePath());

            assertNull(FileLocks.readAllLinesIfExists(absent));

            assertSame(registered, registry().get(absent.getAbsolutePath()),
                    "evicting a lock another thread holds would strand its holder");
            release.countDown();
            assertEquals(1, holder.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void test_evicting_absent_files_never_lets_two_writers_hold_different_locks(@TempDir File tmp)
            throws Exception {
        final var file = new File(tmp, "contended.json");
        final var inside = new AtomicInteger();
        final var failures = new ConcurrentLinkedQueue<String>();
        final var stop = new AtomicBoolean();
        try (var pool = Executors.newFixedThreadPool(4)) {
            final var tasks = new ArrayList<Future<?>>();
            for (var writer = 0; writer < 2; writer++) {
                tasks.add(pool.submit(() -> writeAndDelete(file, inside, failures)));
            }
            for (var reader = 0; reader < 2; reader++) {
                tasks.add(pool.submit(() -> readUntil(stop, file, failures)));
            }
            tasks.get(0).get(60, TimeUnit.SECONDS);
            tasks.get(1).get(60, TimeUnit.SECONDS);
            stop.set(true);
            for (final var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(List.of(), List.copyOf(failures));
        assertNull(FileLocks.readAllLinesIfExists(file));
        assertFalse(registry().containsKey(file.getAbsolutePath()));
    }

    private static void writeAndDelete(File file, AtomicInteger inside, ConcurrentLinkedQueue<String> failures) {
        for (var round = 0; round < 2_000; round++) {
            final var lock = FileLocks.lockWrite(file);
            try {
                if (inside.incrementAndGet() != 1) {
                    failures.add("two writers held the lock of one file at once");
                }
                if (!FileLocks.lockFor(file).isWriteLockedByCurrentThread()) {
                    failures.add("the mapped lock is not the one the writer holds");
                }
                Files.writeString(file.toPath(), "{}", StandardCharsets.UTF_8);
                Files.delete(file.toPath());
            } catch (IOException e) {
                failures.add(e.toString());
            } finally {
                inside.decrementAndGet();
                lock.unlock();
            }
        }
    }

    private static void readUntil(AtomicBoolean stop, File file, ConcurrentLinkedQueue<String> failures) {
        while (!stop.get()) {
            try {
                final var lines = FileLocks.readAllLinesIfExists(file);
                if (lines != null && !lines.equals(List.of("{}"))) {
                    failures.add("read a half-written file: " + lines);
                }
            } catch (IOException e) {
                failures.add(e.toString());
            }
        }
    }
}
