package org.techhouse.ops;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Configuration;

/**
 * Bounds how many client-initiated script runs - RUN_SCRIPT and CALL_PROCEDURE - hold an interpreter on this
 * node at once. The per-run limits bound one run; this bounds their sum, so the node's worst-case script heap
 * is the capacity times {@code scriptMaxMemoryBytes} rather than the number of connected clients times it.
 *
 * <p>Triggers and scheduled procedures are deliberately exempt: they are already bounded by their own worker
 * pools ({@code triggerThreads}/{@code scheduleThreads}), and a trigger refused for want of a permit would be
 * a <em>dropped</em> trigger rather than a retried one - its pending-run record is consumed by the transaction
 * that applies its effects, so nothing would replay it.
 *
 * <p>The capacity is read once, in the constructor: {@code Configuration} is loaded at startup and never
 * reloaded, so there is no live-reload path to honour here. {@link #reconfigure(int, long)} is the single
 * exception and the only thing that makes these fields mutable.
 */
public class ScriptAdmission {
    public static final String SCOPE_NODE = "node";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_DATABASE = "database";

    private volatile int capacity;
    private volatile long waitMs;
    // Fair, so a burst of arrivals cannot indefinitely overtake a thread already waiting and turn the
    // bounded wait into an unbounded one.
    private volatile Semaphore permits;
    private volatile int perUserCapacity;
    private volatile int perDatabaseCapacity;
    // Created on demand and removed once fully available again, so a node that sees many one-off users does
    // not accumulate an entry per name.
    private final ConcurrentHashMap<String, Semaphore> userPermits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> databasePermits = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong rejectedPerUser = new AtomicLong();
    private final AtomicLong rejectedPerDatabase = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();

    public ScriptAdmission() {
        this(Configuration.getInstance().getMaxConcurrentScripts(), Configuration.getInstance().getScriptQueueWaitMs());
        this.perUserCapacity = Configuration.getInstance().getMaxConcurrentScriptsPerUser();
        this.perDatabaseCapacity = Configuration.getInstance().getMaxConcurrentScriptsPerDatabase();
    }

    public ScriptAdmission(int capacity, long waitMs) {
        this.capacity = capacity;
        this.waitMs = waitMs;
        this.permits = capacity > 0 ? new Semaphore(capacity, true) : null;
    }

    /**
     * What one admitted run holds. Recording which pools were taken is what lets the release be exact: a
     * refusal at the per-user level must give the node-wide permit back, and a boolean return could not say
     * which of the three to release.
     */
    public final class Permit implements AutoCloseable {
        private final String username;
        private final String database;
        private final boolean nodeWide;
        private final boolean perUser;
        private final boolean perDatabase;
        private boolean closed;

        private Permit(String username, String database, boolean nodeWide, boolean perUser, boolean perDatabase) {
            this.username = username;
            this.database = database;
            this.nodeWide = nodeWide;
            this.perUser = perUser;
            this.perDatabase = perDatabase;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (perDatabase) {
                releaseFrom(databasePermits, database);
            }
            if (perUser) {
                releaseFrom(userPermits, username);
            }
            if (nodeWide) {
                release();
            }
        }
    }

    /**
     * Acquires the node-wide permit and then this run's per-user and per-database slices. The inner two do
     * not wait: the bounded wait exists to smooth a node-wide burst, whereas a tenant already at its own
     * ceiling should be told so at once rather than occupy a node-wide permit while it queues.
     *
     * @return the permit to close in a {@code finally}, or {@code null} when the run was refused - see
     *         {@link #lastRefusalScope()} for which limit refused it.
     */
    public Permit acquire(String username, String database) {
        if (!tryAcquire()) {
            lastScope.set(SCOPE_NODE);
            return null;
        }
        final var userTaken = tryAcquireFrom(userPermits, username, perUserCapacity);
        if (!userTaken) {
            release();
            rejectedPerUser.incrementAndGet();
            lastScope.set(SCOPE_USER);
            return null;
        }
        final var databaseTaken = tryAcquireFrom(databasePermits, database, perDatabaseCapacity);
        if (!databaseTaken) {
            releaseFrom(userPermits, username);
            release();
            rejectedPerDatabase.incrementAndGet();
            lastScope.set(SCOPE_DATABASE);
            return null;
        }
        return new Permit(username, database, permits != null, perUserCapacity > 0 && username != null,
                perDatabaseCapacity > 0 && database != null);
    }

