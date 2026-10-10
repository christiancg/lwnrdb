package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ex.PartialBulkUpdateException;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PkCompaction;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class AdminPageHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Logger logger = Logger.logFor(AdminPageHelper.class);

    private AdminPageHelper() {
    }

    private record PageDelta(AdminPageEntry live, AdminPageEntry pending) {
        void publish() {
            live.setEntryCount(pending.getEntryCount());
            live.setPageSize(pending.getPageSize());
        }
    }

    private static AdminPageEntry pendingCopy(String dbName, String collName, AdminPageEntry live, int deltaCount,
            long deltaBytes) {
        final var pending = new AdminPageEntry(dbName, collName, live.getPage());
        pending.setEntryCount(live.getEntryCount() + deltaCount);
        pending.setPageSize(live.getPageSize() + deltaBytes);
        return pending;
    }

    private static void lockAdminPageCollection(String dbName, String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_PAGES_DB_NAME,
                String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    private static void releaseAdminPageCollection(String dbName, String collName) {
        locks.release(Globals.ADMIN_PAGES_DB_NAME,
                String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted,
            long incarnation) throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, inserted, type == EventType.CREATED, type == EventType.UPDATED,
                incarnation);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry,
            long incarnation) throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, List.of(dbEntry), type == EventType.CREATED,
                type == EventType.UPDATED, incarnation);
    }

    public static void recordLandedDelta(String collName, EventType type, List<DbEntry> entries) {
        try {
            baseUpdateEntryCount(Globals.ADMIN_DB_NAME, collName, type, entries, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted recording the page row of a landed admin/" + collName + " write", e);
        } catch (IOException e) {
            logger.error("Failed to record the page row of a landed admin/" + collName + " write", e);
        }
    }

    public static void baseUpdateEntryCount(final String dbName, final String collName, final EventType type,
            final List<DbEntry> insertedOrDeleted, final boolean skipMemoryDeltaForCreated)
            throws InterruptedException, IOException {
        baseUpdateEntryCount(dbName, collName, type, insertedOrDeleted, skipMemoryDeltaForCreated, false);
    }

    public static void baseUpdateEntryCount(final String dbName, final String collName, final EventType type,
            final List<DbEntry> insertedOrDeleted, final boolean skipMemoryDeltaForCreated,
            final boolean skipMemoryDeltaForUpdated) throws InterruptedException, IOException {
        baseUpdateEntryCount(dbName, collName, type, insertedOrDeleted, skipMemoryDeltaForCreated,
                skipMemoryDeltaForUpdated, 0L);
    }

    private static void baseUpdateEntryCount(final String dbName, final String collName, final EventType type,
            final List<DbEntry> insertedOrDeleted, final boolean skipMemoryDeltaForCreated,
            final boolean skipMemoryDeltaForUpdated, final long incarnation) throws InterruptedException, IOException {
        if (insertedOrDeleted.isEmpty()) {
            return;
        }
        final var deltaAlreadyAppliedOnWritePath = (type == EventType.CREATED && skipMemoryDeltaForCreated)
                || (type == EventType.UPDATED && skipMemoryDeltaForUpdated);
        lockAdminPageCollection(dbName, collName);
        try {
            // Re-check under the lock: a concurrent drop must not lead to orphan page metadata.
            if (!Globals.ADMIN_DB_NAME.equals(dbName)
                    && !CollectionIncarnation.isCurrent(dbName, collName, incarnation)) {
                return;
            }
            final var pagesPerCollectionName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
            fs.createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName);
            final var grouped = insertedOrDeleted.stream().collect(Collectors.groupingBy(DbEntry::getPage));
            final var existingPageEntries = cache.getAdminPageEntries(dbName, collName);
            final var workingPageEntries = existingPageEntries != null
                    ? new ArrayList<>(existingPageEntries)
                    : new ArrayList<AdminPageEntry>();
            final var deltas = new ArrayList<PageDelta>();
            final var newPages = new ArrayList<AdminPageEntry>();
            for (var groupedEntry : grouped.entrySet()) {
                final var page = groupedEntry.getKey();
                final var groupEntries = groupedEntry.getValue();
                final var sumBytes = groupEntries.stream().mapToLong(DbEntry::byteSize).sum();
                final var deltaBytes = switch (type) {
                    case UPDATED -> sumBytes - groupEntries.stream().mapToLong(DbEntry::getPreviousByteSize).sum();
                    case CREATED -> sumBytes;
                    case DELETED -> -sumBytes;
                };
                final var deltaCount = switch (type) {
                    case UPDATED -> 0;
                    case CREATED -> groupEntries.size();
                    case DELETED -> -groupEntries.size();
                };
                final var existing = workingPageEntries.stream().filter(p -> p.getPage() == page).findFirst();
                if (existing.isPresent()) {
                    final var live = existing.get();
                    final var pending = deltaAlreadyAppliedOnWritePath
                            ? pendingCopy(dbName, collName, live, 0, 0L)
                            : pendingCopy(dbName, collName, live, deltaCount, deltaBytes);
                    deltas.add(new PageDelta(live, pending));
                } else if (type == EventType.CREATED) {
                    final var newEntry = new AdminPageEntry(dbName, collName, page);
                    newEntry.setEntryCount(groupEntries.size());
                    newEntry.setPageSize(sumBytes);
                    workingPageEntries.add(newEntry);
                    newPages.add(newEntry);
                }
            }
            if (!newPages.isEmpty()) {
                insertAdminPages(pagesPerCollectionName, newPages);
            }
            if (!deltas.isEmpty()) {
                updateTouchedPagesInFileSystem(pagesPerCollectionName,
                        deltas.stream().map(PageDelta::pending).toList());
            }
            cache.addAdminPageEntries(dbName, collName, newPages);
            deltas.forEach(PageDelta::publish);
        } finally {
            releaseAdminPageCollection(dbName, collName);
        }
    }

    private static void insertAdminPages(String pagesPerCollectionName, List<AdminPageEntry> newPages)
            throws IOException {
        final var pendingPageBytes = new HashMap<Long, Long>();
        for (var p : newPages) {
            final var size = p.byteSize();
            final var target = cache.selectPageForInsert(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, size,
                    pendingPageBytes);
            p.setStoragePage(target);
            pendingPageBytes.merge(target, (long) size, Long::sum);
        }
        final var inserted = fs.bulkInsertIntoCollection(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, newPages,
                AdminPageEntry::getStoragePage);
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName);
        for (var ie : inserted) {
            pkIdxList.add(ie.getIndex());
        }
        trackInMemoryAdminPages(pagesPerCollectionName, inserted);
    }

    private static void trackInMemoryAdminPages(String pagesPerCollectionName, List<IndexedDbEntry> inserted) {
        for (var ie : inserted) {
            final var page = ie.getIndex().getPage();
            final var bytes = ie.getIndex().getLength();
            final var existing = cache.getAdminPageEntry(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, page);
            if (existing != null) {
                existing.setEntryCount(existing.getEntryCount() + 1);
                existing.setPageSize(existing.getPageSize() + bytes);
            } else {
                final var newEntry = new AdminPageEntry(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, page);
                newEntry.setEntryCount(1);
                newEntry.setPageSize(bytes);
                cache.addAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, newEntry);
            }
        }
    }

    private static void trackInMemoryAdminPagesForUpdate(String pagesPerCollectionName, List<IndexedDbEntry> updated) {
        for (var ie : updated) {
            final var page = ie.getIndex().getPage();
            final var newBytes = ie.getIndex().getLength();
            final var prevBytes = ie.getPreviousByteSize();
            final var existing = cache.getAdminPageEntry(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, page);
            if (existing != null) {
                existing.setPageSize(existing.getPageSize() + newBytes - prevBytes);
            } else {
                final var newEntry = new AdminPageEntry(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, page);
                newEntry.setEntryCount(0);
                newEntry.setPageSize(newBytes);
                cache.addAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, newEntry);
            }
        }
    }

    private static void updateTouchedPagesInFileSystem(String pagesPerCollectionName, List<AdminPageEntry> touchedPages)
            throws IOException {
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName);
        final var indexedEntriesToUpdate = new ArrayList<IndexedDbEntry>();
        final var firstTouchPages = new ArrayList<AdminPageEntry>();
        for (var touchedPage : touchedPages) {
            final var matchingPkIdx = pkIdxList.stream().filter(pk -> pk.getValue().equals(touchedPage.get_id()))
                    .findFirst().orElse(null);
            if (matchingPkIdx == null) {
                firstTouchPages.add(touchedPage);
                continue;
            }
            final var indexedEntry = new IndexedDbEntry();
            indexedEntry.set_id(touchedPage.get_id());
            indexedEntry.setDatabaseName(Globals.ADMIN_PAGES_DB_NAME);
            indexedEntry.setCollectionName(pagesPerCollectionName);
            indexedEntry.setData(touchedPage.getData());
            indexedEntry.setIndex(matchingPkIdx);
            indexedEntriesToUpdate.add(indexedEntry);
        }
        if (!indexedEntriesToUpdate.isEmpty()) {
            try {
                final var bulkResult = fs.bulkUpdateFromCollection(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName,
                        indexedEntriesToUpdate);
                applyBulkUpdateResult(pagesPerCollectionName, pkIdxList, bulkResult.updated(),
                        bulkResult.compactions());
            } catch (PartialBulkUpdateException e) {
                final var partial = e.getPartialResult();
                applyBulkUpdateResult(pagesPerCollectionName, pkIdxList, partial.updated(), partial.compactions());
                throw e;
            }
        }
        if (!firstTouchPages.isEmpty()) {
            insertAdminPages(pagesPerCollectionName, firstTouchPages);
        }
    }

    private static void applyBulkUpdateResult(String pagesPerCollectionName, List<PkIndexEntry> pkIdxList,
            List<IndexedDbEntry> updated, List<PkCompaction> compactions) {
        compactions.forEach(cache::shiftPkPositionsAfterCompaction);
        for (var ie : updated) {
            pkIdxList.removeIf(pk -> pk.getValue().equals(ie.get_id()));
            pkIdxList.add(ie.getIndex());
        }
        trackInMemoryAdminPagesForUpdate(pagesPerCollectionName, updated);
    }

    public static void replacePageEntries(String dbName, String collName, List<AdminPageEntry> corrected)
            throws IOException, InterruptedException {
        lockAdminPageCollection(dbName, collName);
        try {
            final var pagesPerCollectionName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
            fs.createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName);
            final var existing = cache.getAdminPageEntries(dbName, collName);
            final var deltas = new ArrayList<PageDelta>();
            final var newPages = new ArrayList<AdminPageEntry>();
            for (final var target : corrected) {
                final var live = existing == null
                        ? null
                        : existing.stream().filter(p -> p.getPage() == target.getPage()).findFirst().orElse(null);
                if (live == null) {
                    newPages.add(target);
                } else {
                    deltas.add(new PageDelta(live, pendingCopy(dbName, collName, live,
                            target.getEntryCount() - live.getEntryCount(), target.getPageSize() - live.getPageSize())));
                }
            }
            if (!newPages.isEmpty()) {
                insertAdminPages(pagesPerCollectionName, newPages);
            }
            if (!deltas.isEmpty()) {
                updateTouchedPagesInFileSystem(pagesPerCollectionName,
                        deltas.stream().map(PageDelta::pending).toList());
            }
            cache.addAdminPageEntries(dbName, collName, newPages);
            deltas.forEach(PageDelta::publish);
        } finally {
            releaseAdminPageCollection(dbName, collName);
        }
    }

    public static void createPageCollections(String dbName, String collName) throws IOException {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        deletePageCollections(dbName, collName);
        fs.createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
    }

    public static void deletePageCollections(String dbName, String collName) {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        final var exclusive = lockAdminPagesForDrop(dbName, collName);
        try {
            if (sharesPageFolder(dbName, collName)) {
                deleteOwnRows(dbName, collName, pagesCollName);
            } else {
                fs.deleteCollectionFiles(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
                cache.removeAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
            }
            cache.removeAdminPageEntries(dbName, collName);
        } finally {
            if (exclusive) {
                releaseAdminPageCollection(dbName, collName);
            }
        }
    }

    private static boolean sharesPageFolder(String dbName, String collName) {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        for (final var dbEntry : cache.getAllAdminDbEntries()) {
            final var collections = dbEntry.getCollections();
            for (final var otherColl : collections == null ? List.<String>of() : collections) {
                final var isSelf = dbEntry.get_id().equals(dbName) && otherColl.equals(collName);
                if (!isSelf && String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbEntry.get_id(), otherColl)
                        .equals(pagesCollName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteOwnRows(String dbName, String collName, String pagesCollName) {
        final var ownPrefix = Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR;
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
        final var ownRows = pkIdxList.stream().filter(pk -> pk.getValue().startsWith(ownPrefix)).toList();
        for (final var row : ownRows) {
            cache.shiftPkPositionsAfterCompaction(fs.deleteFromCollection(row));
            pkIdxList.remove(row);
            trackInMemoryAdminPageRemoval(pagesCollName, row);
        }
    }

    private static void trackInMemoryAdminPageRemoval(String pagesPerCollectionName, PkIndexEntry removed) {
        final var existing = cache.getAdminPageEntry(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName,
                removed.getPage());
        if (existing != null) {
            existing.setEntryCount(existing.getEntryCount() - 1);
            existing.setPageSize(existing.getPageSize() - removed.getLength());
        }
    }

    private static boolean lockAdminPagesForDrop(String dbName, String collName) {
        try {
            final var acquired = locks.tryLockWrite(Globals.ADMIN_PAGES_DB_NAME,
                    String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName),
                    Configuration.getInstance().getTransactionLockTimeoutMs());
            if (!acquired) {
                logger.warning("Dropping the page metadata of " + dbName + "|" + collName + " without its"
                        + " admin_pages lock: the background page writer may re-create rows for a collection"
                        + " that no longer exists");
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
