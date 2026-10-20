package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

public final class PageRowWriteBack {
    private static final Logger logger = Logger.logFor(PageRowWriteBack.class);
    private static final Map<String, DirtyRows> dirty = new ConcurrentHashMap<>();

    private record DirtyRows(String dbName, String collName, long incarnation, long since, Set<Long> pages) {
        DirtyRows with(DirtyRows later) {
            final var union = new HashSet<>(pages);
            union.addAll(later.pages());
            return new DirtyRows(dbName, collName, later.incarnation(), since, Set.copyOf(union));
        }
    }

    private PageRowWriteBack() {
    }

    static void touched(String dbName, String collName, long incarnation, Collection<Long> pages, long nowMillis) {
        if (pages.isEmpty()) {
            return;
        }
        dirty.merge(Cache.getCollectionIdentifier(dbName, collName),
                new DirtyRows(dbName, collName, incarnation, nowMillis, Set.copyOf(pages)), DirtyRows::with);
    }

    public static void flushDue(long nowMillis) {
        for (final var entry : List.copyOf(dirty.entrySet())) {
            if (nowMillis - entry.getValue().since() >= Globals.PAGE_ROWS_WRITE_BACK_MS) {
                flush(entry.getKey());
            }
        }
    }

    public static void flushAll() {
        for (final var key : List.copyOf(dirty.keySet())) {
            flush(key);
        }
    }

    static void forget(String dbName, String collName) {
        dirty.remove(Cache.getCollectionIdentifier(dbName, collName));
    }

    private static void flush(String key) {
        final var rows = dirty.remove(key);
        if (rows == null) {
            return;
        }
        try {
            AdminPageHelper.writeBackRows(rows.dbName(), rows.collName(), rows.incarnation(), rows.pages());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryLater(key, rows);
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not write the page-occupancy rows of " + key + "; they are retried, and the next"
                    + " startup rebuilds them from the page files: " + e.getMessage());
            retryLater(key, rows);
        }
    }

    private static void retryLater(String key, DirtyRows rows) {
        final var retry = new DirtyRows(rows.dbName(), rows.collName(), rows.incarnation(), System.currentTimeMillis(),
                rows.pages());
        dirty.merge(key, retry, (existing, _) -> retry.with(existing));
    }
}
