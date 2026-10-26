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

    private static final class DirtyRows {
        private final String dbName;
        private final String collName;
        private final long since;
        private final Set<Long> pages = new HashSet<>();
        private long incarnation;

        DirtyRows(String dbName, String collName, long since) {
            this.dbName = dbName;
            this.collName = collName;
            this.since = since;
        }

        void absorb(long laterIncarnation, Collection<Long> touchedPages) {
            incarnation = laterIncarnation;
            pages.addAll(touchedPages);
        }

        String dbName() {
            return dbName;
        }

        String collName() {
            return collName;
        }

        long since() {
            return since;
        }

        long incarnation() {
            return incarnation;
        }

        Set<Long> pages() {
            return pages;
        }
    }

    private PageRowWriteBack() {
    }

    static void touched(String dbName, String collName, long incarnation, Collection<Long> pages, long nowMillis) {
        if (pages.isEmpty()) {
            return;
        }
        dirty.compute(Cache.getCollectionIdentifier(dbName, collName), (_, existing) -> {
            final var rows = existing != null ? existing : new DirtyRows(dbName, collName, nowMillis);
            rows.absorb(incarnation, pages);
            return rows;
        });
    }

    public static void flushDue(long nowMillis) {
        if (dirty.isEmpty()) {
            return;
        }
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
        dirty.compute(key, (_, existing) -> {
            final var retry = new DirtyRows(rows.dbName(), rows.collName(), System.currentTimeMillis());
            retry.absorb(rows.incarnation(), rows.pages());
            if (existing != null) {
                retry.absorb(existing.incarnation(), existing.pages());
            }
            return retry;
        });
    }
}
