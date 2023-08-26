package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

import java.util.concurrent.ExecutionException;

public class AdminOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);

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

    public static void deleteDatabaseEntry(String dbName) {
        var adminIndexPkDbEntry = cache.getPkIndexAdminDbEntry(dbName);
        if (adminIndexPkDbEntry != null) {
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
        final var split = dbEntry.get_id().split("\\|");
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
}
