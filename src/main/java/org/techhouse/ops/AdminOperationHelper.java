package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.UnaryOperator;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.AdminPageHelper;
import org.techhouse.ops.admin.AdminRecordStore;
import org.techhouse.ops.admin.AdminUsageHelper;
import org.techhouse.ops.resp.OperationResponse;

public final class AdminOperationHelper {
    private AdminOperationHelper() {
    }
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final Logger logger = Logger.logFor(AdminOperationHelper.class);

    private static final AdminRecordStore<AdminTransactionEntry> TRANSACTION_OPS = new AdminRecordStore<>(
            Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, "transaction op", cache::getPkIndexTransaction,
            cache::removePkIndexTransaction, AdminTransactionEntry::fromJsonObject,
            (_, collName, type, entries) -> applyAdminPageDelta(collName, type, entries));

    private static final AdminRecordStore<AdminTriggerRunEntry> TRIGGER_RUNS = new AdminRecordStore<>(
            Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, "trigger run", cache::getPkIndexTriggerRun,
            cache::removePkIndexTriggerRun, AdminTriggerRunEntry::fromJsonObject,
            (_, collName, type, entries) -> applyAdminPageDelta(collName, type, entries));

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted)
            throws InterruptedException, IOException {
        AdminPageHelper.bulkUpdateEntryCount(dbName, collName, type, inserted, 0L);
    }

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted,
            long incarnation) throws InterruptedException, IOException {
        AdminPageHelper.bulkUpdateEntryCount(dbName, collName, type, inserted, incarnation);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry)
            throws InterruptedException, IOException {
        AdminPageHelper.updateEntryCount(dbName, collName, type, dbEntry, 0L);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry,
            long incarnation) throws InterruptedException, IOException {
        AdminPageHelper.updateEntryCount(dbName, collName, type, dbEntry, incarnation);
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

    // The caller owns the lock: policy differs per collection (saveCollectionEntry also holds databases).
    private static PkIndexEntry writeAdminEntry(String collName, DbEntry entry, PkIndexEntry existingPk)
            throws IOException {
        if (existingPk != null) {
            entry.setPage(existingPk.getPage());
            final var updateResult = fs.updateFromCollection(entry, existingPk);
            cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
            applyAdminPageDelta(collName, EventType.UPDATED, entry);
            return updateResult.indexEntry();
        }
        entry.setPage(cache.selectPageForInsert(Globals.ADMIN_DB_NAME, collName, entry.byteSize()));
        final var pk = fs.insertIntoCollection(entry);
        applyAdminPageDelta(collName, EventType.CREATED, entry);
        return pk;
    }

    private static void applyAdminPageDelta(String collName, EventType type, DbEntry entry) {
        applyAdminPageDelta(collName, type, List.of(entry));
    }

    private static void applyAdminPageDelta(String collName, EventType type, List<DbEntry> entries) {
        try {
            AdminPageHelper.baseUpdateEntryCount(Globals.ADMIN_DB_NAME, collName, type, entries, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted recording the page row of a landed admin/" + collName + " write", e);
        } catch (IOException e) {
            logger.error("Failed to record the page row of a landed admin/" + collName + " write", e);
        }
    }

    // The caller owns the lock and the cache eviction, which differ per collection.
    private static void eraseAdminEntry(String collName, DbEntry entry, PkIndexEntry pk)
            throws IOException, InterruptedException {
        entry.setPreviousByteSize(pk.getLength());
        entry.setPage(pk.getPage());
        cache.shiftPkPositionsAfterCompaction(fs.deleteFromCollection(pk));
        applyAdminPageDelta(collName, EventType.DELETED, entry);
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
                final var entryAfterCollectionRemoval = cache.getAdminDbEntry(dbName);
                eraseAdminEntry(Globals.ADMIN_DATABASES_COLLECTION_NAME, entryAfterCollectionRemoval,
                        pkAfterCollectionRemoval);
                cache.removeAdminDbEntry(dbName);
            } finally {
                releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
            }
        }
    }

    public static boolean updateDatabaseOwners(String dbName, List<String> owners)
            throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        try {
            final var dbEntry = cache.getAdminDbEntry(dbName);
            final var pk = cache.getPkIndexAdminDbEntry(dbName);
            if (dbEntry == null || pk == null) {
                return false;
            }
            final var updated = new AdminDbEntry(dbName, new ArrayList<>(dbEntry.getCollections()),
                    new ArrayList<>(owners));
            cache.putAdminDbEntry(updated, writeAdminEntry(Globals.ADMIN_DATABASES_COLLECTION_NAME, updated, pk));
            return true;
        } finally {
            releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        }
    }

    public static void saveCollectionEntry(AdminCollEntry dbEntry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        try {
            lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            final var existingCollectionPk = cache.getPkIndexAdminCollEntry(dbEntry.get_id());
            final var pkIndexEntry = writeAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, dbEntry,
                    existingCollectionPk);
            cache.putAdminCollectionEntry(dbEntry, pkIndexEntry);
            try {
                listCollectionInItsDatabase(dbEntry);
            } catch (IOException | InterruptedException | RuntimeException failure) {
                if (existingCollectionPk == null) {
                    unregisterNewCollectionRow(dbEntry, failure);
                }
                throw failure;
            }
        } finally {
            releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        }
    }

    private static void listCollectionInItsDatabase(AdminCollEntry collEntry) throws IOException, InterruptedException {
        final var split = collEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var current = cache.getAdminDbEntry(split[0]);
        if (current.getCollections().contains(split[1])) {
            return;
        }
        final var collections = new ArrayList<>(current.getCollections());
        collections.add(split[1]);
        publishDatabaseEntry(new AdminDbEntry(split[0], collections, new ArrayList<>(current.getOwners())));
    }

    private static void unregisterNewCollectionRow(AdminCollEntry collEntry, Exception cause) {
        try {
            eraseAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, collEntry,
                    cache.getPkIndexAdminCollEntry(collEntry.get_id()));
            cache.removeAdminCollEntry(collEntry.get_id());
        } catch (IOException | RuntimeException | InterruptedException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void publishDatabaseEntry(AdminDbEntry updated) throws IOException {
        final var pk = cache.getPkIndexAdminDbEntry(updated.get_id());
        cache.putAdminDbEntry(updated, writeAdminEntry(Globals.ADMIN_DATABASES_COLLECTION_NAME, updated, pk));
    }

    public static void deleteCollectionEntry(String dbName, String collName) throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            // Also mutates the parent AdminDbEntry in admin/databases; hold the databases lock too,
            // acquired before collections to match deleteDatabaseEntry's order.
            lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
            try {
                lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
                final var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
                adminCollEntry.setPreviousByteSize(adminIndexPkCollEntry.getLength());
                adminCollEntry.setPage(adminIndexPkCollEntry.getPage());
                final var compaction = fs.deleteFromCollection(adminIndexPkCollEntry);
                cache.shiftPkPositionsAfterCompaction(compaction);
                cache.removeAdminCollEntry(collIdentifier);
                applyAdminPageDelta(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, EventType.DELETED, adminCollEntry);
                final var adminDbEntry = cache.getAdminDbEntry(dbName);
                final var remaining = new ArrayList<>(adminDbEntry.getCollections());
                remaining.remove(collName);
                publishDatabaseEntry(new AdminDbEntry(dbName, remaining, new ArrayList<>(adminDbEntry.getOwners())));
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
                final var cachedEntry = cache.getAdminCollectionEntry(dbName, collName);
                final var indexes = new HashSet<>(cachedEntry.getIndexes());
                if (add) {
                    indexes.add(fieldName);
                } else {
                    indexes.remove(fieldName);
                }
                final var adminCollEntry = new AdminCollEntry(dbName, collName, indexes);
                adminCollEntry.setIncarnation(cachedEntry.getIncarnation());
                adminCollEntry.setPage(adminIndexPkCollEntry.getPage());
                final var updateResult = fs.updateFromCollection(adminCollEntry, adminIndexPkCollEntry);
                adminIndexPkCollEntry = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
                cache.putAdminCollectionEntry(adminCollEntry, adminIndexPkCollEntry);
                cache.putPkIndexAdminCollEntry(adminIndexPkCollEntry);
                applyAdminPageDelta(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, EventType.UPDATED, adminCollEntry);
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

    public static OperationResponse withUsersLock(OperationResponse.Attempt attempt) throws Exception {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            return attempt.run();
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
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

    public static void rewriteUsers(UnaryOperator<AdminUserEntry> rewrite) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            for (final var user : new ArrayList<>(cache.getAllAdminUserEntries())) {
                final var rewritten = rewrite.apply(user);
                if (rewritten != null) {
                    saveUserEntry(rewritten);
                }
            }
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }

    public static void rewriteDatabases(UnaryOperator<AdminDbEntry> rewrite) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        try {
            for (final var database : new ArrayList<>(cache.getAllAdminDbEntries())) {
                final var rewritten = rewrite.apply(database);
                if (rewritten != null) {
                    publishDatabaseEntry(rewritten);
                }
            }
        } finally {
            releaseAdmin(Globals.ADMIN_DATABASES_COLLECTION_NAME);
        }
    }

    public static void relistUnlistedCollections() throws IOException, InterruptedException {
        rewriteDatabases(AdminOperationHelper::withRegisteredCollectionsListed);
    }

    private static AdminDbEntry withRegisteredCollectionsListed(AdminDbEntry database) {
        final var listed = new ArrayList<>(database.getCollections());
        final var unlisted = cache.getCollectionNamesForDatabase(database.get_id()).stream()
                .filter(name -> !listed.contains(name)).toList();
        if (unlisted.isEmpty()) {
            return null;
        }
        logger.warning("Re-listing " + unlisted + " in database " + database.get_id()
                + ": registered but missing from the database's list, which a process killed during"
                + " CREATE_COLLECTION leaves");
        listed.addAll(unlisted);
        return new AdminDbEntry(database.get_id(), listed, new ArrayList<>(database.getOwners()));
    }

    public static void saveTransactionOp(AdminTransactionEntry entry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        try {
            cache.putPkIndexTransaction(writeAdminEntry(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, entry,
                    cache.getPkIndexTransaction(entry.get_id())));
        } finally {
            releaseAdmin(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        }
    }

    public static List<AdminTransactionEntry> readTransactionOps(List<String> opIds)
            throws IOException, InterruptedException {
        return TRANSACTION_OPS.read(opIds);
    }

    public static void deleteTransactionOps(List<String> opIds) throws IOException, InterruptedException {
        TRANSACTION_OPS.delete(opIds);
    }

    public static void saveTriggerRun(AdminTriggerRunEntry entry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        try {
            final var savedPk = writeAdminEntry(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, entry,
                    cache.getPkIndexTriggerRun(entry.get_id()));
            cache.putPkIndexTriggerRun(savedPk);
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
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            final var adminIndexPkUserEntry = cache.getPkIndexAdminUserEntry(username);
            final var userEntry = cache.getAdminUserEntry(username);
            if (adminIndexPkUserEntry != null && userEntry != null) {
                eraseAdminEntry(Globals.ADMIN_USERS_COLLECTION_NAME, userEntry, adminIndexPkUserEntry);
                cache.removeAdminUserEntry(username);
                clientTracker.deauthenticateUser(username);
            }
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }
}