    // Thread-local rather than returned beside the permit: a refusal is answered on the calling thread and
    // read immediately, and threading a result object through would widen every call site for one string.
    private final ThreadLocal<String> lastScope = new ThreadLocal<>();

    public String lastRefusalScope() {
        final var scope = lastScope.get();
        return scope == null ? SCOPE_NODE : scope;
    }

    private boolean tryAcquireFrom(ConcurrentHashMap<String, Semaphore> pools, String key, int poolCapacity) {
        if (poolCapacity <= 0 || key == null) {
            return true;
        }
        return pools.computeIfAbsent(key, _ -> new Semaphore(poolCapacity, true)).tryAcquire();
    }

    private void releaseFrom(ConcurrentHashMap<String, Semaphore> pools, String key) {
        if (key == null) {
            return;
        }
        final var pool = pools.get(key);
        if (pool == null) {
            return;
        }
        pool.release();
        // Dropped once idle so the map cannot grow with one entry per name ever seen. A racing acquirer
        // simply recreates it at full capacity, which is the state it was removed in.
        pools.remove(key, pool);
    }

    /**
     * @return {@code true} when the caller holds a permit and must {@link #release()} it in a
     *         {@code finally}; {@code false} when the caller should answer {@code 503-6} without releasing.
     */
    public boolean tryAcquire() {
        final var pool = permits;
        if (pool == null) {
            return true;
        }
        // The uncontended fast path first, so an idle node never pays for a timed park.
        if (pool.tryAcquire()) {
            return true;
        }
        try {
            if (pool.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) {
                waited.incrementAndGet();
                return true;
            }
            rejected.incrementAndGet();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Resizes the pool and zeroes its counters. Nothing in production calls this - the capacity is fixed at
     * startup, as above - but a test needs a small, known cap, and the operation helpers hold this singleton
     * in {@code static final} fields, so the instance cannot be swapped for a differently-sized one. Having
     * it here rather than letting tests reflect into the fields keeps them off private names and off
     * reflective {@code final}-field writes, which a future JDK will refuse.
     */
    public void reconfigure(int newCapacity, long newWaitMs) {
        reconfigure(newCapacity, newWaitMs, 0, 0);
    }

    public void reconfigure(int newCapacity, long newWaitMs, int newPerUserCapacity, int newPerDatabaseCapacity) {
        capacity = newCapacity;
        waitMs = newWaitMs;
        permits = newCapacity > 0 ? new Semaphore(newCapacity, true) : null;
        perUserCapacity = newPerUserCapacity;
        perDatabaseCapacity = newPerDatabaseCapacity;
        userPermits.clear();
        databasePermits.clear();
        rejected.set(0L);
        rejectedPerUser.set(0L);
        rejectedPerDatabase.set(0L);
        waited.set(0L);
    }

    public void release() {
        final var pool = permits;
        if (pool != null) {
            pool.release();
        }
    }

    public int available() {
        final var pool = permits;
        return pool == null ? 0 : pool.availablePermits();
    }

    public int capacity() {
        return capacity;
    }

    public long getRejected() {
        return rejected.get();
    }

    public long getWaited() {
        return waited.get();
    }

    public int perUserCapacity() {
        return perUserCapacity;
    }

    public int perDatabaseCapacity() {
        return perDatabaseCapacity;
    }

    public long getRejectedPerUser() {
        return rejectedPerUser.get();
    }

    public long getRejectedPerDatabase() {
        return rejectedPerDatabase.get();
    }
}
