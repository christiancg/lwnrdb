package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.ops.ErrorCode;
import org.techhouse.test.ResponseLines;

public class OutboundQueueInlineTest {
    private static final long CAP = 100;
    private final ResponseBudget budget = new ResponseBudget(() -> Long.MAX_VALUE);
    private final ResponseLines received = new ResponseLines();
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private final AtomicInteger drainersStarted = new AtomicInteger();
    private final Executor countingDrainer = command -> {
        drainersStarted.incrementAndGet();
        OutboundQueue.VIRTUAL_DRAINER.execute(command);
    };
    private OutboundQueue queue;

    @BeforeEach
    void setUp() {
        queue = new OutboundQueue(received, () -> {
            connectionClosed.countDown();
            received.connectionClosed();
        }, CAP, countingDrainer, () -> {
        }, budget);
    }

    @AfterEach
    void tearDown() {
        received.resume();
    }

    private static EncodedJson frame(String text) {
        return EncodedJson.of(text.getBytes(StandardCharsets.UTF_8));
    }

    private OutboundQueue.InlineWrite respond(String text) {
        return queue.offerResponse(frame(text), 0, true);
    }

    @Test
    public void aSmallResponseToAnIdleQueueIsWrittenByTheCallerWithoutADrainer() throws Exception {
        final var write = respond("inline");
        assertNotEquals(OutboundQueue.InlineWrite.NONE, write);
        assertNull(received.within(50), "nothing is written until the token runs");
        assertFalse(queue.awaitDrained(10), "the queue counts as busy until the inline write ends");

        write.write();

        assertEquals("inline", received.next());
        assertTrue(queue.awaitDrained(1000));
        assertEquals(0, drainersStarted.get());
    }

    @Test
    public void aCallerThatMayNotWriteInlineQueuesEvenASmallResponse() throws Exception {
        assertEquals(OutboundQueue.InlineWrite.NONE, queue.offerResponse(frame("queued"), 0, false));

        assertEquals("queued", received.next());
        assertTrue(queue.awaitDrained(5000));
        assertEquals(1, drainersStarted.get());
    }

    @Test
    public void aResponseLargerThanTheInlineLimitGoesToADrainer() throws Exception {
        final var large = "x".repeat(Globals.INLINE_FRAME_MAX_BYTES);
        assertEquals(OutboundQueue.InlineWrite.NONE, respond(large));

        assertEquals(large, received.next());
        assertTrue(queue.awaitDrained(5000));
        assertEquals(1, drainersStarted.get());
    }

    @Test
    public void aResponseExactlyAtTheInlineLimitIsWrittenInline() throws Exception {
        final var exact = "y".repeat(Globals.INLINE_FRAME_MAX_BYTES - System.lineSeparator().length());
        final var write = respond(exact);
        assertNotEquals(OutboundQueue.InlineWrite.NONE, write);

        write.write();

        assertEquals(exact, received.next());
        assertEquals(0, drainersStarted.get());
    }

    @Test
    public void aBusyQueueQueuesASmallResponseBehindTheFrameInFlight() throws Exception {
        received.stall();
        assertTrue(queue.offer(frame("push")));
        assertTrue(received.awaitBlockedWriter(5000));

        assertEquals(OutboundQueue.InlineWrite.NONE, respond("behind"));
        received.resume();

        assertEquals("push", received.next());
        assertEquals("behind", received.next());
        assertTrue(queue.awaitDrained(5000));
        assertEquals(1, drainersStarted.get());
    }

    @Test
    public void aListenPushDuringAnInlineWriteQueuesBehindItAndStartsTheDrainerAfterwards() throws Exception {
        final var write = respond("response");
        assertTrue(queue.offer(frame("push-1")));
        assertTrue(queue.offer(frame("push-2")));
        assertEquals(0, drainersStarted.get(), "the inline writer owns the stream");

        write.write();

        assertEquals("response", received.next());
        assertEquals("push-1", received.next());
        assertEquals("push-2", received.next());
        assertTrue(queue.awaitDrained(5000));
        assertEquals(1, drainersStarted.get());
    }

    @Test
    public void anOverflowDuringAnInlineWriteStillWritesTheResponseThenCloses() throws Exception {
        final var write = respond("response");
        assertTrue(queue.offer(frame("p".repeat(60))));
        assertFalse(queue.offer(frame("q".repeat(60))));

        write.write();

        assertEquals("response", received.next());
        final var refusal = received.next();
        assertTrue(refusal.contains(ErrorCode.OUTBOUND_BACKLOG.getCode()), refusal);
        assertTrue(connectionClosed.await(5, TimeUnit.SECONDS));
        assertTrue(queue.awaitDrained(5000));
    }

    @Test
    public void aStalledInlineWriteFailsOnceTheConnectionCloses() throws Exception {
        received.stall();
        final var write = respond("stalled");
        final var writer = Thread.ofVirtual().start(write::write);
        assertTrue(received.awaitBlockedWriter(5000));

        assertTrue(queue.abortStalled());
        assertFalse(queue.abortStalled(), "a queue is aborted once");

        writer.join(5000);
        assertFalse(writer.isAlive());
        assertFalse(queue.isOpen());
        assertTrue(queue.awaitDrained(5000));
    }
}
