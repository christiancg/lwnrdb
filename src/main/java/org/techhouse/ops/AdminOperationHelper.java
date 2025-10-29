package org.techhouse.ops;

import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class AdminOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Logger log = Logger.logFor(AdminOperationHelper.class);

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

    private static void lockAdminPageCollection(String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_DB_NAME, Globals.ADMIN_PAGES_PER_COLLECTION_NAME.replace("{}", collName));
    }

    private static void releaseAdminPageCollection(String collName) {
        locks.release(Globals.ADMIN_DB_NAME, Globals.ADMIN_PAGES_PER_COLLECTION_NAME.replace("{}", collName));
    }

    public static void bulkUpdateEntryCount(String dbName, String collName, EventType type, List<DbEntry> inserted)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, inserted);
    }

    public static void updateEntryCount(String dbName, String collName, EventType type, DbEntry dbEntry)
            throws IOException, InterruptedException {
        baseUpdateEntryCount(dbName, collName, type, List.of(dbEntry));
    }

    private static void baseUpdateEntryCount(final String dbName, final String collName, final EventType type, final List<DbEntry> insertedOrDeleted)
            throws InterruptedException, IOException {
        lockAdminPageCollection(collName);
        final var collIdentifier = Cache.getCollectionIdentifier(dbName, collName);
        final var adminIndexPkCollEntry = cache.getPkIndexAdminCollEntry(collIdentifier);
        List<AdminPageEntry> adminPageEntries;
        PkIndexEntry pkIndexEntry;
        final var pagesPerCollectionName = Globals.ADMIN_PAGES_PER_COLLECTION_NAME.replace("{}", collName);
        final var grouped = insertedOrDeleted.stream().collect(Collectors.groupingBy(DbEntry::getPage));
        final var insertedPages = grouped.entrySet().stream()
                .map(longListEntry -> {
                    final var groupedEntry = new AdminPageEntry(collName);
                    groupedEntry.setPage(longListEntry.getKey());
                    groupedEntry.setEntryCount(longListEntry.getValue().size());
                    // TODO: replace size calculation using proper size
                    final var groupedSize = longListEntry.getValue().stream().map(dbEntry -> dbEntry.toFileEntry().length()).reduce(0, Integer::sum);
                    groupedEntry.setPageSize(groupedSize);
                    return groupedEntry;
                }).toList();
        if (adminIndexPkCollEntry != null) {
            adminPageEntries = cache.getAdminPageEntries(dbName, collName);
            final var touchedPages = adminPageEntries.stream()
                    .filter(page -> insertedPages.stream().anyMatch(dbEntry -> dbEntry.getPage() == page.getPage()))
                    .peek(page -> {
                        final var insertedPage = insertedPages.stream().filter(dbEntry -> dbEntry.getPage() == page.getPage()).findFirst();
                        if (insertedPage.isPresent()) {
                            switch (type) {
                                case UPDATED:
                                    // TODO: implement this case
                                    break;
                                case CREATED:
                                    page.setEntryCount(page.getEntryCount() + insertedPage.get().getEntryCount());
                                    page.setPageSize(page.getPageSize() + insertedPage.get().getPageSize());
                                    break;
                                case DELETED:
                                    page.setEntryCount(page.getEntryCount() - insertedPage.get().getEntryCount());
                                    page.setPageSize(page.getPageSize() - insertedPage.get().getPageSize());
                                    break;
                            }
                        }
                    }).toList();
            final var newPages = insertedPages.stream()
                    .filter(page -> adminPageEntries.stream().noneMatch(p -> p.getPage() == page.getPage()))
                    .map(page -> {
                        final var newPkIndexEntry = new AdminPageEntry(collName);
                        newPkIndexEntry.setPage(page.getPage());
                        newPkIndexEntry.setEntryCount(page.getEntryCount());
                        newPkIndexEntry.setPageSize(page.getPageSize());
                        return newPkIndexEntry;
                    })
                    .toList();
            if (!newPages.isEmpty()) {
                fs.bulkInsertIntoCollection(Globals.ADMIN_DB_NAME, pagesPerCollectionName, newPages);
            }
            // Update touched pages using bulkUpdateFromCollection method
            if (!touchedPages.isEmpty()) {
                updateTouchedPagesInFileSystem(dbName, collName, pagesPerCollectionName, touchedPages, adminPageEntries);
            }
        } else {
            final var adminCollEntry = new AdminCollEntry(dbName, collName);
            pkIndexEntry = fs.insertIntoCollection(adminCollEntry);
            cache.putAdminCollectionEntry(adminCollEntry, pkIndexEntry);
            fs.bulkInsertIntoCollection(Globals.ADMIN_DB_NAME, pagesPerCollectionName, insertedPages);
        }
        releaseAdminPageCollection(collName);
    }

    /**
     * Updates touched admin page entries in the filesystem using bulkUpdateFromCollection
     */
    private static void updateTouchedPagesInFileSystem(String dbName, String collName, String pagesPerCollectionName,
                                                       List<AdminPageEntry> touchedPages, List<AdminPageEntry> adminPageEntries)
            throws IOException {
        // Update the cache with the modified entries
        final var updatedPageEntries = new ArrayList<>(adminPageEntries);
        // Replace the touched pages in the list with their updated versions
        for (var touchedPage : touchedPages) {
            updatedPageEntries.removeIf(p -> p.getPage() == touchedPage.getPage());
            updatedPageEntries.add(touchedPage);
        }
        cache.putAdminPageEntries(dbName, collName, updatedPageEntries);

        // Convert touched pages to IndexedDbEntry objects for bulkUpdateFromCollection
        final var indexedEntriesToUpdate = new ArrayList<IndexedDbEntry>();

        for (var touchedPage : touchedPages) {
            try {
                // Create IndexedDbEntry from the touched page
                final var indexedEntry = new IndexedDbEntry();
                indexedEntry.set_id(touchedPage.get_id());
                indexedEntry.setDatabaseName(Globals.ADMIN_DB_NAME);
                indexedEntry.setCollectionName(pagesPerCollectionName);
                indexedEntry.setData(touchedPage.getData());

                // Read the PK index file to find the index entry for this page
                final var existingPkIndexEntry = findPkIndexEntryById(Globals.ADMIN_DB_NAME, pagesPerCollectionName, touchedPage.get_id());
                if (existingPkIndexEntry != null) {
                    indexedEntry.setIndex(existingPkIndexEntry);
                    indexedEntriesToUpdate.add(indexedEntry);
                }
            } catch (Exception e) {
                // If we can't get the PK index for this entry, skip it
                log.error("Warning: Could not retrieve PK index for admin page entry: " + touchedPage.get_id(), e);
            }
        }

        // Use bulkUpdateFromCollection to persist the updates to filesystem
        if (!indexedEntriesToUpdate.isEmpty()) {
            fs.bulkUpdateFromCollection(Globals.ADMIN_DB_NAME, pagesPerCollectionName, indexedEntriesToUpdate);
        }
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

    /**
     * Helper method to find a PK index entry by ID from the index file
     */
    private static PkIndexEntry findPkIndexEntryById(String dbName, String collName, String id) throws IOException {
        // Construct the path to the PK index file
        final var indexFileName = collName + "_pk_string.idx";
        final var dbPath = System.getProperty("user.dir") + "/db"; // Should use config
        final var indexFilePath = dbPath + "/" + dbName + "/" + collName + "/" + indexFileName;
        final var indexFile = new java.io.File(indexFilePath);

        if (!indexFile.exists()) {
            return null;
        }

        try (final var reader = new java.io.BufferedReader(new java.io.FileReader(indexFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Parse the index entry line to find matching ID
                final var parts = line.split(Globals.INDEX_ENTRY_SEPARATOR_REGEX);
                if (parts.length >= 4 && parts[0].equals(id)) {
                    // Found the matching entry, create PkIndexEntry
                    final var position = Long.parseLong(parts[1]);
                    final var length = Integer.parseInt(parts[2]);
                    final var page = Long.parseLong(parts[3]);
                    return new PkIndexEntry(dbName, collName, id, position, length, page);
                }
            }
        }

        return null; // Not found
    }
}
