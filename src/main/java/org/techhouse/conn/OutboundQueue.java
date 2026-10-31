package org.techhouse.conn;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

public class OutboundQueue {
    public static final Executor VIRTUAL_DRAINER = command -> Thread.ofVirtual().name("lwnrdb-outbound").start(command);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ResponseBudget sharedBudget = IocContainer.get(ResponseBudget.class);
    private static final long OVERFLOW_CLOSE_GRACE_MS = 1000L;
    private static final int WRITE_CHUNK_BYTES = Globals.INLINE_FRAME_MAX_BYTES;
    private static final byte[] LINE_END = System.lineSeparator().getBytes(StandardCharsets.US_ASCII);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final OutputStream out;
    private final Closeable connection;
    private final long maxQueuedBytes;
    private final Executor drainer;
    private final Runnable onOverflow;
    private final ResponseBudget budget;
    private long queuedBytes;
    private boolean draining;
    private boolean closed;
    private volatile boolean writing;
    private volatile long lastProgressNanos;
    private final AtomicBoolean aborted = new AtomicBoolean();

    @FunctionalInterface
    public interface InlineWrite {
        InlineWrite NONE = () -> {
        };

        void write();
    }

    private record Frame(EncodedJson head, EncodedJson body, EncodedJson tail, long reserved) {
        long queuedLength() {
            return lengthOf(head) + lengthOf(body) + lengthOf(tail) + LINE_END.length;
        }

        private static long lengthOf(EncodedJson segment) {
            return segment == null ? 0 : segment.length();
        }
    }

    public OutboundQueue(OutputStream out, Closeable connection, long maxQueuedBytes, Executor drainer,
            Runnable onOverflow) {
        this(out, connection, maxQueuedBytes, drainer, onOverflow, sharedBudget);
    }

    public OutboundQueue(OutputStream out, Closeable connection, long maxQueuedBytes, Executor drainer,
            Runnable onOverflow, ResponseBudget budget) {
        this.out = out;
        this.connection = connection;
        this.maxQueuedBytes = maxQueuedBytes;
        this.drainer = drainer;
        this.onOverflow = onOverflow;
        this.budget = budget;
    }

    public static EncodedJson frameOf(OperationResponse response) {
        return frameOf(response, response.estimatedJsonChars());
    }

    public static EncodedJson frameOf(OperationResponse response, int sizeHint) {
        return eJson.toJsonFrame(response, sizeHint);
    }

    public static EncodedJson closeFrame(ErrorCode code) {
        return frameOf(new OperationResponse(OperationType.CLOSE_CONNECTION, code));
    }

    public static void sendDirectly(OutputStream out, ErrorCode code) throws IOException {
        final var frame = closeFrame(code);
        out.write(frame.bytes(), 0, frame.length());
        out.write(LINE_END);
        out.flush();
    }

    public boolean offer(byte[] frame) {
        return offer(EncodedJson.of(frame));
    }

    public boolean offer(EncodedJson frame) {
        return offer(new Frame(frame, null, null, 0));
    }

    public boolean offer(EncodedJson head, EncodedJson body, EncodedJson tail) {
        return offer(new Frame(head, body, tail, 0));
    }

    public InlineWrite offerResponse(EncodedJson response, long reserved, boolean mayWriteInline) {
        final var frame = new Frame(response, null, null, reserved);
        lock.lock();
        try {
            if (mayWriteInline && !closed && !draining && pending.isEmpty()
                    && frame.queuedLength() <= Globals.INLINE_FRAME_MAX_BYTES) {
                draining = true;
                return () -> writeInline(frame);
            }
        } finally {
            lock.unlock();
        }
        offer(frame);
        return InlineWrite.NONE;
    }

