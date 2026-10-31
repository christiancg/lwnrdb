package org.techhouse.cache;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int MEMOIZED_INDEX_FIELDS = 4096;
    private static final Map<String, Map<Object, String>> INDEX_IDENTIFIERS = new ConcurrentHashMap<>();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ScanOrderMemo scanOrder = new ScanOrderMemo();
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

    public static String memoizedIndexIdentifier(String fieldName, Class<?> fieldType) {
        final var identifiers = memoizedIdentifiersOf(fieldName);
        final var known = identifiers.get(fieldType);
        if (known != null) {
            return known;
        }
        final var identifier = getIndexIdentifier(fieldName, fieldType);
        identifiers.putIfAbsent(fieldType, identifier);
        return identifier;
    }

    public static String memoizedHashIndexIdentifier(String fieldName, IndexKind kind) {
        final var identifiers = memoizedIdentifiersOf(fieldName);
        final var known = identifiers.get(kind);
        if (known != null) {
            return known;
        }
        final var identifier = getHashIndexIdentifier(fieldName, kind.label());
        identifiers.putIfAbsent(kind, identifier);
        return identifier;
    }

    private static Map<Object, String> memoizedIdentifiersOf(String fieldName) {
        final var identifiers = INDEX_IDENTIFIERS.get(fieldName);
        if (identifiers != null) {
            return identifiers;
        }
        if (INDEX_IDENTIFIERS.size() >= MEMOIZED_INDEX_FIELDS) {
            INDEX_IDENTIFIERS.clear();
        }
        return INDEX_IDENTIFIERS.computeIfAbsent(fieldName, _ -> new ConcurrentHashMap<>());
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

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids, PkRows pinned)
            throws IOException {
        return userCache.getEntriesByIds(dbName, collName, ids, pinned);
    }

    public Set<String> getIdsEqualToAny(String dbName, String collName, String fieldName, List<Object> lookupValues)
            throws IOException {
        return IndexLookupResolver.idsEqualToAny(userCache, dbName, collName, fieldName, lookupValues);
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
        INDEX_IDENTIFIERS.remove(fieldName);
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

    public WriterPkRows pkRowsForWriter(String dbName, String collName, Collection<String> ids) throws IOException {
        return userCache.pkTier().forWriter(dbName, collName, ids);
    }

    public boolean isFieldIndexTypeRefused(String dbName, String collName, String indexIdentifier) {
        if (UserCache.isReservedDatabase(dbName)) {
            return false;
        }
        if (userCache.isCachingDisabled(dbName)) {
            return true;
        }
        final var bytes = userCache.sizes().refusedFieldIndexBytes(getCollectionIdentifier(dbName, collName),
                indexIdentifier);
        return bytes != null && !userCache.admissionFeasible(AccessKind.FIELD_INDEX, bytes);
    }

    public Set<String> fieldsKeepingPriorValues(String dbName, String collName) {
        if (UserCache.isReservedDatabase(dbName)) {
            return Set.of();
        }
        if (userCache.isCachingDisabled(dbName)) {
            return adminCache.getIndexesForCollection(dbName, collName);
        }
        return userCache.sizes().fieldsWithRefusedTypes(getCollectionIdentifier(dbName, collName));
    }

    public Map<String, DbEntry> getWholeCollection(String dbName, String collName) {
        final var wholeCollection = completeCachedCollection(dbName, collName);
        if (wholeCollection != null) {
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
            return userCache.pkIndexSize(dbName, collName);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, DbEntry> completeCachedCollection(String dbName, String collName) {
        final var cached = userCache.getCachedCollection(dbName, collName);
        return cached != null && !cached.isEmpty() && cached.size() == pkCountForCoverage(dbName, collName)
                ? cached
                : null;
    }

    private Map<String, DbEntry> usableCachedCollection(String dbName, String collName) {
        return userCache.isCachingDisabled(dbName) ? null : completeCachedCollection(dbName, collName);
    }

    public boolean holdsWholeCollection(String dbName, String collName) {
        return usableCachedCollection(dbName, collName) != null;
    }

    public Stream<DbEntry> streamCollection(String dbName, String collName) throws IOException {
        final var cached = usableCachedCollection(dbName, collName);
        return cached != null ? cached.values().stream() : streamCollectionFromDisk(dbName, collName);
    }

    public Stream<DbEntry> streamCollectionSkipping(String dbName, String collName, long skip) throws IOException {
        if (skip <= 0) {
            return streamCollection(dbName, collName);
        }
        final var cached = usableCachedCollection(dbName, collName);
        if (cached != null) {
            return cached.values().stream().skip(skip);
        }
        final var sortedPages = completeSortedPages(dbName, collName);
        if (sortedPages == null || !locks.holdsCollectionLock(dbName, collName)) {
            return streamCollectionFromDisk(dbName, collName).skip(skip);
        }
        final var plan = PageSkip.plan(sortedPages, userCache.getPkIndexAndLoadIfNecessary(dbName, collName), skip);
        final var scan = new ScanAdmission(plan.pages(), false, pageReader(dbName, collName), memoryManagement, null);
        return StreamSupport.stream(scan, false).skip(plan.remainder());
    }

    public Stream<DbEntry> streamCollectionInScanOrder(String dbName, String collName) throws IOException {
        final var cached = usableCachedCollection(dbName, collName);
        return cached != null
                ? Arrays.stream(scanOrder.inScanOrder(getCollectionIdentifier(dbName, collName), cached))
                : streamCollectionFromDisk(dbName, collName, true);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String dbName, String collName) throws IOException {
        return streamCollectionFromDisk(dbName, collName, false);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String dbName, String collName, boolean inScanOrder)
            throws IOException {
        final var sortedPages = completeSortedPages(dbName, collName);
        if (sortedPages == null) {
            return inScanOrder
                    ? fs.streamPages(dbName, collName).flatMap(page -> sortedById(page.values()))
                    : fs.streamEntries(dbName, collName);
        }
        final var scan = new ScanAdmission(sortedPages, inScanOrder, pageReader(dbName, collName), memoryManagement,
                scanCompletion(dbName, collName));
        return StreamSupport.stream(scan, false);
    }

    private List<AdminPageEntry> completeSortedPages(String dbName, String collName) throws IOException {
        final var collPages = adminCache.getAdminPageEntries(dbName, collName);
        if (collPages == null || collPages.isEmpty() || collPages.size() < fs.pageFileCount(dbName, collName)) {
            return null;
        }
        return collPages.stream().sorted(Comparator.comparingLong(AdminPageEntry::getPage)).toList();
    }

    private ScanAdmission.PageReader pageReader(String dbName, String collName) {
        return page -> fs.readWholeCollectionPage(dbName, collName, page).values();
    }

    private ScanAdmission.Completion scanCompletion(String dbName, String collName) {
        if (userCache.isCachingDisabled(dbName) || !locks.holdsCollectionLock(dbName, collName)) {
            return null;
        }
        return (collected, measuredBytes) -> {
            if (locks.holdsCollectionLock(dbName, collName) && collected.size() == pkIndexSize(dbName, collName)) {
                userCache.admitScannedCollection(dbName, collName, collected, measuredBytes);
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

    public boolean mayAdmitFieldIndexLookup(String dbName, String collName, String fieldName, int ids) {
        return locks.holdsCollectionLock(dbName, collName)
                && userCache.wouldAdmit(new AdmissionRequest(AccessKind.FIELD_INDEX, dbName, collName, fieldName,
                        CacheSizeEstimator.estimateFieldIndexLookupSize(ids)));
    }

    public boolean hasNoIndex(String dbName, String collName, String fieldName) {
        return !adminCache.hasIndex(dbName, collName, fieldName)
                || fs.indexMarkers().isMarked(dbName, collName, fieldName);
    }

}
