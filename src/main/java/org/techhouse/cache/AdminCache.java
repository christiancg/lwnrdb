package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Cache for admin (internal metadata) entries: databases, collections, users,
 * page metadata and the PK indexes of the admin collections. Admin entries are
 * always cached and never evicted, so this cache holds no memory-management
 * machinery. User document/index caching lives in {@link UserCache}; the two are
 * coordinated by the {@link Cache} facade.
 */
public class AdminCache {
    private static final Logger logger = Logger.logFor(AdminCache.class);
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, AdminDbEntry> databases = new ConcurrentHashMap<>();
    private final Map<String, AdminCollEntry> collections = new ConcurrentHashMap<>();
    private final Map<String, AdminUserEntry> users = new ConcurrentHashMap<>();
    private final Map<String, List<AdminPageEntry>> pages = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> databasesPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionsPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> usersPkIndex = new ConcurrentHashMap<>();
    private final Map<String, List<PkIndexEntry>> pagesPkIndexes = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionUsagePkIndex = new ConcurrentHashMap<>();

    public void loadAdminData() throws IOException {
        loadAdminPagesForCollection(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        loadAdminPagesForCollection(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        loadAdminPagesForCollection(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
        loadAdminPagesForCollection(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTION_USAGE_NAME);
        final var pkIndexCollectionUsageEntries = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTION_USAGE_NAME);
        final var pkIndexCollectionUsageEntriesMap = pkIndexCollectionUsageEntries.stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry));
        collectionUsagePkIndex.putAll(pkIndexCollectionUsageEntriesMap);
        final var pkIndexAdminDbEntries = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_DATABASES_COLLECTION_NAME);
        final var pkIndexAdminDbEntriesMap = pkIndexAdminDbEntries.stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry));
        databasesPkIndex.putAll(pkIndexAdminDbEntriesMap);
        final var pkIndexAdminCollEntries = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        final var pkIndexAdminCollEntriesMap = pkIndexAdminCollEntries.stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry));
        collectionsPkIndex.putAll(pkIndexAdminCollEntriesMap);
        final var pkIndexAdminUserEntries = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_USERS_COLLECTION_NAME);
        final var pkIndexAdminUserEntriesMap = pkIndexAdminUserEntries.stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry));
        usersPkIndex.putAll(pkIndexAdminUserEntriesMap);
        if (!pkIndexAdminDbEntriesMap.isEmpty()) {
            final var adminDatabasesColl = readWholeAdminCollection(Globals.ADMIN_DATABASES_COLLECTION_NAME);
            loadAdminEntries(adminDatabasesColl, Globals.ADMIN_DATABASES_COLLECTION_NAME, AdminDbEntry::fromJsonObject,
                    databases);
        }
        if (!pkIndexAdminCollEntries.isEmpty()) {
            final var adminCollectionsColl = readWholeAdminCollection(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            loadAdminEntries(adminCollectionsColl, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                    AdminCollEntry::fromJsonObject, collections);
        }
        if (!pkIndexAdminUserEntriesMap.isEmpty()) {
            final var adminUsersColl = readWholeAdminCollection(Globals.ADMIN_USERS_COLLECTION_NAME);
            loadAdminEntries(adminUsersColl, Globals.ADMIN_USERS_COLLECTION_NAME, AdminUserEntry::fromJsonObject,
                    users);
        }
        for (var collEntry : collections.values()) {
            final var parts = collEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
            if (parts.length < 2)
                continue;
            loadAdminPagesForCollection(parts[0], parts[1]);
        }
    }

    private <V> void loadAdminEntries(Map<String, DbEntry> source, String adminCollName,
            java.util.function.Function<JsonObject, V> mapper, Map<String, V> target) {
        for (var entry : source.entrySet()) {
            try {
                target.put(entry.getKey(), mapper.apply(entry.getValue().getData()));
            } catch (Exception e) {
                logger.warning("Skipping malformed admin entry '" + entry.getKey() + "' in " + adminCollName + ": "
                        + e.getMessage());
            }
        }
    }

    private void loadAdminPagesForCollection(String dbName, String collName) throws IOException {
        final var pagesCollName = String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collName);
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        final var pkIdx = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, pagesCollName);
        // The PK index loaded here belongs to pagesCollName (the file on disk that holds the
        // AdminPageEntries for `collName`). It must be keyed by (admin, pagesCollName) because
        // that's where insertAdminPages / updateTouchedPagesInFileSystem look it up.
        pagesPkIndexes.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, pagesCollName), new ArrayList<>(pkIdx));
        final var pageEntries = new ArrayList<AdminPageEntry>();
        try (var pagesStream = fs.streamPages(Globals.ADMIN_DB_NAME, pagesCollName)) {
            pagesStream.forEach(map -> map.values().stream()
                    .map(e -> AdminPageEntry.fromJsonObject(dbName, collName, e.getData())).forEach(pageEntries::add));
        }
        pages.put(collId, pageEntries);
        rebuildInMemoryPagesFromPkIndex(pagesCollName, pkIdx);
    }

    private void rebuildInMemoryPagesFromPkIndex(String collName, List<PkIndexEntry> pkIdx) {
        final var byPage = pkIdx.stream().collect(Collectors.groupingBy(PkIndexEntry::getPage));
        final var entries = new ArrayList<AdminPageEntry>();
        for (var e : byPage.entrySet()) {
            final var pageNum = e.getKey();
            final var pkList = e.getValue();
            final var entry = new AdminPageEntry(Globals.ADMIN_DB_NAME, collName, pageNum);
            entry.setEntryCount(pkList.size());
            entry.setPageSize(pkList.stream().mapToLong(PkIndexEntry::getLength).sum());
            entries.add(entry);
        }
        pages.put(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, collName), entries);
    }

    private Map<String, DbEntry> readWholeAdminCollection(String collName) throws IOException {
        final var result = new HashMap<String, DbEntry>();
        try (var pagesStream = fs.streamPages(Globals.ADMIN_DB_NAME, collName)) {
            pagesStream.forEach(result::putAll);
        }
        return result;
    }

    public PkIndexEntry getPkIndexAdminDbEntry(String dbName) {
        return databasesPkIndex.get(dbName);
    }

    public void putPkIndexAdminDbEntry(PkIndexEntry adminPkIndexAdminDbEntry) {
        databasesPkIndex.put(adminPkIndexAdminDbEntry.getValue(), adminPkIndexAdminDbEntry);
    }

    public AdminDbEntry getAdminDbEntry(String dbName) {
        return databases.get(dbName);
    }

    public Collection<AdminDbEntry> getAllAdminDbEntries() {
        return databases.values();
    }

    public List<String> getUserDatabaseNames() {
        return databases.keySet().stream().filter(name -> !Globals.ADMIN_DB_NAME.equals(name)).sorted().toList();
    }

    public List<String> getCollectionNamesForDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return collections.keySet().stream().filter(id -> id.startsWith(prefix))
                .map(id -> id.substring(prefix.length())).sorted().toList();
    }

    public PkIndexEntry getPkIndexAdminCollEntry(String collIdentifier) {
        return collectionsPkIndex.get(collIdentifier);
    }

    public void putPkIndexAdminCollEntry(PkIndexEntry adminPkIndexAdminCollEntry) {
        collectionsPkIndex.put(adminPkIndexAdminCollEntry.getValue(), adminPkIndexAdminCollEntry);
    }

    public AdminCollEntry getAdminCollectionEntry(String dbName, String collName) {
        return collections.get(Cache.getCollectionIdentifier(dbName, collName));
    }

    public List<AdminPageEntry> getAdminPageEntries(String dbName, String collName) {
        return pages.get(Cache.getCollectionIdentifier(dbName, collName));
    }

    public AdminPageEntry getAdminPageEntry(String dbName, String collName, long page) {
        final var entries = pages.get(Cache.getCollectionIdentifier(dbName, collName));
        if (entries == null)
            return null;
        return entries.stream().filter(p -> p.getPage() == page).findFirst().orElse(null);
    }

    public void putAdminPageEntries(String dbName, String collName, List<AdminPageEntry> adminPageEntries) {
        pages.put(Cache.getCollectionIdentifier(dbName, collName), adminPageEntries);
    }

    public void addAdminPageEntries(String dbName, String collName, AdminPageEntry adminPageEntry) {
        pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>())
                .add(adminPageEntry);
    }

    public void updatePageSizeInMemory(String dbName, String collName, long page, long bytesDelta) {
        final var pageEntries = pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ArrayList<>());
        final var existing = pageEntries.stream().filter(p -> p.getPage() == page).findFirst();
        if (existing.isPresent()) {
            existing.get().setPageSize(existing.get().getPageSize() + bytesDelta);
            existing.get().setEntryCount(existing.get().getEntryCount() + 1);
        } else {
            final var newEntry = new AdminPageEntry(dbName, collName, page);
            newEntry.setPageSize(bytesDelta);
            newEntry.setEntryCount(1);
            pageEntries.add(newEntry);
        }
    }

    public List<PkIndexEntry> getAdminPagePkIndexes(String dbName, String collName) {
        return pagesPkIndexes.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>());
    }

    public void removeAdminPageEntries(String dbName, String collName) {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        pages.remove(collId);
        pagesPkIndexes.remove(collId);
    }

    public void putAdminDbEntry(AdminDbEntry dbEntry, PkIndexEntry indexEntry) {
        databases.put(dbEntry.get_id(), dbEntry);
        databasesPkIndex.put(dbEntry.get_id(), indexEntry);
    }

    public void removeAdminDbEntry(String dbName) {
        databases.remove(dbName);
        databasesPkIndex.remove(dbName);
    }

    public void putAdminCollectionEntry(AdminCollEntry dbEntry, PkIndexEntry indexEntry) {
        final var collIdentifier = dbEntry.get_id();
        collections.put(collIdentifier, dbEntry);
        collectionsPkIndex.put(collIdentifier, indexEntry);
    }

    public void removeAdminCollEntry(String collIdentifier) {
        collections.remove(collIdentifier);
        collectionsPkIndex.remove(collIdentifier);
    }

    public long selectPageForInsert(String dbName, String collName, int entryByteSize) {
        return selectPageForInsert(dbName, collName, entryByteSize, Map.of());
    }

    public long selectPageForInsert(String dbName, String collName, int entryByteSize,
            Map<Long, Long> pendingPageBytes) {
        final var maxPageBytes = configuration.getMaxPageSize();
        final var pageEntries = pages.computeIfAbsent(Cache.getCollectionIdentifier(dbName, collName),
                _ -> new ArrayList<>());
        final var fit = pageEntries.stream().sorted(Comparator.comparingLong(AdminPageEntry::getPage)).filter(
                p -> p.getPageSize() + pendingPageBytes.getOrDefault(p.getPage(), 0L) + entryByteSize <= maxPageBytes)
                .findFirst();
        if (fit.isPresent()) {
            return fit.get().getPage();
        }
        final var maxKnownPage = pageEntries.stream().mapToLong(AdminPageEntry::getPage).max().orElse(-1L);
        final var maxPendingPage = pendingPageBytes.keySet().stream().mapToLong(Long::longValue).max().orElse(-1L);
        return Math.max(maxKnownPage, maxPendingPage) + 1L;
    }

    public boolean hasIndex(String dbName, String collName, String fieldName) {
        return getIndexesForCollection(dbName, collName).contains(fieldName);
    }

    public Set<String> getIndexesForCollection(String dbName, String collName) {
        final var collection = collections.get(Cache.getCollectionIdentifier(dbName, collName));
        // The collection may have been dropped while a background index event for it was still
        // queued. Treat a missing collection as "no indexes" so background maintenance becomes a
        // clean no-op (and hasIndex returns false) instead of throwing.
        if (collection == null) {
            return Set.of();
        }
        return collection.getIndexes();
    }

    public AdminUserEntry getAdminUserEntry(String username) {
        return users.get(username);
    }

    public Collection<AdminUserEntry> getAllAdminUserEntries() {
        return users.values();
    }

    public void putAdminUserEntry(AdminUserEntry userEntry, PkIndexEntry indexEntry) {
        users.put(userEntry.get_id(), userEntry);
        usersPkIndex.put(userEntry.get_id(), indexEntry);
    }

    public void removeAdminUserEntry(String username) {
        users.remove(username);
        usersPkIndex.remove(username);
    }

    public PkIndexEntry getPkIndexAdminUserEntry(String username) {
        return usersPkIndex.get(username);
    }

    public PkIndexEntry getPkIndexCollectionUsage(String usageId) {
        return collectionUsagePkIndex.get(usageId);
    }

    public void putPkIndexCollectionUsage(PkIndexEntry indexEntry) {
        collectionUsagePkIndex.put(indexEntry.getValue(), indexEntry);
    }

    public void removePkIndexCollectionUsage(String usageId) {
        collectionUsagePkIndex.remove(usageId);
    }

    public Map<String, PkIndexEntry> getCollectionUsagePkIndexes() {
        return collectionUsagePkIndex;
    }
}
