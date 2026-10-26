package org.techhouse.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PageOccupancy;
import org.techhouse.fs.VacuumedPage;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.RangeBounds;

public class Cache implements UserCacheDelegate, AdminCacheDelegate {
    private static final String FIELD_LOOKUP_LABEL = "~lookup";
    private static final ClassValue<String> INDEX_TYPE_NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            final var name = type.getName();
            return name.substring(name.lastIndexOf('.') + 1);
        }
    };
    private static final Comparator<DbEntry> BY_ID = Comparator.comparing(DbEntry::get_id);
    private static final Comparator<DbEntry> BY_PAGE_THEN_ID = Comparator.comparingLong(DbEntry::getPage)
            .thenComparing(DbEntry::get_id);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final AbsentIndexTypes absentIndexTypes = new AbsentIndexTypes();
    private final org.techhouse.concurrency.ResourceLocking locks = IocContainer
            .get(org.techhouse.concurrency.ResourceLocking.class);

    public Cache() {
        final var admins = adminCache;
        final var users = userCache;
        fs.onVacuum(vacuumed -> reposition(vacuumed, admins, users));
    }

    @Override
    public UserCache userCache() {
        return userCache;
    }

    @Override
    public AdminCache adminCache() {
        return adminCache;
    }

    public static String getCollectionIdentifier(String dbName, String collName) {
        return CollectionIdentifiers.of(dbName, collName);
    }

    public static CollectionNames canonicalNames(String dbName, String collName) {
        return CollectionIdentifiers.namesOf(dbName, collName);
    }

    public static String getIndexIdentifier(String fieldName, Class<?> fieldType) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + INDEX_TYPE_NAMES.get(fieldType);
    }

    public static String getIndexIdentifier(String fieldName, String typeLabel) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + typeLabel;
    }

    public static String getFieldLookupIdentifier(String fieldName) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + FIELD_LOOKUP_LABEL;
    }

    public static boolean isFieldLookupIdentifier(String indexIdentifier) {
        return indexIdentifier.endsWith(Globals.COLL_IDENTIFIER_SEPARATOR + FIELD_LOOKUP_LABEL);
    }

    public static String getHashIndexIdentifier(String fieldName, String typeLabel) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + Globals.HASH_INDEX_KEY_PREFIX + typeLabel;
    }

    public LongFunction<PageOccupancy> pageOccupancy(String dbName, String collName) {
        return page -> {
            final var entry = adminCache.getAdminPageEntry(dbName, collName, page);
            return entry == null ? null : new PageOccupancy(entry.getPageSize(), entry.getEntryCount());
        };
    }

    private static void reposition(VacuumedPage vacuumed, AdminCache admins, UserCache users) {
        if (Globals.ADMIN_DB_NAME.equals(vacuumed.dbName()) || Globals.ADMIN_PAGES_DB_NAME.equals(vacuumed.dbName())) {
            admins.repositionAfterVacuum(vacuumed);
        } else {
            users.repositionAfterVacuum(vacuumed);
        }
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

    public Set<String> getIdsInRange(String dbName, String collName, RangeBounds bounds) throws IOException {
        return IndexLookupResolver.idsInRange(userCache, dbName, collName, bounds);
    }

    public OptionalLong countInRange(String dbName, String collName, RangeBounds bounds) throws IOException {
        return IndexLookupResolver.countInRange(userCache, dbName, collName, bounds);
    }

    public <T> OptionalLong countEquals(String dbName, String collName, String fieldName, T value) throws IOException {
        return IndexLookupResolver.countEquals(userCache, dbName, collName, fieldName, value);
    }

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids) throws IOException {
        return userCache.getEntriesByIds(dbName, collName, ids);
    }

    public void evictDatabase(String dbName) {
        CollectionIdentifiers.forgetDatabase(dbName);
        absentIndexTypes.forgetDatabase(dbName);
        userCache.evictDatabase(dbName);
        adminCache.removeCollectionSchemasForDatabase(dbName);
        adminCache.removeProceduresForDatabase(dbName);
        adminCache.removeSchedulesForDatabase(dbName);
        adminCache.removeTriggersForDatabase(dbName);
    }

    public void evictCollection(String dbName, String collName) {
        absentIndexTypes.forgetCollection(getCollectionIdentifier(dbName, collName));
        CollectionIdentifiers.forget(dbName, collName);
        userCache.evictCollection(dbName, collName);
        adminCache.removeCollectionSchema(dbName, collName);
        adminCache.removeTriggers(dbName, collName);
    }

    @Override
    public void evictFieldIndex(String dbName, String collName, String indexIdentifier) {
        absentIndexTypes.forget(getCollectionIdentifier(dbName, collName), indexIdentifier);
        userCache.evictFieldIndex(dbName, collName, indexIdentifier);
    }

    @Override
    public void evictFieldIndexAllTypes(String dbName, String collName, String fieldName) {
        absentIndexTypes.forgetField(getCollectionIdentifier(dbName, collName), fieldName);
        userCache.evictFieldIndexAllTypes(dbName, collName, fieldName);
    }

    public boolean isFieldIndexTypeAbsent(String dbName, String collName, String indexIdentifier) {
        return absentIndexTypes.isAbsent(getCollectionIdentifier(dbName, collName), indexIdentifier);
    }

    public void rememberFieldIndexTypeAbsent(String dbName, String collName, String indexIdentifier) {
        absentIndexTypes.remember(getCollectionIdentifier(dbName, collName), indexIdentifier);
    }

    public void forgetFieldIndexTypeAbsent(String dbName, String collName, String indexIdentifier) {
        absentIndexTypes.forget(getCollectionIdentifier(dbName, collName), indexIdentifier);
    }

    public Map<String, DbEntry> getWholeCollection(String dbName, String collName) {
        final var wholeCollection = userCache.getCachedCollection(dbName, collName);
        // Gate completeness on the synchronous PK index size, never on the admin page entry counts:
        // those lag behind committed writes and would accept an incomplete cached map.
        if (wholeCollection != null && !wholeCollection.isEmpty()
                && wholeCollection.size() == pkIndexSize(dbName, collName)) {
            return wholeCollection;
        }
        try {
            final var loaded = readWholeCollection(dbName, collName);
            return locks.holdsCollectionLock(dbName, collName)
                    ? userCache.admitWholeCollection(dbName, collName, loaded)
                    : loaded;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public int pkIndexSize(String dbName, String collName) {
        try {
            return userCache.getPkIndexAndLoadIfNecessary(dbName, collName).size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Stream<DbEntry> streamCollection(String dbName, String collName) throws IOException {
        if (!userCache.isCachingDisabled(dbName)) {
            final var cached = userCache.getCachedCollection(dbName, collName);
            if (cached != null && !cached.isEmpty() && cached.size() == pkIndexSize(dbName, collName)) {
                return cached.values().stream();
            }
        }
        return streamCollectionFromDisk(dbName, collName);
    }

    public Stream<DbEntry> streamCollectionInScanOrder(String dbName, String collName) throws IOException {
        if (!userCache.isCachingDisabled(dbName)) {
            final var cached = userCache.getCachedCollection(dbName, collName);
            if (cached != null && !cached.isEmpty() && cached.size() == pkIndexSize(dbName, collName)) {
                return cached.values().stream().sorted(BY_PAGE_THEN_ID);
            }
        }
        return streamCollectionFromDisk(dbName, collName, true);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String dbName, String collName) throws IOException {
        return streamCollectionFromDisk(dbName, collName, false);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String dbName, String collName, boolean inScanOrder)
            throws IOException {
        final var collPages = adminCache.getAdminPageEntries(dbName, collName);
        if (collPages == null || collPages.isEmpty() || collPages.size() < fs.pageFileCount(dbName, collName)) {
            return inScanOrder
                    ? fs.streamPages(dbName, collName).flatMap(page -> sortedById(page.values()))
                    : fs.streamEntries(dbName, collName);
        }
        final var sortedPages = collPages.stream().sorted(Comparator.comparingLong(AdminPageEntry::getPage)).toList();
        final var scan = new ScanAdmission(sortedPages, inScanOrder,
                page -> fs.readWholeCollectionPage(dbName, collName, page).values(), memoryManagement,
                scanCompletion(dbName, collName));
        return StreamSupport.stream(scan, false);
    }

    private ScanAdmission.Completion scanCompletion(String dbName, String collName) {
        if (userCache.isCachingDisabled(dbName) || !locks.holdsCollectionLock(dbName, collName)) {
            return null;
        }
        return collected -> {
            if (locks.holdsCollectionLock(dbName, collName) && collected.size() == pkIndexSize(dbName, collName)) {
                userCache.admitWholeCollection(dbName, collName, collected);
            }
        };
    }

    private static Stream<DbEntry> sortedById(Collection<DbEntry> pageDocuments) {
        return pageDocuments.stream().sorted(BY_ID);
    }

    private Map<String, DbEntry> readWholeCollection(String dbName, String collName) throws IOException {
        final var result = new HashMap<String, DbEntry>();
        try (var pagesStream = fs.streamPages(dbName, collName)) {
            pagesStream.forEach(result::putAll);
        }
        return result;
    }

    public boolean hasNoIndex(String dbName, String collName, String fieldName) {
        return !adminCache.hasIndex(dbName, collName, fieldName)
                || fs.indexMarkers().isMarked(dbName, collName, fieldName);
    }

}
