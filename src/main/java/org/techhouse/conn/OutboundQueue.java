package org.techhouse.conn;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;

public class OutboundQueue {
    public static final Executor VIRTUAL_DRAINER = command -> Thread.ofVirtual().name("lwnrdb-outbound").start(command);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final long OVERFLOW_CLOSE_GRACE_MS = 1000L;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final BufferedWriter writer;
    private final Closeable connection;
    private final long maxQueuedBytes;
    private final Executor drainer;
    private final Runnable onOverflow;
    private long queuedBytes;
    private boolean draining;
    private boolean closed;

    public OutboundQueue(BufferedWriter writer, Closeable connection, long maxQueuedBytes, Executor drainer,
            Runnable onOverflow) {
        this.writer = writer;
        this.connection = connection;
        this.maxQueuedBytes = maxQueuedBytes;
        this.drainer = drainer;
        this.onOverflow = onOverflow;
    }

    public static String closeFrame(ErrorCode code) {
        return eJson.toJson(new OperationResponse(OperationType.CLOSE_CONNECTION, code));
    }

    public static void sendDirectly(BufferedWriter writer, ErrorCode code) throws IOException {
        writer.write(closeFrame(code));
        writer.newLine();
        writer.flush();
    }

    public boolean offer(String line) {
        final boolean overflowed;
        final boolean startDrain;
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            overflowed = queuedBytes > 0 && line.length() > maxQueuedBytes - queuedBytes;
            if (overflowed) {
                pending.clear();
                queuedBytes = 0;
                closed = true;
                pending.add(closeFrame(ErrorCode.OUTBOUND_BACKLOG));
            } else {
                pending.add(line);
                queuedBytes += line.length();
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
        var line = nextLine();
        while (line != null) {
            try {
                writer.write(line);
                writer.newLine();
                if (nothingPending()) {
                    writer.flush();
                }
            } catch (IOException e) {
                abandon();
                return;
            }
            line = nextLine();
        }
    }

    private String nextLine() {
        final String line;
        final boolean finished;
        lock.lock();
        try {
            line = pending.poll();
            if (line != null && !closed) {
                queuedBytes -= line.length();
            }
            finished = line == null && closed;
            if (line == null) {
                draining = false;
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (finished) {
            closeQuietly();
        }
        return line;
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
            draining = false;
            idle.signalAll();
        } finally {
            lock.unlock();
        }
        closeQuietly();
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
