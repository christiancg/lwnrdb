package org.techhouse.unit.conn;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.test.TestUtils;

final class ScriptedConnection {
    private static final Object EOF = new Object();
    private static final long UNTIMED_READ_LIMIT_SECONDS = 20;
    private static final long RESPONSE_WAIT_SECONDS = 10;

    private final LinkedBlockingDeque<Object> inbound = new LinkedBlockingDeque<>();
    private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
    private final List<Integer> timeouts = new CopyOnWriteArrayList<>();
    private final AtomicInteger timeoutsFired = new AtomicInteger();
    private volatile int soTimeout;
    private volatile Runnable onTimeout = () -> {
    };
    private final MessageProcessor processor;
    private Thread thread;

    ScriptedConnection() throws IOException {
        final var socket = TestUtils.clientSocket(new Inbound(), new Outbound());
        doAnswer(invocation -> {
            soTimeout = invocation.getArgument(0);
            timeouts.add(soTimeout);
            return null;
        }).when(socket).setSoTimeout(anyInt());
        doAnswer(_ -> {
            inbound.addFirst(EOF);
            return null;
        }).when(socket).close();
        processor = new MessageProcessor(socket);
    }

    MessageProcessor processor() {
        return processor;
    }

    UUID clientId() throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateField(processor, "clientId", UUID.class);
    }

    void start() {
        thread = Thread.ofPlatform().name("scripted-connection").start(processor);
    }

    void onTimeout(Runnable hook) {
        onTimeout = hook;
    }

    void send(String line) {
        sendBytes((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    void sendBytes(byte[] bytes) {
        inbound.add(bytes);
    }

    String exchange(String line) throws InterruptedException {
        send(line);
        return response();
    }

    String response() throws InterruptedException {
        return responses.poll(RESPONSE_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    String responseWithin(long millis) throws InterruptedException {
        return responses.poll(millis, TimeUnit.MILLISECONDS);
    }

    List<Integer> timeouts() {
        return timeouts;
    }

    int timeoutsFired() {
        return timeoutsFired.get();
    }

    boolean awaitClosed(long millis) throws InterruptedException {
        thread.join(millis);
        return !thread.isAlive();
    }

    void close() throws InterruptedException {
        inbound.add(EOF);
        if (thread != null) {
            thread.join(TimeUnit.SECONDS.toMillis(RESPONSE_WAIT_SECONDS));
        }
    }

    private final class Inbound extends InputStream {
        @Override
        public int read() throws IOException {
            final var single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public int read(byte[] target, int offset, int length) throws IOException {
            final var timeout = soTimeout;
            final Object next;
            try {
                next = timeout == 0
                        ? inbound.pollFirst(UNTIMED_READ_LIMIT_SECONDS, TimeUnit.SECONDS)
                        : inbound.pollFirst(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (next == null && timeout != 0) {
                timeoutsFired.incrementAndGet();
                onTimeout.run();
                throw new SocketTimeoutException("scripted read timeout");
            }
            if (next == null || next == EOF) {
                inbound.addFirst(EOF);
                return -1;
            }
            final var chunk = (byte[]) next;
            final var served = Math.min(length, chunk.length);
            System.arraycopy(chunk, 0, target, offset, served);
            if (served < chunk.length) {
                inbound.addFirst(Arrays.copyOfRange(chunk, served, chunk.length));
            }
            return served;
        }
    }

    private final class Outbound extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                responses.add(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            } else {
                pending.write(b);
            }
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (var i = offset; i < offset + length; i++) {
                write(bytes[i]);
            }
        }
    }
}
