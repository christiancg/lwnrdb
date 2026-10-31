package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.conn.WriteStallWatchdog;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.test.ResponseLines;
import org.techhouse.test.TestUtils;

public class WriteStallWatchdogTest {
    private static final long TIMEOUT_MS = 1000L;
    private final Configuration configuration = Configuration.getInstance();
    private final ResponseBudget budget = new ResponseBudget(() -> Long.MAX_VALUE);
    private final ResponseLines received = new ResponseLines();
    private ClientTracker clientTracker;
    private WriteStallWatchdog watchdog;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(configuration, "clientWriteStallTimeoutMs", TIMEOUT_MS);
        clientTracker = new ClientTracker();
        watchdog = new WriteStallWatchdog();
        TestUtils.setPrivateField(watchdog, "clientTracker", clientTracker);
    }

    @AfterEach
    void tearDown() throws NoSuchFieldException, IllegalAccessException {
        received.resume();
        watchdog.stop();
        TestUtils.setPrivateField(configuration, "clientWriteStallTimeoutMs",
                ConfigKey.CLIENT_WRITE_STALL_TIMEOUT_MS.defaultValue());
    }

    private OutboundQueue register(OutputStream out) {
        final var socket = Mockito.mock(Socket.class);
        final var address = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(address);
        Mockito.when(address.getHostAddress()).thenReturn("127.0.0.1");
        final var clientId = clientTracker.addClient(socket);
        final var queue = new OutboundQueue(out, received::connectionClosed, Long.MAX_VALUE,
                OutboundQueue.VIRTUAL_DRAINER, () -> {
                }, budget);
        clientTracker.registerOutbound(clientId, queue);
        return queue;
    }

    private static EncodedJson frame(char fill, int length) {
        return EncodedJson.of(String.valueOf(fill).repeat(length).getBytes(StandardCharsets.UTF_8));
    }

    private static long later(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    public void aStalledInlineWriteIsClosedAfterTheTimeoutAndItsWriterFails() throws Exception {
        received.stall();
        final var queue = register(received);
        final var write = queue.offerResponse(frame('s', 10), 0, true);
        final var writer = Thread.ofVirtual().start(write::write);
        assertTrue(received.awaitBlockedWriter(5000));

        assertEquals(0, watchdog.scan(System.nanoTime()), "not stalled for long enough yet");
        assertTrue(queue.isOpen());
        assertEquals(1, watchdog.scan(later(TIMEOUT_MS + 500)));

        writer.join(5000);
        assertFalse(writer.isAlive(), "the blocked writer failed out once the connection closed");
        assertFalse(queue.isOpen());
        assertEquals(0, watchdog.scan(later(10 * TIMEOUT_MS)), "an aborted queue is not closed twice");
    }

    @Test
    public void aStalledDrainIsClosedAfterTheTimeout() throws Exception {
        received.stall();
        final var queue = register(received);
        queue.offerResponse(frame('d', Globals.INLINE_FRAME_MAX_BYTES * 2), 0, true).write();
        assertTrue(received.awaitBlockedWriter(5000));

        assertEquals(1, watchdog.scan(later(TIMEOUT_MS + 500)));

        assertTrue(queue.awaitDrained(5000));
        assertFalse(queue.isOpen());
    }

    @Test
    public void anIdleQueueIsNeverClosed() {
        final var queue = register(received);

        assertEquals(0, watchdog.scan(later(100 * TIMEOUT_MS)));
        assertTrue(queue.isOpen());
    }

    @Test
    public void aSlowlyProgressingWriteIsNotClosed() {
        final var chunks = new AtomicInteger();
        final var queue = register(new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public void write(byte[] bytes, int offset, int length) throws InterruptedIOException {
                chunks.incrementAndGet();
                try {
                    Thread.sleep(TIMEOUT_MS / 4);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
        });
        queue.offerResponse(frame('p', Globals.INLINE_FRAME_MAX_BYTES * 6), 0, true).write();

        final var aborted = new AtomicInteger();
        final var deadline = later(10 * TIMEOUT_MS);
        while (!queue.awaitDrained(50) && System.nanoTime() < deadline) {
            aborted.addAndGet(watchdog.scan(System.nanoTime()));
        }

        assertTrue(chunks.get() >= 6, "the frame was written chunk by chunk: " + chunks.get());
        assertEquals(0, aborted.get(), "each chunk counted as progress although the whole write took longer");
        assertTrue(queue.isOpen());
    }

    @Test
    public void startAndStopTheScanner() {
        assertFalse(watchdog.isRunning());
        watchdog.start();
        watchdog.start();
        assertTrue(watchdog.isRunning());
        watchdog.stop();
        assertFalse(watchdog.isRunning());
        watchdog.stop();
    }
}
