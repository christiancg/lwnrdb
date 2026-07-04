package org.techhouse.ops;

import java.util.Collections;
import java.util.List;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class SaveOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final Configuration configuration = Configuration.getInstance();

    private SaveOperationHelper() {
    }

    // True when rewriting the existing entry in place with the new (larger) value would push its page
    // past maxPageSize. Uses the cached page byte size (minus the old entry, plus the new one); when no
    // page metadata is available the check is skipped (falls back to the in-place update).
    public static boolean wouldOverflowPage(String dbName, String collName, PkIndexEntry idxEntry, DbEntry entry) {
        final var pageEntry = cache.getAdminPageEntry(dbName, collName, idxEntry.getPage());
        if (pageEntry == null) {
            return false;
        }
        final var projectedPageSize = pageEntry.getPageSize() - idxEntry.getLength() + entry.byteSize();
        return projectedPageSize > configuration.getMaxPageSize();
    }

    // Relocates a grown document that no longer fits on its page: removes it from the current page
    // (compacting the survivors) and re-inserts it into a fitting page. Modeled as a DELETE of the old
    // version followed by a CREATE of the new one so per-page metadata (the old page loses the entry,
    // the new page gains it) and the field indexes are maintained through the same background events the
    // standalone delete/insert paths emit. Runs while the caller already holds the collection write lock;
    // the caller performs the cross-cutting bookkeeping (markDirty / recordCollectionAccess) on the
    // returned entry.
    public static PkIndexEntry relocateOnGrowUpdate(String dbName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, List<PkIndexEntry> primaryKeyIndex) throws Exception {
        final var oldEntry = cache.getById(dbName, collName, idxEntry);
        oldEntry.setPage(idxEntry.getPage());
        final var compaction = fs.deleteFromCollection(idxEntry);
        cache.shiftPkPositionsAfterCompaction(compaction);
        primaryKeyIndex.remove(idxEntry);
        cache.evictEntry(dbName, collName, entry.get_id());
        pendingIndexWrites.mark(dbName, collName, entry.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, oldEntry));

        entry.setPage(cache.selectPageForInsert(dbName, collName, entry.byteSize()));
        final var relocatedPkIndexEntry = fs.insertIntoCollection(entry);
        cache.updatePageSizeInMemory(dbName, collName, relocatedPkIndexEntry.getPage(),
                relocatedPkIndexEntry.getLength());
        int insertAt = Collections.binarySearch(primaryKeyIndex, relocatedPkIndexEntry.getValue());
        if (insertAt < 0) {
            insertAt = -(insertAt + 1);
        }
        primaryKeyIndex.add(insertAt, relocatedPkIndexEntry);
        cache.addEntryToCache(dbName, collName, entry);
        pendingIndexWrites.mark(dbName, collName, entry.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.CREATED, dbName, collName, entry));
        return relocatedPkIndexEntry;
    }
}
