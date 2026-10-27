package org.techhouse.conn;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

public class OutboundQueue {
    public static final Executor VIRTUAL_DRAINER = command -> Thread.ofVirtual().name("lwnrdb-outbound").start(command);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final long OVERFLOW_CLOSE_GRACE_MS = 1000L;
    private static final byte[] LINE_END = System.lineSeparator().getBytes(StandardCharsets.US_ASCII);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final OutputStream out;
    private final Closeable connection;
    private final long maxQueuedBytes;
    private final Executor drainer;
    private final Runnable onOverflow;
    private long queuedBytes;
    private boolean draining;
    private boolean closed;

    private record Frame(EncodedJson head, EncodedJson body, EncodedJson tail) {
        long queuedLength() {
            return lengthOf(head) + lengthOf(body) + lengthOf(tail) + LINE_END.length;
        }

        private static long lengthOf(EncodedJson segment) {
            return segment == null ? 0 : segment.length();
        }
    }

    public OutboundQueue(OutputStream out, Closeable connection, long maxQueuedBytes, Executor drainer,
            Runnable onOverflow) {
        this.out = out;
        this.connection = connection;
        this.maxQueuedBytes = maxQueuedBytes;
        this.drainer = drainer;
        this.onOverflow = onOverflow;
    }

    public static EncodedJson frameOf(OperationResponse response) {
        return eJson.toJsonFrame(response, response.estimatedJsonChars());
    }

    public static EncodedJson closeFrame(ErrorCode code) {
        return frameOf(new OperationResponse(OperationType.CLOSE_CONNECTION, code));
    }

    public static void sendDirectly(OutputStream out, ErrorCode code) throws IOException {
        writeFrame(out, new Frame(closeFrame(code), null, null));
        out.flush();
    }

    private static void writeFrame(OutputStream out, Frame frame) throws IOException {
        writeSegment(out, frame.head());
        writeSegment(out, frame.body());
        writeSegment(out, frame.tail());
        out.write(LINE_END);
    }

    private static void writeSegment(OutputStream out, EncodedJson segment) throws IOException {
        if (segment != null && segment.length() > 0) {
            out.write(segment.bytes(), 0, segment.length());
        }
    }

    public boolean offer(byte[] frame) {
        return offer(EncodedJson.of(frame));
    }

    public boolean offer(EncodedJson frame) {
        return offer(new Frame(frame, null, null));
    }

    public boolean offer(EncodedJson head, EncodedJson body, EncodedJson tail) {
        return offer(new Frame(head, body, tail));
    }

    private boolean offer(Frame frame) {
        final boolean overflowed;
        final boolean startDrain;
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            overflowed = queuedBytes > 0 && frame.queuedLength() > maxQueuedBytes - queuedBytes;
            if (overflowed) {
                pending.clear();
                queuedBytes = 0;
                closed = true;
                pending.add(new Frame(closeFrame(ErrorCode.OUTBOUND_BACKLOG), null, null));
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

    private void drain() {
        var frame = nextFrame();
        while (frame != null) {
            try {
                writeFrame(out, frame);
                if (nothingPending()) {
                    out.flush();
                }
            } catch (IOException e) {
                abandon();
                return;
            }
            frame = nextFrame();
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

    private void abandon() {
        lock.lock();
        try {
            closed = true;
            pending.clear();
            queuedBytes = 0;
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
