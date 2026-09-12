package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class AdminPageHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private AdminPageHelper() {
    }

    private static void lockAdminPageCollection(String dbName, String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_PAGES_DB_NAME,
                String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    private static void releaseAdminPageCollection(String dbName, String collName) {
        locks.release(Globals.ADMIN_PAGES_DB_NAME,
                String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, inserted, type == EventType.CREATED);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, List.of(dbEntry), type == EventType.CREATED);
    }

    public static void baseUpdateEntryCount(final String dbName, final String collName, final EventType type,
            final List<DbEntry> insertedOrDeleted, final boolean skipMemoryDeltaForCreated)
            throws InterruptedException, IOException {
        if (insertedOrDeleted.isEmpty()) {
            return;
        }
        lockAdminPageCollection(dbName, collName);
        try {
            // Re-check under the lock: a concurrent drop must not lead to orphan page metadata.
            if (!Globals.ADMIN_DB_NAME.equals(dbName) && cache.getAdminCollectionEntry(dbName, collName) == null) {
                return;
            }
            final var pagesPerCollectionName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
            fs.createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName);
            final var grouped = insertedOrDeleted.stream().collect(Collectors.groupingBy(DbEntry::getPage));
            final var existingPageEntries = cache.getAdminPageEntries(dbName, collName);
            final var workingPageEntries = existingPageEntries != null
                    ? new ArrayList<>(existingPageEntries)
                    : new ArrayList<AdminPageEntry>();
            final var touchedPages = new ArrayList<AdminPageEntry>();
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
                    final var pageEntry = existing.get();
                    if (type != EventType.CREATED || !skipMemoryDeltaForCreated) {
                        pageEntry.setEntryCount(pageEntry.getEntryCount() + deltaCount);
                        pageEntry.setPageSize(pageEntry.getPageSize() + deltaBytes);
                    }
                    touchedPages.add(pageEntry);
                } else if (type == EventType.CREATED) {
                    final var newEntry = new AdminPageEntry(dbName, collName, page);
                    newEntry.setEntryCount(groupEntries.size());
                    newEntry.setPageSize(sumBytes);
                    workingPageEntries.add(newEntry);
                    newPages.add(newEntry);
                }
            }
            cache.putAdminPageEntries(dbName, collName, workingPageEntries);
            if (!newPages.isEmpty()) {
                insertAdminPages(pagesPerCollectionName, newPages);
            }
            if (!touchedPages.isEmpty()) {
                updateTouchedPagesInFileSystem(pagesPerCollectionName, touchedPages);
            }
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
            p.setPage(target);
            pendingPageBytes.merge(target, (long) size, Long::sum);
        }
        final var inserted = fs.bulkInsertIntoCollection(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName, newPages);
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
        for (var touchedPage : touchedPages) {
            final var matchingPkIdx = pkIdxList.stream().filter(pk -> pk.getValue().equals(touchedPage.get_id()))
                    .findFirst().orElse(null);
            if (matchingPkIdx == null) {
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
            final var bulkResult = fs.bulkUpdateFromCollection(Globals.ADMIN_PAGES_DB_NAME, pagesPerCollectionName,
                    indexedEntriesToUpdate);
            final var updated = bulkResult.updated();
            // Fix the in-memory positions of non-updated admin-page survivors shifted by the batch.
            bulkResult.compactions().forEach(cache::shiftPkPositionsAfterCompaction);
            for (var ie : updated) {
                pkIdxList.removeIf(pk -> pk.getValue().equals(ie.get_id()));
                pkIdxList.add(ie.getIndex());
            }
            trackInMemoryAdminPagesForUpdate(pagesPerCollectionName, updated);
        }
    }

    public static void createPageCollections(String dbName, String collName) throws IOException {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        fs.createCollectionFile(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
    }

    public static void deletePageCollections(String dbName, String collName) {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        fs.deleteCollectionFiles(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
        cache.removeAdminPageEntries(dbName, collName);
        cache.removeAdminPageEntries(Globals.ADMIN_PAGES_DB_NAME, pagesCollName);
    }
}
