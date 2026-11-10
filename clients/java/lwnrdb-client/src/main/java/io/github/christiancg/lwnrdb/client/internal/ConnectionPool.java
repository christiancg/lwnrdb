package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.PoolExhaustedException;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionPool implements AutoCloseable {
    private final ConnectionFactory factory;
    private final int size;
    private final Duration acquireTimeout;
    private final Duration shutdownTimeout;
    private final Semaphore permits;
    private final Deque<WireConnection> idle = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConnectionPool(ConnectionFactory factory) {
        this.factory = factory;
        this.size = factory.settings().poolSize();
        this.acquireTimeout = factory.settings().acquireTimeout();
        this.shutdownTimeout = factory.settings().shutdownTimeout();
        this.permits = new Semaphore(size, true);
    }

    public Lease borrow() {
        if (closed.get()) {
            throw new ConnectionLostException("the client is closed", null);
        }
        acquirePermit();
        try {
            WireConnection connection;
            while ((connection = idle.pollFirst()) != null) {
                if (connection.isOpen()) {
                    return new Lease(connection);
                }
            }
            return new Lease(factory.open());
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    public int idleConnections() {
        return idle.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drainIdle();
        try {
            if (permits.tryAcquire(size, shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                permits.release(size);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainIdle();
    }

    private void drainIdle() {
        WireConnection connection;
        while ((connection = idle.pollFirst()) != null) {
            connection.closeGracefully(shutdownTimeout);
        }
    }

    private void acquirePermit() {
        try {
            if (!permits.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new PoolExhaustedException(
                        "no connection became free within " + acquireTimeout + "; all " + size + " are in use");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoolExhaustedException("interrupted while waiting for a connection");
        }
    }

    public final class Lease implements AutoCloseable {
        private final WireConnection connection;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile boolean discard;

        Lease(WireConnection connection) {
            this.connection = connection;
        }

        public WireConnection connection() {
            return connection;
        }

        public void discard() {
            discard = true;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            if (discard || closed.get() || !connection.isOpen()) {
                connection.close();
            } else {
                idle.addFirst(connection);
            }
            permits.release();
        }
    }
}