    private boolean offer(Frame frame) {
        final boolean overflowed;
        final boolean startDrain;
        lock.lock();
        try {
            if (closed) {
                budget.release(frame.reserved());
                return false;
            }
            overflowed = queuedBytes > 0 && frame.queuedLength() > maxQueuedBytes - queuedBytes;
            if (overflowed) {
                discardPending();
                budget.release(frame.reserved());
                closed = true;
                pending.add(new Frame(closeFrame(ErrorCode.OUTBOUND_BACKLOG), null, null, 0));
            } else {
                pending.add(frame);
                queuedBytes += frame.queuedLength();
            }
            startDrain = !draining;
            draining = true;
        } finally {
            lock.unlock();
        }
        if (startDrain) {
            drainer.execute(this::drain);
        }
        if (overflowed) {
            onOverflow.run();
            Thread.ofVirtual().name("lwnrdb-outbound-close").start(this::closeAfterGrace);
            return false;
        }
        return isOpen();
    }

    public boolean isOpen() {
        lock.lock();
        try {
            return !closed;
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitDrained(long timeoutMillis) {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (draining) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    public long stalledForMillis(long nowNanos) {
        return writing ? TimeUnit.NANOSECONDS.toMillis(nowNanos - lastProgressNanos) : 0L;
    }

    public boolean abortStalled() {
        if (aborted.compareAndSet(false, true)) {
            SocketClosing.closeInBackground(connection);
            return true;
        }
        return false;
    }

    public void discard() {
        lock.lock();
        try {
            closed = true;
            discardPending();
        } finally {
            lock.unlock();
        }
    }

    private void writeInline(Frame frame) {
        if (writeAndRelease(frame)) {
            final var next = nextFrame();
            if (next != null) {
                drainer.execute(() -> drainFrom(next));
            }
        }
    }

    private void drain() {
        final var first = nextFrame();
        if (first != null) {
            drainFrom(first);
        }
    }

    private void drainFrom(Frame first) {
        var frame = first;
        while (frame != null && writeAndRelease(frame)) {
            frame = nextFrame();
        }
    }

    private boolean writeAndRelease(Frame frame) {
        final var written = writeReleasing(frame);
        if (!written) {
            abandon();
        }
        return written;
    }

    private boolean writeReleasing(Frame frame) {
        try {
            writeTracked(frame);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            budget.release(frame.reserved());
        }
    }

    private void writeTracked(Frame frame) throws IOException {
        lastProgressNanos = System.nanoTime();
        writing = true;
        try {
            writeChunked(frame.head());
            writeChunked(frame.body());
            writeChunked(frame.tail());
            out.write(LINE_END);
            if (nothingPending()) {
                out.flush();
            }
        } finally {
            writing = false;
        }
    }

    private void writeChunked(EncodedJson segment) throws IOException {
        if (segment == null) {
            return;
        }
        final var length = segment.length();
        for (var offset = 0; offset < length; offset += WRITE_CHUNK_BYTES) {
            out.write(segment.bytes(), offset, Math.min(WRITE_CHUNK_BYTES, length - offset));
            lastProgressNanos = System.nanoTime();
        }
    }

    private Frame nextFrame() {
        final Frame frame;
        lock.lock();
        try {
            frame = pending.poll();
            if (frame != null) {
                if (!closed) {
                    queuedBytes -= frame.queuedLength();
                }
                return frame;
            }
            if (!closed) {
                finishDraining();
                return null;
            }
        } finally {
            lock.unlock();
        }
        closeThenFinishDraining();
        return null;
    }

    private void finishDraining() {
        draining = false;
        idle.signalAll();
    }

    private void closeThenFinishDraining() {
        closeQuietly();
        lock.lock();
        try {
            finishDraining();
        } finally {
            lock.unlock();
        }
    }

    private boolean nothingPending() {
        lock.lock();
        try {
            return pending.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private void discardPending() {
        var reserved = 0L;
        for (final var frame : pending) {
            reserved += frame.reserved();
        }
        budget.release(reserved);
        pending.clear();
        queuedBytes = 0;
    }

    private void abandon() {
        lock.lock();
        try {
            closed = true;
            discardPending();
        } finally {
            lock.unlock();
        }
        closeThenFinishDraining();
    }

    private void closeAfterGrace() {
        try {
            Thread.sleep(OVERFLOW_CLOSE_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            connection.close();
        } catch (IOException ignored) {
        }
    }
}
