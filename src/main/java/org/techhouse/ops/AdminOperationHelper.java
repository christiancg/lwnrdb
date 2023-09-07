package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;

public class AdminOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    public static void updateEntryCount(String dbName, String collName)
            throws ExecutionException, InterruptedException {
        final var entryCount = cache.getEntryCountForCollection(dbName, collName);
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            final var adminCollEntry = cache.getAdminCollectionEntry(dbName, collName);
            adminCollEntry.setEntryCount(entryCount);
            fs.updateFromCollection(adminCollEntry, adminIndexPkCollEntry);
            cache.putAdminCollectionEntry(adminCollEntry, adminIndexPkCollEntry);
        } else {
            final var adminCollEntry = new AdminCollEntry(dbName, collName);
            adminCollEntry.setEntryCount(entryCount);
            final var pkIndexEntry = fs.insertIntoCollection(adminCollEntry);
            cache.putAdminCollectionEntry(adminCollEntry, pkIndexEntry);
        }
    }

    public static void saveDatabaseEntry(AdminDbEntry dbEntry)
            throws ExecutionException, InterruptedException {
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbEntry.get_id());
        if (adminIndexPkDbEntry != null) {
            fs.updateFromCollection(dbEntry, adminIndexPkDbEntry);
            cache.putAdminDbEntry(dbEntry, adminIndexPkDbEntry);
        } else {
            final var pkIndexEntry = fs.insertIntoCollection(dbEntry);
            cache.putAdminDbEntry(dbEntry, pkIndexEntry);
        }
    }

    public static void deleteDatabaseEntry(String dbName)
            throws ExecutionException, InterruptedException {
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
        if (adminIndexPkDbEntry != null) {
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
        }
    }

    public static AdminDbEntry getDatabaseEntry(String dbName) {
        return cache.getAdminDbEntry(dbName);
    }

    public static void saveCollectionEntry(AdminCollEntry dbEntry)
            throws ExecutionException, InterruptedException {
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(dbEntry.get_id());
        if (adminIndexPkCollEntry != null) {
            fs.updateFromCollection(dbEntry, adminIndexPkCollEntry);
            cache.putAdminCollectionEntry(dbEntry, adminIndexPkCollEntry);
        } else {
            final var pkIndexEntry = fs.insertIntoCollection(dbEntry);
            cache.putAdminCollectionEntry(dbEntry, pkIndexEntry);
        }
        final var split = dbEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var adminDbEntry = cache.getAdminDbEntry(split[0]);
        var adminDbPkIndexEntry = cache.getPkIndexAdminDbEntry(split[0]);
        final var colls = adminDbEntry.getCollections();
        colls.add(split[1]);
        adminDbEntry.setCollections(colls);
        adminDbPkIndexEntry = fs.updateFromCollection(adminDbEntry, adminDbPkIndexEntry);
        cache.putPkIndexAdminDbEntry(adminDbPkIndexEntry);
    }

    public static void deleteCollectionEntry(String dbName, String collName)
            throws ExecutionException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
            fs.deleteFromCollection(adminIndexPkCollEntry);
            cache.removeAdminCollEntry(collIdentifier);
            final var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
            final var adminDbEntry = cache.getAdminDbEntry(dbName);
            final var otherCollections = adminDbEntry.getCollections();
            otherCollections.remove(collName);
            adminDbEntry.setCollections(otherCollections);
            final var updatedAdminIndexPkDbEntry = fs.updateFromCollection(adminDbEntry, adminIndexPkDbEntry);
            cache.putAdminDbEntry(adminDbEntry, updatedAdminIndexPkDbEntry);
        }
    }

    public static AdminCollEntry getCollectionEntry(String dbName, String collName) {
        return cache.getAdminCollectionEntry(dbName, collName);
    }

    public static boolean hasIndexEntry(String dbName, String collName, String fieldName) {
        return cache.hasIndex(dbName, collName, fieldName);
    }

    public static void saveNewIndex(String dbName, String collName, String fieldName)
            throws ExecutionException, InterruptedException {
        internalUpdateAdminColl(dbName, collName, fieldName, true);
    }

    public static void deleteIndex(String dbName, String collName, String fieldName)
            throws ExecutionException, InterruptedException {
        internalUpdateAdminColl(dbName, collName, fieldName, false);
    }

    private static void internalUpdateAdminColl(String dbName, String collName, String fieldName, boolean add)
            throws ExecutionException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        if (adminIndexPkCollEntry != null) {
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
        }
    }
}
