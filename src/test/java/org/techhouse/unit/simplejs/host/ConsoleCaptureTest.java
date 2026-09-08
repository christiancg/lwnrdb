package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.host.ConsoleCapture;

public class ConsoleCaptureTest {
    // Lines below the cap are all retained, in order, with no truncation flag
    @Test
    public void test_retains_lines_under_cap() {
        final var capture = new ConsoleCapture(10, 100);
        capture.accept("a");
        capture.accept("b");
        capture.accept("c");
        assertEquals(List.of("a", "b", "c"), capture.lines());
        assertFalse(capture.isTruncated());
    }

    // Over the cap the ring buffer drops the OLDEST lines and keeps the newest
    @Test
    public void test_evicts_oldest_over_cap() {
        final var capture = new ConsoleCapture(2, 100);
        capture.accept("a");
        capture.accept("b");
        capture.accept("c");
        assertEquals(List.of("b", "c"), capture.lines());
        assertTrue(capture.isTruncated());
    }

    // A single line longer than the per-line cap is clipped and flags truncation
    @Test
    public void test_clips_overlong_line() {
        final var capture = new ConsoleCapture(10, 4);
        capture.accept("abcdefgh");
        assertEquals(List.of("abcd"), capture.lines());
        assertTrue(capture.isTruncated());
    }

    // A line exactly at the per-line cap is kept whole
    @Test
    public void test_line_at_exact_cap_is_not_clipped() {
        final var capture = new ConsoleCapture(10, 4);
        capture.accept("abcd");
        assertEquals(List.of("abcd"), capture.lines());
        assertFalse(capture.isTruncated());
    }

    // A zero line cap disables capture entirely without throwing
    @Test
    public void test_zero_cap_disables_capture() {
        final var capture = new ConsoleCapture(0, 100);
        capture.accept("a");
        assertTrue(capture.lines().isEmpty());
        assertFalse(capture.isTruncated());
    }

    // A negative line cap behaves like zero
    @Test
    public void test_negative_cap_disables_capture() {
        final var capture = new ConsoleCapture(-1, 100);
        capture.accept("a");
        assertTrue(capture.lines().isEmpty());
    }

    // A non-positive per-line cap means no clipping
    @Test
    public void test_zero_line_char_cap_disables_clipping() {
        final var capture = new ConsoleCapture(10, 0);
        capture.accept("abcdefgh");
        assertEquals(List.of("abcdefgh"), capture.lines());
        assertFalse(capture.isTruncated());
    }

    // A null line is captured as the string "null" rather than throwing
    @Test
    public void test_null_line_is_captured_as_text() {
        final var capture = new ConsoleCapture(10, 100);
        capture.accept(null);
        assertEquals(List.of("null"), capture.lines());
    }

    // lines() is an immutable snapshot: it cannot be mutated and does not track later writes
    @Test
    public void test_lines_is_an_immutable_snapshot() {
        final var capture = new ConsoleCapture(10, 100);
        capture.accept("a");
        final var snapshot = capture.lines();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("b"));
        capture.accept("b");
        assertEquals(List.of("a"), snapshot);
        assertEquals(List.of("a", "b"), capture.lines());
    }

    // Concurrent writers (a fetch settlement and a coroutine body) never corrupt the buffer
    @Test
    public void test_concurrent_writes_are_safe() throws Exception {
        final var capture = new ConsoleCapture(1000, 100);
        final var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var t = 0; t < 4; t++) {
                executor.submit(() -> {
                    start.await();
                    for (var i = 0; i < 100; i++) {
                        capture.accept("line");
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(400, capture.lines().size());
        assertFalse(capture.isTruncated());
    }
}
