package org.techhouse.ops;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Configuration;

public class ScriptAdmission {
    public static final String SCOPE_NODE = "node";
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_DATABASE = "database";

    private volatile int capacity;
    private volatile long waitMs;
    private volatile Semaphore permits;
    private volatile int perUserCapacity;
    private volatile int perDatabaseCapacity;
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
        pools.remove(key, pool);
    }

    public boolean tryAcquire() {
        final var pool = permits;
        if (pool == null) {
            return true;
        }
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
