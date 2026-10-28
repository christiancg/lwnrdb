package org.techhouse.ops.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.RangeBounds;
import org.techhouse.utils.SearchUtils;

public final class RangeIdWalk implements Iterator<String> {
    private static final int MAX_PAGE = 4096;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    private final String dbName;
    private final String collName;
    private final RangeBounds bounds;
    private final boolean ascending;
    private int pageSize;
    private List<String> page = List.of();
    private int at;
    private boolean lastPage;
    private boolean started;
    private double lastValue;
    private String lastId;

    private RangeIdWalk(String dbName, String collName, RangeBounds bounds, boolean ascending, int firstPage) {
        this.dbName = dbName;
        this.collName = collName;
        this.bounds = bounds;
        this.ascending = ascending;
        this.pageSize = firstPage;
    }

    public static RangeIdWalk open(String dbName, String collName, RangeBounds bounds, boolean ascending,
            long firstPage, ReadObserver observer) throws IOException {
        final var fieldName = bounds.fieldName();
        final var pendingBefore = PendingWriteReconciler.pendingIds(dbName, collName);
        if (!rl.holdsCollectionLock(dbName, collName) || cache.hasNoIndex(dbName, collName, fieldName)
                || !pendingBefore.isEmpty()) {
            return null;
        }
        final var walk = new RangeIdWalk(dbName, collName, bounds, ascending,
                Math.clamp(firstPage, 1, Integer.MAX_VALUE - 8));
        if (!walk.readPage() || !PendingWriteReconciler.pendingIdsAround(pendingBefore, dbName, collName).isEmpty()) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(dbName, collName, fieldName));
        return walk;
    }

    @Override
    public boolean hasNext() {
        while (at >= page.size()) {
            if (lastPage) {
                return false;
            }
            if (pageSize < MAX_PAGE) {
                pageSize = Math.min(pageSize * 2, MAX_PAGE);
            }
            try {
                readPage();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return true;
    }

    @Override
    public String next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return page.get(at++);
    }

    private boolean readPage() throws IOException {
        final var fieldName = bounds.fieldName();
        try {
            rl.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class);
            page = new ArrayList<>();
            at = 0;
            if (entries == null) {
                lastPage = true;
                return false;
            }
            final var slice = SearchUtils.sliceBetween(entries, bounds);
            if (slice == null) {
                lastPage = true;
            } else {
                collect(entries, slice[0], slice[1]);
            }
            return true;
        } finally {
            rl.releaseIndexRead(dbName, collName, fieldName);
        }
    }

    private void collect(List<FieldIndexEntry<Number>> entries, int from, int to) {
        final var step = ascending ? 1 : -1;
        var index = started ? resumeIndex(entries, from, to) : ascending ? from : to - 1;
        while (index >= from && index < to && page.size() < pageSize) {
            final var entry = entries.get(index);
            final var value = entry.getValue().doubleValue();
            final var ids = IndexEntryReader.sortedIds(entry);
            var next = 0;
            if (started && FieldIndexEntry.compareIndexedNumbers(value, lastValue) == 0) {
                final var found = Collections.binarySearch(ids, lastId);
                next = found >= 0 ? found + 1 : -found - 1;
            }
            while (next < ids.size() && page.size() < pageSize) {
                lastId = ids.get(next++);
                lastValue = value;
                started = true;
                page.add(lastId);
            }
            if (next < ids.size()) {
                return;
            }
            index += step;
        }
        lastPage = index < from || index >= to;
    }

    private int resumeIndex(List<FieldIndexEntry<Number>> entries, int from, int to) {
        var low = from;
        var high = to;
        while (low < high) {
            final var mid = (low + high) >>> 1;
            final var compared = FieldIndexEntry.compareIndexedNumbers(entries.get(mid).getValue().doubleValue(),
                    lastValue);
            if (compared < 0 || !ascending && compared == 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return ascending ? low : low - 1;
    }
}
