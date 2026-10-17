package org.techhouse.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class ResponseLines extends OutputStream {
    private static final long DEFAULT_WAIT_SECONDS = 10;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private boolean stalled;
    private boolean connectionClosed;
    private boolean writerBlocked;

    public String next() throws InterruptedException {
        return lines.poll(DEFAULT_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    public String within(long millis) throws InterruptedException {
        return lines.poll(millis, TimeUnit.MILLISECONDS);
    }

    public int received() {
        return lines.size();
    }

    public synchronized void stall() {
        stalled = true;
    }

    public synchronized void resume() {
        stalled = false;
        notifyAll();
    }

    public synchronized boolean awaitBlockedWriter(long millis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!writerBlocked) {
            final var remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return false;
            }
            wait(remaining);
        }
        return true;
    }

    public synchronized void connectionClosed() {
        connectionClosed = true;
        notifyAll();
    }

    @Override
    public synchronized void write(int b) throws IOException {
        awaitWritable();
        if (b == '\n') {
            lines.add(pending.toString(StandardCharsets.UTF_8));
            pending.reset();
        } else {
            pending.write(b);
        }
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        for (var i = offset; i < offset + length; i++) {
            write(bytes[i]);
        }
    }

    private void awaitWritable() throws IOException {
        while (stalled && !connectionClosed) {
            writerBlocked = true;
            notifyAll();
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("stalled write interrupted");
            }
        }
        if (stalled) {
            throw new IOException("the connection was closed under a stalled write");
        }
    }
}
