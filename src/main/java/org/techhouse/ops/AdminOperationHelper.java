package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.admin.AdminRecordStore;
import org.techhouse.ops.admin.AdminUsageHelper;

public final class AdminOperationHelper {
    private AdminOperationHelper() {
    }
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private static final AdminRecordStore<AdminTransactionEntry> TRANSACTION_OPS = new AdminRecordStore<>(
            Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, "transaction op", cache::getPkIndexTransaction,
            cache::removePkIndexTransaction, AdminTransactionEntry::fromJsonObject, (dbName, collName, type,
                    entries) -> AdminPageHelper.baseUpdateEntryCount(dbName, collName, type, entries, false));

    private static final AdminRecordStore<AdminTriggerRunEntry> TRIGGER_RUNS = new AdminRecordStore<>(
            Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, "trigger run", cache::getPkIndexTriggerRun,
            cache::removePkIndexTriggerRun, AdminTriggerRunEntry::fromJsonObject, (dbName, collName, type,
                    entries) -> AdminPageHelper.baseUpdateEntryCount(dbName, collName, type, entries, false));

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted)
            throws InterruptedException, IOException {
        AdminPageHelper.bulkUpdateEntryCount(dbName, collName, type, inserted);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry)
            throws InterruptedException, IOException {
        AdminPageHelper.updateEntryCount(dbName, collName, type, dbEntry);
    }

    public static void createPageCollections(String dbName, String collName) throws IOException {
        AdminPageHelper.createPageCollections(dbName, collName);
    }

    public static void deletePageCollections(String dbName, String collName) {
        AdminPageHelper.deletePageCollections(dbName, collName);
    }

    private static void lockAdmin(String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, collName);
    }

    private static void releaseAdmin(String collName) {
        locks.release(Globals.ADMIN_DB_NAME, collName);
    }

    // Insert-or-update by primary key. The caller owns the lock, because saveCollectionEntry must hold
    // the databases lock as well and lock policy differs per collection.
    private static PkIndexEntry writeAdminEntry(String collName, DbEntry entry, PkIndexEntry existingPk)
            throws IOException, InterruptedException {
        if (existingPk != null) {
            entry.setPage(existingPk.getPage());
            final var updateResult = fs.updateFromCollection(entry, existingPk);
            cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
            return updateResult.indexEntry();
        }
        entry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, collName, entry.byteSize()));
        final var pk = fs.insertIntoCollection(entry);
        AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, collName, EventType.CREATED, List.of(entry), false);
        return pk;
    }

    // Removes an admin entry's row and fixes the surviving in-memory PK positions. The caller owns the
    // lock and the cache eviction, which differ per collection.
    private static void eraseAdminEntry(String collName, DbEntry entry, PkIndexEntry pk)
            throws IOException, InterruptedException {
        entry.setPreviousByteSize(pk.getLength());
        cache.shiftPkPositionsAfterCompaction(fs.deleteFromCollection(pk));
        AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, collName, EventType.DELETED, List.of(entry), false);
    }

    public static void saveDatabaseEntry(AdminDbEntry dbEntry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        try {
            final var adminDbEntry = writeAdminEntry(Globals.ADMIN_DATABASES_COLLECTION_NAME, dbEntry,
                    cache.getPkIndexAdminDbEntry(dbEntry.get_id()));
            cache.putAdminDbEntry(dbEntry, adminDbEntry);
        } finally {
            releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        }
    }

    public static void deleteDatabaseEntry(String dbName) throws IOException, InterruptedException {
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
        if (adminIndexPkDbEntry != null) {
            lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
            try {
                final var adminDbEntry = cache.getAdminDbEntry(dbName);
                final var collectionsSnapshot = new ArrayList<>(adminDbEntry.getCollections());
                for (var collection : collectionsSnapshot) {
                    deleteCollectionEntry(dbName, collection);
                    AdminPageHelper.deletePageCollections(dbName, collection);
                }
                final var pkAfterCollectionRemoval = cache.getPkIndexAdminDbEntry(dbName);
                eraseAdminEntry(Globals.ADMIN_DATABASES_COLLECTION_NAME, adminDbEntry, pkAfterCollectionRemoval);
                cache.removeAdminDbEntry(dbName);
            } finally {
                releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
            }
        }
    }

    public static void updateDatabaseOwners(String dbName, java.util.List<String> owners)
            throws IOException, InterruptedException {
        final var dbEntry = cache.getAdminDbEntry(dbName);
        if (dbEntry != null) {
            dbEntry.setOwners(owners);
            saveDatabaseEntry(dbEntry);
        }
    }

    public static void saveCollectionEntry(AdminCollEntry dbEntry) throws IOException, InterruptedException {
        // This method also mutates the parent AdminDbEntry (the database's collection list) in the
        // admin/databases collection, so it must hold the databases lock too — otherwise it races a
        // concurrent saveDatabaseEntry/deleteDatabaseEntry that owns only that lock. Acquire databases
        // before collections to match deleteDatabaseEntry's lock order (reentrant, deadlock-safe).
        lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        try {
            final var pkIndexEntry = writeAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, dbEntry,
                    cache.getPkIndexAdminCollEntry(dbEntry.get_id()));
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
            AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME,
                    EventType.UPDATED, List.of(adminDbEntry), false);
        } finally {
            releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        }
    }

    public static void deleteCollectionEntry(String dbName, String collName) throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            // Also mutates the parent AdminDbEntry in admin/databases; hold the databases lock too,
            // acquired before collections to match deleteDatabaseEntry's order (reentrant when this
            // is called from deleteDatabaseEntry, which already holds the databases lock).
            lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
            lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            try {
                final var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
                adminCollEntry.setPreviousByteSize(adminIndexPkCollEntry.getLength());
                final var compaction = fs.deleteFromCollection(adminIndexPkCollEntry);
                cache.shiftPkPositionsAfterCompaction(compaction);
                cache.removeAdminCollEntry(collIdentifier);
                AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        EventType.DELETED, List.of(adminCollEntry), false);
                final var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
                final var adminDbEntry = cache.getAdminDbEntry(dbName);
                final var otherCollections = adminDbEntry.getCollections();
                otherCollections.remove(collName);
                adminDbEntry.setCollections(otherCollections);
                adminDbEntry.setPage(adminIndexPkDbEntry.getPage());
                final var dbUpdateResult = fs.updateFromCollection(adminDbEntry, adminIndexPkDbEntry);
                cache.shiftPkPositionsAfterCompaction(dbUpdateResult.compaction());
                cache.putAdminDbEntry(adminDbEntry, dbUpdateResult.indexEntry());
                AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME,
                        EventType.UPDATED, List.of(adminDbEntry), false);
            } finally {
                releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
                releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
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
            lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
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
                AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        EventType.UPDATED, List.of(adminCollEntry), false);
            } finally {
                releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            }
        }
    }

    public static void upsertCollectionUsage(CollectionUsageEvent event) throws IOException, InterruptedException {
        AdminUsageHelper.upsertCollectionUsage(event);
    }

    public static void cleanupCollectionUsage(long maxAgeMillis) throws IOException, InterruptedException {
        AdminUsageHelper.cleanupCollectionUsage(maxAgeMillis);
    }

    public static void saveUserEntry(AdminUserEntry userEntry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            final var adminUserEntry = writeAdminEntry(Globals.ADMIN_USERS_COLLECTION_NAME, userEntry,
                    cache.getPkIndexAdminUserEntry(userEntry.get_id()));
            cache.putAdminUserEntry(userEntry, adminUserEntry);
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }

    // Appends one buffered transaction operation to admin/transactions. Op records are always new
    // inserts (their _id is transactionId|seq, unique per transaction), so this only ever inserts.
    public static void saveTransactionOp(AdminTransactionEntry entry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        try {
            entry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME,
                    entry.byteSize()));
            final var savedPk = fs.insertIntoCollection(entry);
            cache.putPkIndexTransaction(savedPk);
            AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME,
                    EventType.CREATED, List.of(entry), false);
        } finally {
            releaseAdmin(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        }
    }

    public static List<AdminTransactionEntry> readTransactionOps(List<String> opIds)
            throws IOException, InterruptedException {
        return TRANSACTION_OPS.read(opIds);
    }

    // Used at both commit and rollback, and - with every op id - for startup cleanup of transactions
    // orphaned by a crash.
    public static void deleteTransactionOps(List<String> opIds) throws IOException, InterruptedException {
        TRANSACTION_OPS.delete(opIds);
    }

    // Appends one pending trigger run record. Records are always new inserts (their _id is runId|chunkSeq,
    // unique per run), so this only ever inserts.
    public static void saveTriggerRun(AdminTriggerRunEntry entry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        try {
            entry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME,
                    entry.byteSize()));
            final var savedPk = fs.insertIntoCollection(entry);
            cache.putPkIndexTriggerRun(savedPk);
            AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME,
                    EventType.CREATED, List.of(entry), false);
        } finally {
            releaseAdmin(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        }
    }

    public static List<AdminTriggerRunEntry> readTriggerRuns(List<String> recordIds)
            throws IOException, InterruptedException {
        return TRIGGER_RUNS.read(recordIds);
    }

    public static void deleteTriggerRuns(List<String> recordIds) throws IOException, InterruptedException {
        TRIGGER_RUNS.delete(recordIds);
    }

    public static void deleteUserEntry(String username) throws IOException, InterruptedException {
        var adminIndexPkUserEntry = cache.getPkIndexAdminUserEntry(username);
        if (adminIndexPkUserEntry != null) {
            lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
            try {
                eraseAdminEntry(Globals.ADMIN_USERS_COLLECTION_NAME, cache.getAdminUserEntry(username),
                        adminIndexPkUserEntry);
                cache.removeAdminUserEntry(username);
            } finally {
                releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
            }
        }
    }
}
