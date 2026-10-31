package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.test.ResponseLines;

public class OutboundQueueBudgetTest {
    private static final long CAP = 100_000;
    private static final int LARGE = Globals.INLINE_FRAME_MAX_BYTES + 1;
    private final ResponseBudget budget = new ResponseBudget(() -> Long.MAX_VALUE);
    private final ResponseLines received = new ResponseLines();
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private OutboundQueue queue;

    @BeforeEach
    void setUp() {
        received.stall();
        queue = new OutboundQueue(received, () -> {
            connectionClosed.countDown();
            received.connectionClosed();
        }, CAP, OutboundQueue.VIRTUAL_DRAINER, () -> {
        }, budget);
    }

    @AfterEach
    void tearDown() {
        received.resume();
    }

    private static EncodedJson frame(char fill, int length) {
        return EncodedJson.of(String.valueOf(fill).repeat(length).getBytes(StandardCharsets.UTF_8));
    }

    private OutboundQueue.InlineWrite respond(char fill, int length) {
        final var response = frame(fill, length);
        budget.reserve(response.length());
        return queue.offerResponse(response, response.length(), true);
    }

    private void stallOnALargeResponseInFlight() throws InterruptedException {
        assertEquals(OutboundQueue.InlineWrite.NONE, respond('a', LARGE));
        assertTrue(received.awaitBlockedWriter(5000));
    }

    @Test
    public void anInlineResponseReleasesItsBytesOnceWritten() {
        received.resume();
        final var write = respond('i', 100);
        assertEquals(100, budget.unsentBytes(), "reserved until the bytes are written");

        write.write();

        assertEquals(0, budget.unsentBytes());
    }

    @Test
    public void drainedResponsesReleaseTheirBytesOnceWritten() throws Exception {
        stallOnALargeResponseInFlight();
        respond('b', 200).write();
        respond('c', LARGE).write();
        assertEquals(2L * LARGE + 200, budget.unsentBytes());

        received.resume();

        assertTrue(queue.awaitDrained(5000));
        assertEquals(0, budget.unsentBytes());
    }

    @Test
    public void anOverflowReleasesTheDroppedBacklogAndTheRefusedFrame() throws Exception {
        queue = new OutboundQueue(received, () -> {
            connectionClosed.countDown();
            received.connectionClosed();
        }, LARGE + 100L, OutboundQueue.VIRTUAL_DRAINER, () -> {
        }, budget);
        stallOnALargeResponseInFlight();
        respond('q', LARGE).write();
        respond('r', LARGE).write();
        assertFalse(queue.isOpen(), "the third large response overflowed the queue");
        assertEquals(LARGE, budget.unsentBytes(), "only the response being written stays reserved");

        assertTrue(connectionClosed.await(5, TimeUnit.SECONDS));
        assertTrue(queue.awaitDrained(5000));
        assertEquals(0, budget.unsentBytes());
    }

    @Test
    public void aClosedQueueReleasesARefusedResponseAtOnce() {
        final var failing = new OutboundQueue(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        }, () -> {
        }, CAP, Runnable::run, () -> {
        }, budget);
        final var first = frame('f', 10);
        budget.reserve(first.length());
        failing.offerResponse(first, first.length(), true).write();
        assertFalse(failing.isOpen());
        assertEquals(0, budget.unsentBytes(), "a failed write still releases its frame");

        final var late = frame('l', LARGE);
        budget.reserve(late.length());
        assertEquals(OutboundQueue.InlineWrite.NONE, failing.offerResponse(late, late.length(), true));

        assertEquals(0, budget.unsentBytes());
    }

    @Test
    public void aFailedDrainReleasesEveryQueuedFrame() throws Exception {
        stallOnALargeResponseInFlight();
        respond('b', 300).write();
        respond('c', LARGE).write();

        received.connectionClosed();

        assertTrue(queue.awaitDrained(5000));
        assertFalse(queue.isOpen());
        assertEquals(0, budget.unsentBytes());
    }

    @Test
    public void connectionCleanupDiscardsWhatIsStillQueued() throws Exception {
        stallOnALargeResponseInFlight();
        respond('b', 300).write();
        respond('c', LARGE).write();

        queue.discard();
        assertEquals(LARGE, budget.unsentBytes(), "the frame being written is released by its writer");
        received.connectionClosed();

        assertTrue(queue.awaitDrained(5000));
        assertEquals(0, budget.unsentBytes());
        assertEquals(OutboundQueue.InlineWrite.NONE, respond('d', 10));
        assertEquals(0, budget.unsentBytes(), "a response offered after the cleanup is released at once");
    }

    @Test
    public void listenPushesAreNeverCharged() {
        assertTrue(queue.offer(frame('p', 500)));
        assertEquals(0, budget.unsentBytes());
        received.resume();
        assertTrue(queue.awaitDrained(5000));
        assertEquals(0, budget.unsentBytes());
    }
}
