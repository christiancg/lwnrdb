package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ResponseLines;

public class OutboundQueueTest {
    private static final long CAP = 100;
    private static final EJson eJson = IocContainer.get(EJson.class);
    private final ResponseLines received = new ResponseLines();
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private final AtomicInteger overflows = new AtomicInteger();
    private OutboundQueue queue;

    @BeforeEach
    void setUp() {
        received.stall();
        queue = new OutboundQueue(received, () -> {
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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private boolean offer(String text) {
        return queue.offer(bytes(text));
    }

    @Test
    public void test_an_offer_returns_while_the_reader_is_stalled() {
        final var started = System.nanoTime();
        assertTrue(offer(line('a', 10)));
        assertTrue(offer(line('b', 10)));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        assertFalse(queue.awaitDrained(100), "the stalled reader still holds the first line");
    }

    @Test
    public void test_lines_are_delivered_in_order_once_the_reader_resumes() throws Exception {
        assertTrue(offer("first"));
        assertTrue(offer("second"));
        received.resume();

        assertTrue(queue.awaitDrained(5000));
        assertEquals("first", received.next());
        assertEquals("second", received.next());
        assertTrue(queue.isOpen());
    }

    @Test
    public void test_a_single_line_larger_than_the_cap_is_admitted_when_nothing_waits() throws Exception {
        assertTrue(offer(line('x', (int) CAP * 3)));
        received.resume();

        assertTrue(queue.awaitDrained(5000));
        assertEquals(line('x', (int) CAP * 3), received.next());
    }

    private void stallOnALineInFlight() throws InterruptedException {
        assertTrue(offer("in-flight"));
        assertTrue(received.awaitBlockedWriter(5000));
    }

    @Test
    public void test_an_overflow_drops_the_backlog_and_closes_after_a_final_413_frame() throws Exception {
        stallOnALineInFlight();
        assertTrue(offer(line('q', 60)));

        assertFalse(offer(line('r', 60)));
        assertFalse(queue.isOpen());
        assertEquals(1, overflows.get());
        assertFalse(offer("after"), "a closed queue refuses every later line");

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
        assertTrue(offer(line('q', 60)));
        assertFalse(offer(line('r', 60)));

        assertTrue(connectionClosed.await(5, TimeUnit.SECONDS));
        assertTrue(queue.awaitDrained(5000), "the stalled write fails once the connection closes");
    }

    @Test
    public void queueCountsRealBytes() throws Exception {
        stallOnALineInFlight();
        final var cjk = line('界', 20);
        assertEquals(60, bytes(cjk).length);

        assertTrue(offer(cjk), "60 bytes fit under a 100 byte cap");
        assertFalse(offer(cjk), "20 chars more would still fit, but 60 bytes more do not");
        assertEquals(1, overflows.get());
    }

    @Test
    public void test_ascii_frames_of_the_same_char_count_fit_under_the_cap() throws Exception {
        stallOnALineInFlight();

        assertTrue(offer(line('a', 30)));
        assertTrue(offer(line('b', 30)));
        assertTrue(queue.isOpen());
        assertEquals(0, overflows.get());
    }

    @Test
    public void test_the_line_terminator_counts_toward_the_cap() throws Exception {
        stallOnALineInFlight();
        final var terminator = System.lineSeparator().length();

        assertTrue(offer(line('a', 50 - terminator)));
        assertTrue(offer(line('b', 50 - terminator)), "two frames of exactly half the cap fit");
        assertFalse(offer("c"), "one more byte does not");
    }

    @Test
    public void test_a_failed_write_closes_the_queue_and_the_connection() throws Exception {
        final var failing = new OutboundQueue(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        }, connectionClosed::countDown, CAP, Runnable::run, overflows::incrementAndGet);

        assertFalse(failing.offer(bytes("doomed")));
        assertFalse(failing.isOpen());
        assertTrue(connectionClosed.await(1, TimeUnit.SECONDS));
        assertEquals(0, overflows.get());
    }

    @Test
    public void test_close_frame_is_a_close_connection_response_carrying_the_code() {
        final var frame = new String(OutboundQueue.closeFrame(ErrorCode.OUTBOUND_BACKLOG), StandardCharsets.UTF_8);

        assertTrue(frame.contains("\"type\":\"CLOSE_CONNECTION\""), frame);
        assertTrue(frame.contains("\"errorCode\":\"413-1\""), frame);
    }

    @Test
    public void closeFrameUnchanged() throws IOException {
        assertEquals(
                "{\"type\":\"CLOSE_CONNECTION\",\"status\":\"ERROR\","
                        + "\"message\":\"The connection's outbound buffer overflowed\",\"errorCode\":\"413-1\"}",
                new String(OutboundQueue.closeFrame(ErrorCode.OUTBOUND_BACKLOG), StandardCharsets.UTF_8));
        for (final var code : ErrorCode.values()) {
            final var legacy = eJson.toJson(new OperationResponse(OperationType.CLOSE_CONNECTION, code));
            assertArrayEquals(legacy.getBytes(StandardCharsets.UTF_8), OutboundQueue.closeFrame(code), code.name());
            assertArrayEquals(legacyDirectSend(legacy), directSend(code), code.name());
        }
    }

    private static byte[] legacyDirectSend(String frame) throws IOException {
        final var sink = new ByteArrayOutputStream();
        final var writer = new BufferedWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8));
        writer.write(frame);
        writer.newLine();
        writer.flush();
        return sink.toByteArray();
    }

    private static byte[] directSend(ErrorCode code) throws IOException {
        final var sink = new ByteArrayOutputStream();
        OutboundQueue.sendDirectly(sink, code);
        return sink.toByteArray();
    }
}
