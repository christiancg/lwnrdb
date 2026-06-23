package org.techhouse.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;

/**
 * Facade over the two separated cache stores: {@link AdminCache} for admin
 * (internal metadata) entries and {@link UserCache} for user document/index
 * entries. Admin methods delegate to {@link AdminCache} and user methods to
 * {@link UserCache}; the cross-cutting read/stream methods are implemented here
 * because they combine admin page metadata with the user document cache. The
 * facade stays a concrete IoC singleton so the existing
 * {@code IocContainer.get(Cache.class)} call sites are unchanged.
 */
public class Cache {
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    // Lazily initialized because the cache <-> MemoryManagement relationship is a
    // construction-time cycle: MemoryManagement holds the cache eagerly, so we cannot hold
    // MemoryManagement eagerly here without recursing through the IoC container during init.
    private MemoryManagement memoryManagement;

    private MemoryManagement memoryManagement() {
        var mm = memoryManagement;
        if (mm == null) {
            mm = IocContainer.get(MemoryManagement.class);
            memoryManagement = mm;
        }
        return mm;
    }

    public static String getCollectionIdentifier(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    public static String getIndexIdentifier(String fieldName, Class<?> fieldType) {
        final var parts = fieldType.getName().split("\\.");
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + parts[parts.length - 1];
    }

    public static String getIndexIdentifier(String fieldName, String typeLabel) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + typeLabel;
    }

    public void loadAdminData() throws IOException {
        adminCache.loadAdminData();
    }

