package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ops.ErrorCode;
import org.techhouse.test.ResponseLines;

public class OutboundQueueTest {
    private static final long CAP = 100;
    private final ResponseLines received = new ResponseLines();
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private final AtomicInteger overflows = new AtomicInteger();
    private OutboundQueue queue;

    @BeforeEach
    void setUp() {
        received.stall();
        queue = new OutboundQueue(new BufferedWriter(new OutputStreamWriter(received, StandardCharsets.UTF_8)), () -> {
            connectionClosed.countDown();
            received.connectionClosed();
        }, CAP, OutboundQueue.VIRTUAL_DRAINER, overflows::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        received.resume();
    }

    private static String line(char fill, int length) {
        return String.valueOf(fill).repeat(length);
    }

    @Test
    public void test_an_offer_returns_while_the_reader_is_stalled() {
        final var started = System.nanoTime();
        assertTrue(queue.offer(line('a', 10)));
        assertTrue(queue.offer(line('b', 10)));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        assertFalse(queue.awaitDrained(100), "the stalled reader still holds the first line");
    }

    @Test
    public void test_lines_are_delivered_in_order_once_the_reader_resumes() throws Exception {
        assertTrue(queue.offer("first"));
        assertTrue(queue.offer("second"));
        received.resume();

        assertTrue(queue.awaitDrained(5000));
        assertEquals("first", received.next());
        assertEquals("second", received.next());
        assertTrue(queue.isOpen());
    }

    @Test
    public void test_a_single_line_larger_than_the_cap_is_admitted_when_nothing_waits() throws Exception {
        assertTrue(queue.offer(line('x', (int) CAP * 3)));
        received.resume();

        assertTrue(queue.awaitDrained(5000));
        assertEquals(line('x', (int) CAP * 3), received.next());
    }

    private void stallOnALineInFlight() throws InterruptedException {
        assertTrue(queue.offer("in-flight"));
        assertTrue(received.awaitBlockedWriter(5000));
        assertTrue(queue.offer(line('q', 60)));
    }

    @Test
    public void test_an_overflow_drops_the_backlog_and_closes_after_a_final_413_frame() throws Exception {
        stallOnALineInFlight();

        assertFalse(queue.offer(line('r', 60)));
        assertFalse(queue.isOpen());
        assertEquals(1, overflows.get());
        assertFalse(queue.offer("after"), "a closed queue refuses every later line");

        received.resume();
        assertEquals("in-flight", received.next());
        final var refusal = received.next();
        assertTrue(refusal.contains(ErrorCode.OUTBOUND_BACKLOG.getCode()), refusal);
        assertTrue(refusal.contains("CLOSE_CONNECTION"), refusal);
        assertTrue(connectionClosed.await(5, TimeUnit.SECONDS));
        assertEquals(0, received.received(), "the dropped backlog is never written");
    }

    @Test
    public void test_an_overflow_closes_the_connection_even_if_the_reader_never_resumes() throws Exception {
        stallOnALineInFlight();
        assertFalse(queue.offer(line('r', 60)));

        assertTrue(connectionClosed.await(5, TimeUnit.SECONDS));
        assertTrue(queue.awaitDrained(5000), "the stalled write fails once the connection closes");
    }

    @Test
    public void test_a_failed_write_closes_the_queue_and_the_connection() throws Exception {
        final var failing = new OutboundQueue(new BufferedWriter(new java.io.Writer() {
            @Override
            @SuppressWarnings("NullableProblems")
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("broken pipe");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        }, 1), connectionClosed::countDown, CAP, Runnable::run, overflows::incrementAndGet);

        assertFalse(failing.offer("doomed"));
        assertFalse(failing.isOpen());
        assertTrue(connectionClosed.await(1, TimeUnit.SECONDS));
        assertEquals(0, overflows.get());
    }

    @Test
    public void test_close_frame_is_a_close_connection_response_carrying_the_code() {
        final var frame = OutboundQueue.closeFrame(ErrorCode.OUTBOUND_BACKLOG);

        assertTrue(frame.contains("\"type\":\"CLOSE_CONNECTION\""), frame);
        assertTrue(frame.contains("\"errorCode\":\"413-1\""), frame);
    }
}
