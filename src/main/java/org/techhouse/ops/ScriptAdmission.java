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
    private final ConcurrentHashMap<String, SlicePool> userPermits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlicePool> databasePermits = new ConcurrentHashMap<>();
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

    private enum Slot {
        NONE, SHARED, RESERVED
    }

    private static final class SlicePool {
        private final Semaphore shared;
        private final Semaphore reserved;
        private final int sharedCapacity;
        private final int reservedCapacity;
        private int users;

        private SlicePool(int sharedCapacity, int reservedCapacity) {
            this.sharedCapacity = sharedCapacity;
            this.reservedCapacity = reservedCapacity;
            this.shared = new Semaphore(sharedCapacity, true);
            this.reserved = reservedCapacity > 0 ? new Semaphore(reservedCapacity, true) : null;
        }

        static SlicePool forUser(int limit) {
            return new SlicePool(limit, 0);
        }

        static SlicePool forDatabase(int limit) {
            final var reserve = limit >= 2 ? 1 : 0;
            return new SlicePool(limit - reserve, reserve);
        }

        Slot take(boolean privileged) {
            if (shared.tryAcquire()) {
                return Slot.SHARED;
            }
            if (privileged && reserved != null && reserved.tryAcquire()) {
                return Slot.RESERVED;
            }
            return Slot.NONE;
        }

        void give(Slot slot) {
            if (slot == Slot.RESERVED) {
                reserved.release();
            } else if (slot == Slot.SHARED) {
                shared.release();
            }
        }

        boolean idle() {
            return users == 0 && shared.availablePermits() == sharedCapacity && !shared.hasQueuedThreads()
                    && (reserved == null
                            || reserved.availablePermits() == reservedCapacity && !reserved.hasQueuedThreads());
        }
    }

    private record Slice(SlicePool pool, Slot slot) {
        static final Slice UNLIMITED = new Slice(null, Slot.SHARED);
        static final Slice REFUSED = new Slice(null, Slot.NONE);

        boolean taken() {
            return slot != Slot.NONE;
        }
    }

    public sealed interface Admission permits Permit, Refusal {
    }

    public record Refusal(String scope) implements Admission {
    }

    public final class Permit implements Admission, AutoCloseable {
        private final String username;
        private final String database;
        private final boolean nodeWide;
        private final Slice userSlice;
        private final Slice databaseSlice;
        private boolean closed;

        private Permit(String username, String database, boolean nodeWide, Slice userSlice, Slice databaseSlice) {
            this.username = username;
            this.database = database;
            this.nodeWide = nodeWide;
            this.userSlice = userSlice;
            this.databaseSlice = databaseSlice;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            giveBack(databasePermits, database, databaseSlice);
            giveBack(userPermits, username, userSlice);
            if (nodeWide) {
                release();
            }
        }
    }

    public Admission acquire(String username, String database, boolean privileged) {
        return switch (acquireSlices(username, database, privileged)) {
            case Refusal refusal -> refusal;
            case Permit slices -> withNodePermit(slices);
        };
    }

    private Admission withNodePermit(Permit slices) {
        if (!tryAcquire()) {
            slices.close();
            return new Refusal(SCOPE_NODE);
        }
        return new Permit(slices.username, slices.database, permits != null, slices.userSlice, slices.databaseSlice);
    }

    public Admission acquireSlices(String username, String database) {
        return acquireSlices(username, database, false);
    }

    private Admission acquireSlices(String username, String database, boolean privileged) {
        final var userSlice = take(userPermits, username, perUserCapacity, false, privileged);
        if (!userSlice.taken()) {
            rejectedPerUser.incrementAndGet();
            return new Refusal(SCOPE_USER);
        }
        final var databaseSlice = take(databasePermits, database, perDatabaseCapacity, true, privileged);
        if (!databaseSlice.taken()) {
            giveBack(userPermits, username, userSlice);
            rejectedPerDatabase.incrementAndGet();
            return new Refusal(SCOPE_DATABASE);
        }
        return new Permit(username, database, false, userSlice, databaseSlice);
    }

    public Admission acquireSlicesWithin(String username, String database, long waitMillis) {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        final var userSlice = waitFor(userPermits, username, perUserCapacity, false, deadline);
        if (!userSlice.taken()) {
            rejectedPerUser.incrementAndGet();
            return new Refusal(SCOPE_USER);
        }
        final var databaseSlice = waitFor(databasePermits, database, perDatabaseCapacity, true, deadline);
        if (!databaseSlice.taken()) {
            giveBack(userPermits, username, userSlice);
            rejectedPerDatabase.incrementAndGet();
            return new Refusal(SCOPE_DATABASE);
        }
        return new Permit(username, database, false, userSlice, databaseSlice);
    }

    private static SlicePool newPool(int limit, boolean database) {
        return database ? SlicePool.forDatabase(limit) : SlicePool.forUser(limit);
    }

    private static Slice take(ConcurrentHashMap<String, SlicePool> pools, String key, int limit, boolean database,
            boolean privileged) {
        if (limit <= 0 || key == null) {
            return Slice.UNLIMITED;
        }
        final var taken = new Slice[]{Slice.REFUSED};
        pools.compute(key, (_, mapped) -> {
            final var pool = mapped == null ? newPool(limit, database) : mapped;
            final var slot = pool.take(privileged);
            if (slot != Slot.NONE) {
                pool.users++;
                taken[0] = new Slice(pool, slot);
            }
            return pool.users == 0 ? null : pool;
        });
        return taken[0];
    }

    private static Slice waitFor(ConcurrentHashMap<String, SlicePool> pools, String key, int limit, boolean database,
            long deadlineNanos) {
        if (limit <= 0 || key == null) {
            return Slice.UNLIMITED;
        }
        final var entered = new Slice[1];
        pools.compute(key, (_, mapped) -> {
            final var pool = mapped == null ? newPool(limit, database) : mapped;
            pool.users++;
            entered[0] = new Slice(pool, pool.take(false));
            return pool;
        });
        final var pool = entered[0].pool();
        if (entered[0].taken()) {
            return entered[0];
        }
        var acquired = false;
        try {
            acquired = pool.shared.tryAcquire(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acquired) {
            return new Slice(pool, Slot.SHARED);
        }
        leave(pools, key, pool, Slot.NONE);
        return Slice.REFUSED;
    }

    private static void giveBack(ConcurrentHashMap<String, SlicePool> pools, String key, Slice slice) {
        if (slice.pool() != null) {
            leave(pools, key, slice.pool(), slice.slot());
        }
    }

    private static void leave(ConcurrentHashMap<String, SlicePool> pools, String key, SlicePool pool, Slot slot) {
        final var left = new boolean[1];
        pools.computeIfPresent(key, (_, mapped) -> {
            if (mapped != pool) {
                return mapped;
            }
            pool.give(slot);
            pool.users--;
            left[0] = true;
            return pool.idle() ? null : pool;
        });
        if (!left[0]) {
            pool.give(slot);
        }
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

    public long queueWaitMs() {
        return waitMs;
    }

    public int tenantPools() {
        return userPermits.size() + databasePermits.size();
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
