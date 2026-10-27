package org.techhouse.cache;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.data.DbEntry;

public final class ScanOrderMemo {
    static final int MAX_COLLECTIONS = 8;
    static final Comparator<DbEntry> BY_PAGE_THEN_ID = Comparator.comparingLong(DbEntry::getPage)
            .thenComparing(DbEntry::get_id);

    private record Order(WeakReference<Map<String, DbEntry>> source, String[] ids) {
    }

    private final Map<String, Order> orders = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Order> eldest) {
            return size() > MAX_COLLECTIONS;
        }
    };
    private final ReentrantLock lock = new ReentrantLock();

    public DbEntry[] inScanOrder(String identifier, Map<String, DbEntry> cached) {
        final var remembered = remembered(identifier, cached);
        if (remembered != null) {
            final var rows = replay(remembered, cached);
            if (rows != null) {
                return rows;
            }
        }
        final var rows = cached.values().toArray(DbEntry[]::new);
        Arrays.sort(rows, BY_PAGE_THEN_ID);
        final var ids = new String[rows.length];
        for (var i = 0; i < rows.length; i++) {
            ids[i] = rows[i].get_id();
        }
        remember(identifier, new Order(new WeakReference<>(cached), ids));
        return rows;
    }

    public int rememberedCollections() {
        lock.lock();
        try {
            return orders.size();
        } finally {
            lock.unlock();
        }
    }

    private String[] remembered(String identifier, Map<String, DbEntry> cached) {
        lock.lock();
        try {
            orders.values().removeIf(order -> order.source().get() == null);
            final var order = orders.get(identifier);
            return order == null || order.source().get() != cached ? null : order.ids();
        } finally {
            lock.unlock();
        }
    }

    private void remember(String identifier, Order order) {
        lock.lock();
        try {
            orders.put(identifier, order);
        } finally {
            lock.unlock();
        }
    }

    private static DbEntry[] replay(String[] ids, Map<String, DbEntry> cached) {
        if (ids.length != cached.size()) {
            return null;
        }
        final var rows = new DbEntry[ids.length];
        DbEntry previous = null;
        for (var i = 0; i < ids.length; i++) {
            final var row = cached.get(ids[i]);
            if (row == null || previous != null && BY_PAGE_THEN_ID.compare(previous, row) >= 0) {
                return null;
            }
            rows[i] = row;
            previous = row;
        }
        return rows;
    }
}
