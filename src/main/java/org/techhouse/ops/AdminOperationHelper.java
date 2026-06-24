package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminCollectionUsageEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class AdminOperationHelper {
    private AdminOperationHelper() {
    }
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

    private static void lockAdminCollectionsCollection() throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    private static void releaseAdminCollectionsCollection() {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    private static void lockAdminDatabaseCollection() throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
    }

    private static void releaseAdminDatabaseCollection() {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
    }

    private static void lockAdminPageCollection(String dbName, String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    private static void releaseAdminPageCollection(String dbName, String collName) {
        locks.release(Globals.ADMIN_DB_NAME, String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName));
    }

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, inserted);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, List.of(dbEntry));
    }

    private static void baseUpdateEntryCount(final String dbName, final String collName, final EventType type,
            final List<DbEntry> insertedOrDeleted) throws InterruptedException, IOException {
        if (insertedOrDeleted.isEmpty()) {
            return;
        }
        lockAdminPageCollection(dbName, collName);
        try {
            final var pagesPerCollectionName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
            fs.createCollectionFile(Globals.ADMIN_DB_NAME, pagesPerCollectionName);
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
                    pageEntry.setEntryCount(pageEntry.getEntryCount() + deltaCount);
                    pageEntry.setPageSize(pageEntry.getPageSize() + deltaBytes);
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
            final var target = cache.selectPageForInsert(Globals.ADMIN_DB_NAME, pagesPerCollectionName, size,
                    pendingPageBytes);
            p.setPage(target);
            pendingPageBytes.merge(target, (long) size, Long::sum);
        }
        final var inserted = fs.bulkInsertIntoCollection(Globals.ADMIN_DB_NAME, pagesPerCollectionName, newPages);
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_DB_NAME, pagesPerCollectionName);
        for (var ie : inserted) {
            pkIdxList.add(ie.getIndex());
        }
        trackInMemoryAdminPages(pagesPerCollectionName, inserted);
    }

    private static void trackInMemoryAdminPages(String pagesPerCollectionName, List<IndexedDbEntry> inserted) {
        for (var ie : inserted) {
            final var page = ie.getIndex().getPage();
            final var bytes = ie.getIndex().getLength();
            final var existing = cache.getAdminPageEntry(Globals.ADMIN_DB_NAME, pagesPerCollectionName, page);
            if (existing != null) {
                existing.setEntryCount(existing.getEntryCount() + 1);
                existing.setPageSize(existing.getPageSize() + bytes);
            } else {
                final var newEntry = new AdminPageEntry(Globals.ADMIN_DB_NAME, pagesPerCollectionName, page);
                newEntry.setEntryCount(1);
                newEntry.setPageSize(bytes);
                cache.addAdminPageEntries(Globals.ADMIN_DB_NAME, pagesPerCollectionName, newEntry);
            }
        }
    }

    private static void trackInMemoryAdminPagesForUpdate(String pagesPerCollectionName, List<IndexedDbEntry> updated) {
        for (var ie : updated) {
            final var page = ie.getIndex().getPage();
            final var newBytes = ie.getIndex().getLength();
            final var prevBytes = ie.getPreviousByteSize();
            final var existing = cache.getAdminPageEntry(Globals.ADMIN_DB_NAME, pagesPerCollectionName, page);
            if (existing != null) {
                existing.setPageSize(existing.getPageSize() + newBytes - prevBytes);
            } else {
                final var newEntry = new AdminPageEntry(Globals.ADMIN_DB_NAME, pagesPerCollectionName, page);
                newEntry.setEntryCount(0);
                newEntry.setPageSize(newBytes);
                cache.addAdminPageEntries(Globals.ADMIN_DB_NAME, pagesPerCollectionName, newEntry);
            }
        }
    }

    private static void updateTouchedPagesInFileSystem(String pagesPerCollectionName, List<AdminPageEntry> touchedPages)
            throws IOException {
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_DB_NAME, pagesPerCollectionName);
        final var indexedEntriesToUpdate = new ArrayList<IndexedDbEntry>();
        for (var touchedPage : touchedPages) {
            final var matchingPkIdx = pkIdxList.stream().filter(pk -> pk.getValue().equals(touchedPage.get_id()))
                    .findFirst().orElse(null);
            if (matchingPkIdx == null) {
                continue;
            }
            final var indexedEntry = new IndexedDbEntry();
            indexedEntry.set_id(touchedPage.get_id());
            indexedEntry.setDatabaseName(Globals.ADMIN_DB_NAME);
            indexedEntry.setCollectionName(pagesPerCollectionName);
            indexedEntry.setData(touchedPage.getData());
            indexedEntry.setIndex(matchingPkIdx);
            indexedEntriesToUpdate.add(indexedEntry);
        }
        if (!indexedEntriesToUpdate.isEmpty()) {
            final var bulkResult = fs.bulkUpdateFromCollection(Globals.ADMIN_DB_NAME, pagesPerCollectionName,
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

    public static void saveDatabaseEntry(AdminDbEntry dbEntry) throws IOException, InterruptedException {
        lockAdminDatabaseCollection();
        try {
            var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbEntry.get_id());
            PkIndexEntry adminDbEntry;
            if (adminIndexPkDbEntry != null) {
                dbEntry.setPage(adminIndexPkDbEntry.getPage());
                final var updateResult = fs.updateFromCollection(dbEntry, adminIndexPkDbEntry);
                adminDbEntry = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
            } else {
                dbEntry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME,
                        Globals.ADMIN_DATABASES_COLLECTION_NAME, dbEntry.byteSize()));
                adminDbEntry = fs.insertIntoCollection(dbEntry);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, EventType.CREATED,
                        List.of(dbEntry));
            }
            cache.putAdminDbEntry(dbEntry, adminDbEntry);
        } finally {
            releaseAdminDatabaseCollection();
        }
    }

    public static void deleteDatabaseEntry(String dbName) throws IOException, InterruptedException {
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
        if (adminIndexPkDbEntry != null) {
            lockAdminDatabaseCollection();
            try {
                final var adminDbEntry = cache.getAdminDbEntry(dbName);
                // we need to create a new list to avoid concurrent modification exception
                //      as the array is also being modified inside the method deleteCollectionEntry
                final var collections = new ArrayList<>(adminDbEntry.getCollections());
                for (var collection : collections) {
                    deleteCollectionEntry(dbName, collection);
                }
                // we need to reload this variable as the removal of collections
                //      will change the database entry
                adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
                adminDbEntry.setPreviousByteSize(adminIndexPkDbEntry.getLength());
                final var compaction = fs.deleteFromCollection(adminIndexPkDbEntry);
                cache.shiftPkPositionsAfterCompaction(compaction);
                cache.removeAdminDbEntry(dbName);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, EventType.DELETED,
                        List.of(adminDbEntry));
            } finally {
                releaseAdminDatabaseCollection();
            }
        }
    }

    public static AdminDbEntry getDatabaseEntry(String dbName) {
        return cache.getAdminDbEntry(dbName);
    }

    public static void updateDatabaseOwners(String dbName, java.util.List<String> owners)
            throws IOException, InterruptedException {
        final var dbEntry = cache.getAdminDbEntry(dbName);
        if (dbEntry != null) {
            dbEntry.setOwners(owners);
            saveDatabaseEntry(dbEntry);
        }
    }

    public static void createPageCollections(String dbName, String collName) throws IOException {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        fs.createCollectionFile(Globals.ADMIN_DB_NAME, pagesCollName);
    }

    public static void deletePageCollections(String dbName, String collName) {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        fs.deleteCollectionFiles(Globals.ADMIN_DB_NAME, pagesCollName);
        cache.removeAdminPageEntries(dbName, collName);
        cache.removeAdminPageEntries(Globals.ADMIN_DB_NAME, pagesCollName);
    }

    public static void saveCollectionEntry(AdminCollEntry dbEntry) throws IOException, InterruptedException {
        lockAdminCollectionsCollection();
        try {
            var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(dbEntry.get_id());
            PkIndexEntry pkIndexEntry;
            if (adminIndexPkCollEntry != null) {
                dbEntry.setPage(adminIndexPkCollEntry.getPage());
                final var updateResult = fs.updateFromCollection(dbEntry, adminIndexPkCollEntry);
                pkIndexEntry = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
            } else {
                dbEntry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME,
                        Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, dbEntry.byteSize()));
                pkIndexEntry = fs.insertIntoCollection(dbEntry);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        EventType.CREATED, List.of(dbEntry));
            }
            cache.putAdminCollectionEntry(dbEntry, pkIndexEntry);
            final var split = dbEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
            final var adminDbEntry = cache.getAdminDbEntry(split[0]);
            var adminDbPkIndexEntry = cache.getPkIndexAdminDbEntry(split[0]);
            final var collections = adminDbEntry.getCollections();
            collections.add(split[1]);
            adminDbEntry.setCollections(collections);
            adminDbEntry.setPage(adminDbPkIndexEntry.getPage());
            final var dbUpdateResult = fs.updateFromCollection(adminDbEntry, adminDbPkIndexEntry);
            adminDbPkIndexEntry = dbUpdateResult.indexEntry();
            cache.shiftPkPositionsAfterCompaction(dbUpdateResult.compaction());
            cache.putPkIndexAdminDbEntry(adminDbPkIndexEntry);
            baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, EventType.UPDATED,
                    List.of(adminDbEntry));
        } finally {
            releaseAdminCollectionsCollection();
        }
    }

    public static void deleteCollectionEntry(String dbName, String collName) throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            lockAdminCollectionsCollection();
            try {
                final var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
                adminCollEntry.setPreviousByteSize(adminIndexPkCollEntry.getLength());
                final var compaction = fs.deleteFromCollection(adminIndexPkCollEntry);
                cache.shiftPkPositionsAfterCompaction(compaction);
                cache.removeAdminCollEntry(collIdentifier);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        EventType.DELETED, List.of(adminCollEntry));
                final var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
                final var adminDbEntry = cache.getAdminDbEntry(dbName);
                final var otherCollections = adminDbEntry.getCollections();
                otherCollections.remove(collName);
                adminDbEntry.setCollections(otherCollections);
                adminDbEntry.setPage(adminIndexPkDbEntry.getPage());
                final var dbUpdateResult = fs.updateFromCollection(adminDbEntry, adminIndexPkDbEntry);
                cache.shiftPkPositionsAfterCompaction(dbUpdateResult.compaction());
                cache.putAdminDbEntry(adminDbEntry, dbUpdateResult.indexEntry());
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, EventType.UPDATED,
                        List.of(adminDbEntry));
            } finally {
                releaseAdminCollectionsCollection();
            }
        }
    }

    public static AdminCollEntry getCollectionEntry(String dbName, String collName) {
        return cache.getAdminCollectionEntry(dbName, collName);
    }

    public static void saveNewIndex(String dbName, String collName, String fieldName)
            throws IOException, InterruptedException {
        internalUpdateAdminColl(dbName, collName, fieldName, true);
    }

    public static void deleteIndex(String dbName, String collName, String fieldName)
            throws IOException, InterruptedException {
        internalUpdateAdminColl(dbName, collName, fieldName, false);
    }

    private static void internalUpdateAdminColl(String dbName, String collName, String fieldName, boolean add)
            throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            lockAdminCollectionsCollection();
            try {
                var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
                final var indexes = new HashSet<>(adminCollEntry.getIndexes());
                if (add) {
                    indexes.add(fieldName);
                } else {
                    indexes.remove(fieldName);
                }
                adminCollEntry.setIndexes(indexes);
                adminCollEntry.setPage(adminIndexPkCollEntry.getPage());
                final var updateResult = fs.updateFromCollection(adminCollEntry, adminIndexPkCollEntry);
                adminIndexPkCollEntry = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
                cache.putAdminCollectionEntry(adminCollEntry, adminIndexPkCollEntry);
                cache.putPkIndexAdminCollEntry(adminIndexPkCollEntry);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        EventType.UPDATED, List.of(adminCollEntry));
            } finally {
                releaseAdminCollectionsCollection();
            }
        }
    }

    private static void lockAdminUsersCollection() throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
    }

    private static void releaseAdminUsersCollection() {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
    }

    public static void saveUserEntry(AdminUserEntry userEntry) throws IOException, InterruptedException {
        lockAdminUsersCollection();
        try {
            var adminIndexPkUserEntry = cache.getPkIndexAdminUserEntry(userEntry.get_id());
            PkIndexEntry adminUserEntry;
            if (adminIndexPkUserEntry != null) {
                userEntry.setPage(adminIndexPkUserEntry.getPage());
                final var updateResult = fs.updateFromCollection(userEntry, adminIndexPkUserEntry);
                adminUserEntry = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
            } else {
                userEntry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                        userEntry.byteSize()));
                adminUserEntry = fs.insertIntoCollection(userEntry);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, EventType.CREATED,
                        List.of(userEntry));
            }
            cache.putAdminUserEntry(userEntry, adminUserEntry);
        } finally {
            releaseAdminUsersCollection();
        }
    }

    private static void lockAdminCollectionUsageCollection() throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
    }

    private static void releaseAdminCollectionUsageCollection() {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
    }

    public static void upsertCollectionUsage(CollectionUsageEvent event) throws IOException, InterruptedException {
        if (Globals.ADMIN_DB_NAME.equals(event.getDbName())) {
            return;
        }
        memoryManagement.recordAccess(event.getKind(), event.getDbName(), event.getCollName(), event.getIndexKey());
        final var counter = memoryManagement.getCounter(event.getKind(), event.getDbName(), event.getCollName(),
                event.getIndexKey());
        if (counter == null) {
            return;
        }
        lockAdminCollectionUsageCollection();
        try {
            final var entryId = AdminCollectionUsageEntry.buildId(counter.dbName(), counter.collName(),
                    counter.indexKey());
            final var usageEntry = new AdminCollectionUsageEntry(counter.kind(), counter.dbName(), counter.collName(),
                    counter.indexKey(), counter.getAccessCount(), counter.getLastAccessMillis());
            final var existingPk = cache.getPkIndexCollectionUsage(entryId);
            PkIndexEntry savedPk;
            if (existingPk != null) {
                usageEntry.setPage(existingPk.getPage());
                usageEntry.setPreviousByteSize(existingPk.getLength());
                final var updateResult = fs.updateFromCollection(usageEntry, existingPk);
                savedPk = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME, EventType.UPDATED,
                        List.of(usageEntry));
            } else {
                usageEntry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME,
                        usageEntry.byteSize()));
                savedPk = fs.insertIntoCollection(usageEntry);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME, EventType.CREATED,
                        List.of(usageEntry));
            }
            cache.putPkIndexCollectionUsage(savedPk);
        } finally {
            releaseAdminCollectionUsageCollection();
        }
    }

    public static void cleanupCollectionUsage(long maxAgeMillis) throws IOException, InterruptedException {
        final var threshold = System.currentTimeMillis() - maxAgeMillis;
        lockAdminCollectionUsageCollection();
        try {
            final var pkIndexes = new ArrayList<>(cache.getCollectionUsagePkIndexes().values());
            for (var pk : pkIndexes) {
                final DbEntry entry;
                try {
                    entry = fs.getById(pk);
                } catch (Exception ex) {
                    throw new IOException("Failed to read collection usage entry " + pk.getValue(), ex);
                }
                if (entry == null)
                    continue;
                final var data = entry.getData();
                data.addProperty(Globals.PK_FIELD, entry.get_id());
                final var usage = AdminCollectionUsageEntry.fromJsonObject(data);
                if (usage.getLastAccessMillis() < threshold) {
                    usage.setPreviousByteSize(pk.getLength());
                    final var compaction = fs.deleteFromCollection(pk);
                    cache.shiftPkPositionsAfterCompaction(compaction);
                    cache.removePkIndexCollectionUsage(pk.getValue());
                    memoryManagement.clearCounter(usage.getKind(), usage.getDbName(), usage.getCollName(),
                            usage.getIndexKey());
                    baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME, EventType.DELETED,
                            List.of(usage));
                }
            }
        } finally {
            releaseAdminCollectionUsageCollection();
        }
    }

    public static void deleteUserEntry(String username) throws IOException, InterruptedException {
        var adminIndexPkUserEntry = cache.getPkIndexAdminUserEntry(username);
        if (adminIndexPkUserEntry != null) {
            lockAdminUsersCollection();
            try {
                final var adminUserEntry = cache.getAdminUserEntry(username);
                adminUserEntry.setPreviousByteSize(adminIndexPkUserEntry.getLength());
                final var compaction = fs.deleteFromCollection(adminIndexPkUserEntry);
                cache.shiftPkPositionsAfterCompaction(compaction);
                cache.removeAdminUserEntry(username);
                baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, EventType.DELETED,
                        List.of(adminUserEntry));
            } finally {
                releaseAdminUsersCollection();
            }
        }
    }
}
