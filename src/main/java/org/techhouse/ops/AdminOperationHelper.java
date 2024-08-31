package org.techhouse.ops;

import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

public class AdminOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

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

    public static void bulkUpdateEntryCount(String dbName, String collName, int insertedCount)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, insertedCount);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type)
            throws IOException, InterruptedException {
        if (type != EventType.UPDATED) {
            baseUpdateEntryCount(dbName, collName, type == EventType.CREATED ? 1 : -1);
        }
    }

    private static void baseUpdateEntryCount(final String dbName, final String collName, final int insertedEntries)
            throws InterruptedException, IOException {
        lockAdminCollectionsCollection();
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        AdminCollEntry adminCollEntry;
        PkIndexEntry pkIndexEntry;
        if (adminIndexPkCollEntry != null) {
            adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
            adminCollEntry.setEntryCount(adminCollEntry.getEntryCount() + insertedEntries);
            pkIndexEntry = fs.updateFromCollection(adminCollEntry, adminIndexPkCollEntry);
        } else {
            adminCollEntry = new AdminCollEntry(dbName, collName);
            adminCollEntry.setEntryCount(adminCollEntry.getEntryCount() + insertedEntries);
            pkIndexEntry = fs.insertIntoCollection(adminCollEntry);
        }
        cache.putAdminCollectionEntry(adminCollEntry, pkIndexEntry);
        releaseAdminCollectionsCollection();
    }

    public static void saveDatabaseEntry(AdminDbEntry dbEntry)
            throws IOException, InterruptedException {
        lockAdminDatabaseCollection();
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbEntry.get_id());
        PkIndexEntry adminDbEntry;
        if (adminIndexPkDbEntry != null) {
            adminDbEntry = fs.updateFromCollection(dbEntry, adminIndexPkDbEntry);
        } else {
            adminDbEntry = fs.insertIntoCollection(dbEntry);
        }
        cache.putAdminDbEntry(dbEntry, adminDbEntry);
        releaseAdminDatabaseCollection();
    }

    public static void deleteDatabaseEntry(String dbName)
            throws IOException, InterruptedException {
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
        if (adminIndexPkDbEntry != null) {
            lockAdminDatabaseCollection();
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
            fs.deleteFromCollection(adminIndexPkDbEntry);
            cache.removeAdminDbEntry(dbName);
            releaseAdminDatabaseCollection();
        }
    }

    public static AdminDbEntry getDatabaseEntry(String dbName) {
        return cache.getAdminDbEntry(dbName);
    }

    public static void saveCollectionEntry(AdminCollEntry dbEntry)
            throws IOException, InterruptedException {
        lockAdminCollectionsCollection();
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(dbEntry.get_id());
        PkIndexEntry pkIndexEntry;
        if (adminIndexPkCollEntry != null) {
            pkIndexEntry = fs.updateFromCollection(dbEntry, adminIndexPkCollEntry);
        } else {
            pkIndexEntry = fs.insertIntoCollection(dbEntry);
        }
        cache.putAdminCollectionEntry(dbEntry, pkIndexEntry);
        final var split = dbEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var adminDbEntry = cache.getAdminDbEntry(split[0]);
        var adminDbPkIndexEntry = cache.getPkIndexAdminDbEntry(split[0]);
        final var colls = adminDbEntry.getCollections();
        colls.add(split[1]);
        adminDbEntry.setCollections(colls);
        adminDbPkIndexEntry = fs.updateFromCollection(adminDbEntry, adminDbPkIndexEntry);
        cache.putPkIndexAdminDbEntry(adminDbPkIndexEntry);
        releaseAdminCollectionsCollection();
    }

    public static void deleteCollectionEntry(String dbName, String collName)
            throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            lockAdminCollectionsCollection();
            fs.deleteFromCollection(adminIndexPkCollEntry);
            cache.removeAdminCollEntry(collIdentifier);
            final var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
            final var adminDbEntry = cache.getAdminDbEntry(dbName);
            final var otherCollections = adminDbEntry.getCollections();
            otherCollections.remove(collName);
            adminDbEntry.setCollections(otherCollections);
            final var updatedAdminIndexPkDbEntry = fs.updateFromCollection(adminDbEntry, adminIndexPkDbEntry);
            cache.putAdminDbEntry(adminDbEntry, updatedAdminIndexPkDbEntry);
            releaseAdminCollectionsCollection();
        }
    }

    public static AdminCollEntry getCollectionEntry(String dbName, String collName) {
        return cache.getAdminCollectionEntry(dbName, collName);
    }

    public static boolean hasIndexEntry(String dbName, String collName, String fieldName) {
        return cache.hasLoadedIndex(dbName, collName, fieldName);
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
            var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
            final var indexes = new HashSet<>(adminCollEntry.getIndexes()); // indexes is not a mutable list
            if (add) {
                indexes.add(fieldName);
            } else {
                indexes.remove(fieldName);
            }
            adminCollEntry.setIndexes(indexes);
            adminIndexPkCollEntry = fs.updateFromCollection(adminCollEntry, adminIndexPkCollEntry);
            cache.putAdminCollectionEntry(adminCollEntry, adminIndexPkCollEntry);
            cache.putPkIndexAdminCollEntry(adminIndexPkCollEntry);
            releaseAdminCollectionsCollection();
        }
    }
}