    // ── User document / index cache (delegated to UserCache) ──────────────────

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        return userCache.getPkIndexAndLoadIfNecessary(dbName, collName);
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, indexType);
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind);
    }

    public <T> Set<String> getIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator,
            T value) throws IOException {
        return userCache.getIdsFromIndex(dbName, collName, fieldName, operator, value);
    }

    public void recordFieldIndexAccess(String dbName, String collName, String fieldName) {
        userCache.recordFieldIndexAccess(dbName, collName, fieldName);
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        userCache.addEntryToCache(dbName, collName, entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        userCache.addEntriesToCache(dbName, collName, entries);
    }

    public DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
        return userCache.getById(dbName, collName, idxEntry);
    }

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids) throws IOException {
        return userCache.getEntriesByIds(dbName, collName, ids);
    }

    public void evictEntry(String dbName, String collName, String pk) {
        userCache.evictEntry(dbName, collName, pk);
    }

    public void evictDatabase(String dbName) {
        userCache.evictDatabase(dbName);
    }

    public void evictCollection(String dbName, String collName) {
        userCache.evictCollection(dbName, collName);
    }

    public List<CacheableResource> listCacheableResources() {
        return userCache.listCacheableResources();
    }

    public boolean hasLoadedIndex(String dbName, String collName, String fieldName) {
        return userCache.hasLoadedIndex(dbName, collName, fieldName);
    }

    // ── Cross-cutting reads (combine user documents with admin page metadata) ──

    public Map<String, DbEntry> getWholeCollection(String dbName, String collName) {
        final var wholeCollection = userCache.getCachedCollection(dbName, collName);
        final var collPages = adminCache.getAdminPageEntries(dbName, collName);
        if (collPages == null) {
            if (wholeCollection != null && !wholeCollection.isEmpty()) {
                return wholeCollection;
            }
        } else if (wholeCollection != null && !wholeCollection.isEmpty()) {
            final var entryCount = collPages.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
            if (wholeCollection.size() >= entryCount) {
                return wholeCollection;
            }
        }
        try {
            final var loaded = readWholeCollection(dbName, collName);
            return userCache.admitWholeCollection(dbName, collName, loaded);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Stream<DbEntry> streamCollection(String dbName, String collName) throws IOException {
        if (!userCache.isCachingDisabled(dbName)) {
            final var cached = userCache.getCachedCollection(dbName, collName);
            if (cached != null && !cached.isEmpty()) {
                final var collPages = adminCache.getAdminPageEntries(dbName, collName);
                if (collPages == null) {
                    return cached.values().stream();
                }
                final var entryCount = collPages.stream().mapToInt(AdminPageEntry::getEntryCount).sum();
                if (cached.size() >= entryCount) {
                    return cached.values().stream();
                }
            }
        }
        return streamCollectionFromDisk(dbName, collName);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String dbName, String collName) throws IOException {
        final var collPages = adminCache.getAdminPageEntries(dbName, collName);
        if (collPages == null || collPages.isEmpty()) {
            // No page metadata to drive memory-aware reading; fall back to the lazy
            // file-based page stream (still only one page resident at a time).
            return fs.streamEntries(dbName, collName);
        }
        final var maxPageBytes = configuration.getMaxPageSize();
        final var sortedPages = collPages.stream().sorted(Comparator.comparingLong(AdminPageEntry::getPage)).toList();
        // flatMap pulls one page at a time: the headroom check + page read happen lazily
        // as the previous page's entries are exhausted downstream, so each page map is
        // released for GC before the next is read.
        return sortedPages.stream().flatMap(pageEntry -> {
            final var estimate = pageEntry.getPageSize() > 0 ? pageEntry.getPageSize() : maxPageBytes;
            memoryManagement().ensureHeadroomForBytes(estimate);
            try {
                return fs.readWholeCollectionPage(dbName, collName, pageEntry.getPage()).values().stream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Stream<JsonObject> initializeStreamIfNecessary(Stream<JsonObject> resultStream, String dbName,
            String collName) throws IOException {
        if (resultStream != null) {
            return resultStream;
        }
        return streamCollection(dbName, collName).map(DbEntry::getData);
    }

    private Map<String, DbEntry> readWholeCollection(String dbName, String collName) throws IOException {
        final var result = new HashMap<String, DbEntry>();
        try (var pagesStream = fs.streamPages(dbName, collName)) {
            pagesStream.forEach(result::putAll);
        }
        return result;
    }

    // ── Admin metadata cache (delegated to AdminCache) ────────────────────────

    public long selectPageForInsert(String dbName, String collName, int entryByteSize) {
        return adminCache.selectPageForInsert(dbName, collName, entryByteSize);
    }

    public long selectPageForInsert(String dbName, String collName, int entryByteSize,
            Map<Long, Long> pendingPageBytes) {
        return adminCache.selectPageForInsert(dbName, collName, entryByteSize, pendingPageBytes);
    }

    public PkIndexEntry getPkIndexAdminDbEntry(String dbName) {
        return adminCache.getPkIndexAdminDbEntry(dbName);
    }

    public void putPkIndexAdminDbEntry(PkIndexEntry adminPkIndexAdminDbEntry) {
        adminCache.putPkIndexAdminDbEntry(adminPkIndexAdminDbEntry);
    }

    public AdminDbEntry getAdminDbEntry(String dbName) {
        return adminCache.getAdminDbEntry(dbName);
    }

    public Collection<AdminDbEntry> getAllAdminDbEntries() {
        return adminCache.getAllAdminDbEntries();
    }

    public List<String> getUserDatabaseNames() {
        return adminCache.getUserDatabaseNames();
    }

    public List<String> getCollectionNamesForDatabase(String dbName) {
        return adminCache.getCollectionNamesForDatabase(dbName);
    }

    public PkIndexEntry getPkIndexAdminCollEntry(String collIdentifier) {
        return adminCache.getPkIndexAdminCollEntry(collIdentifier);
    }

    public void putPkIndexAdminCollEntry(PkIndexEntry adminPkIndexAdminCollEntry) {
        adminCache.putPkIndexAdminCollEntry(adminPkIndexAdminCollEntry);
    }

    public AdminCollEntry getAdminCollectionEntry(String dbName, String collName) {
        return adminCache.getAdminCollectionEntry(dbName, collName);
    }

    public List<AdminPageEntry> getAdminPageEntries(String dbName, String collName) {
        return adminCache.getAdminPageEntries(dbName, collName);
    }

    public AdminPageEntry getAdminPageEntry(String dbName, String collName, long page) {
        return adminCache.getAdminPageEntry(dbName, collName, page);
    }

    public void putAdminPageEntries(String dbName, String collName, List<AdminPageEntry> adminPageEntries) {
        adminCache.putAdminPageEntries(dbName, collName, adminPageEntries);
    }

    public void addAdminPageEntries(String dbName, String collName, AdminPageEntry adminPageEntry) {
        adminCache.addAdminPageEntries(dbName, collName, adminPageEntry);
    }

    public void updatePageSizeInMemory(String dbName, String collName, long page, long bytesDelta) {
        adminCache.updatePageSizeInMemory(dbName, collName, page, bytesDelta);
    }

    public List<PkIndexEntry> getAdminPagePkIndexes(String dbName, String collName) {
        return adminCache.getAdminPagePkIndexes(dbName, collName);
    }

    public void removeAdminPageEntries(String dbName, String collName) {
        adminCache.removeAdminPageEntries(dbName, collName);
    }

    public void putAdminDbEntry(AdminDbEntry dbEntry, PkIndexEntry indexEntry) {
        adminCache.putAdminDbEntry(dbEntry, indexEntry);
    }

    public void removeAdminDbEntry(String dbName) {
        adminCache.removeAdminDbEntry(dbName);
    }

    public void putAdminCollectionEntry(AdminCollEntry dbEntry, PkIndexEntry indexEntry) {
        adminCache.putAdminCollectionEntry(dbEntry, indexEntry);
    }

    public void removeAdminCollEntry(String collIdentifier) {
        adminCache.removeAdminCollEntry(collIdentifier);
    }

    public boolean hasIndex(String dbName, String collName, String fieldName) {
        return adminCache.hasIndex(dbName, collName, fieldName);
    }

    public Set<String> getIndexesForCollection(String dbName, String collName) {
        return adminCache.getIndexesForCollection(dbName, collName);
    }

    public AdminUserEntry getAdminUserEntry(String username) {
        return adminCache.getAdminUserEntry(username);
    }

    public Collection<AdminUserEntry> getAllAdminUserEntries() {
        return adminCache.getAllAdminUserEntries();
    }

    public void putAdminUserEntry(AdminUserEntry userEntry, PkIndexEntry indexEntry) {
        adminCache.putAdminUserEntry(userEntry, indexEntry);
    }

    public void removeAdminUserEntry(String username) {
        adminCache.removeAdminUserEntry(username);
    }

    public PkIndexEntry getPkIndexAdminUserEntry(String username) {
        return adminCache.getPkIndexAdminUserEntry(username);
    }

    public PkIndexEntry getPkIndexCollectionUsage(String usageId) {
        return adminCache.getPkIndexCollectionUsage(usageId);
    }

    public void putPkIndexCollectionUsage(PkIndexEntry indexEntry) {
        adminCache.putPkIndexCollectionUsage(indexEntry);
    }

    public void removePkIndexCollectionUsage(String usageId) {
        adminCache.removePkIndexCollectionUsage(usageId);
    }

    public Map<String, PkIndexEntry> getCollectionUsagePkIndexes() {
        return adminCache.getCollectionUsagePkIndexes();
    }
}
