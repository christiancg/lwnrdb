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
    private static final Configuration configuration = Configuration.getInstance();

    private volatile int capacity;
    private volatile long waitMs;
    private volatile Semaphore permits;
    private volatile int perUserCapacity;
    private volatile int perDatabaseCapacity;
    private final ConcurrentHashMap<String, Semaphore> userPermits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DatabaseSlots> databasePermits = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong rejectedPerUser = new AtomicLong();
    private final AtomicLong rejectedPerDatabase = new AtomicLong();
    private final AtomicLong waited = new AtomicLong();

    public ScriptAdmission() {
        this(configuration.getMaxConcurrentScripts(), configuration.getScriptQueueWaitMs());
        this.perUserCapacity = configuration.getMaxConcurrentScriptsPerUser();
        this.perDatabaseCapacity = configuration.getMaxConcurrentScriptsPerDatabase();
    }

    public ScriptAdmission(int capacity, long waitMs) {
        this.capacity = capacity;
        this.waitMs = waitMs;
        this.permits = capacity > 0 ? new Semaphore(capacity, true) : null;
    }

    private record DatabaseSlots(Semaphore shared, Semaphore reserved) {
        static DatabaseSlots of(int limit) {
            final var reserve = limit >= 2 ? 1 : 0;
            return new DatabaseSlots(new Semaphore(limit - reserve, true),
                    reserve > 0 ? new Semaphore(reserve, true) : null);
        }

        DatabaseSlot take(boolean privileged) {
            if (shared.tryAcquire()) {
                return DatabaseSlot.SHARED;
            }
            if (privileged && reserved != null && reserved.tryAcquire()) {
                return DatabaseSlot.RESERVED;
            }
            return DatabaseSlot.NONE;
        }

        void give(DatabaseSlot slot) {
            if (slot == DatabaseSlot.RESERVED && reserved != null) {
                reserved.release();
            } else if (slot == DatabaseSlot.SHARED) {
                shared.release();
            }
        }
    }

    private enum DatabaseSlot {
        NONE, SHARED, RESERVED, UNLIMITED
    }

    public final class Permit implements AutoCloseable {
        private final String username;
        private final String database;
        private final boolean nodeWide;
        private final boolean perUser;
        private final DatabaseSlot databaseSlot;
        private boolean closed;

        private Permit(String username, String database, boolean nodeWide, boolean perUser, DatabaseSlot databaseSlot) {
            this.username = username;
            this.database = database;
            this.nodeWide = nodeWide;
            this.perUser = perUser;
            this.databaseSlot = databaseSlot;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseDatabaseSlot(database, databaseSlot);
            if (perUser) {
                releaseFrom(userPermits, username);
            }
            if (nodeWide) {
                release();
            }
        }
    }

    public Permit acquire(String username, String database, boolean privileged) {
        final var slices = acquireSlices(username, database, privileged);
        if (slices == null) {
            return null;
        }
        if (!tryAcquire()) {
            slices.close();
            lastScope.set(SCOPE_NODE);
            return null;
        }
        return new Permit(username, database, permits != null, slices.perUser, slices.databaseSlot);
    }

    public Permit acquireSlices(String username, String database) {
        return acquireSlices(username, database, false);
    }

    private Permit acquireSlices(String username, String database, boolean privileged) {
        final var userTaken = tryAcquireFrom(userPermits, username, perUserCapacity);
        if (!userTaken) {
            rejectedPerUser.incrementAndGet();
            lastScope.set(SCOPE_USER);
            return null;
        }
        final var databaseSlot = takeDatabaseSlot(database, privileged);
        if (databaseSlot == DatabaseSlot.NONE) {
            releaseFrom(userPermits, username);
            rejectedPerDatabase.incrementAndGet();
            lastScope.set(SCOPE_DATABASE);
            return null;
        }
        return new Permit(username, database, false, perUserCapacity > 0 && username != null, databaseSlot);
    }

    private DatabaseSlot takeDatabaseSlot(String database, boolean privileged) {
        final var limit = perDatabaseCapacity;
        if (limit <= 0 || database == null) {
            return DatabaseSlot.UNLIMITED;
        }
        return databasePermits.computeIfAbsent(database, _ -> DatabaseSlots.of(limit)).take(privileged);
    }

    private void releaseDatabaseSlot(String database, DatabaseSlot slot) {
        if (database == null || slot == DatabaseSlot.UNLIMITED) {
            return;
        }
        final var slots = databasePermits.get(database);
        if (slots != null) {
            slots.give(slot);
        }
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
