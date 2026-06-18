package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.SearchUtils;

public class Cache {
    private static final Logger logger = Logger.logFor(Cache.class);
    private static final long ESTIMATED_PK_ENTRY_BYTES = 96L;
    private static final long ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES = 64L;
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();
    private final Map<String, AdminDbEntry> databases = new ConcurrentHashMap<>();
    private final Map<String, AdminCollEntry> collections = new ConcurrentHashMap<>();
    private final Map<String, AdminUserEntry> users = new ConcurrentHashMap<>();
    private final Map<String, List<AdminPageEntry>> pages = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> databasesPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionsPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> usersPkIndex = new ConcurrentHashMap<>();
    private final Map<String, List<PkIndexEntry>> pagesPkIndexes = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionUsagePkIndex = new ConcurrentHashMap<>();
    // Lazily initialized because Cache <-> MemoryManagement is a construction-time cycle:
    // MemoryManagement holds Cache eagerly, so we cannot hold MemoryManagement eagerly here
    // without recursing through the IoC container during static init.
    private MemoryManagement memoryManagement;
    private BackgroundTaskManager taskManager;

    private MemoryManagement memoryManagement() {
        var mm = memoryManagement;
        if (mm == null) {
            mm = IocContainer.get(MemoryManagement.class);
            memoryManagement = mm;
        }
        return mm;
    }

    private BackgroundTaskManager taskManager() {
        var tm = taskManager;
        if (tm == null) {
            tm = IocContainer.get(BackgroundTaskManager.class);
            taskManager = tm;
        }
        return tm;
    }

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
            final var adminDatabasesColl = readWholeCollection(Globals.ADMIN_DB_NAME,
                    Globals.ADMIN_DATABASES_COLLECTION_NAME);
            loadAdminEntries(adminDatabasesColl, Globals.ADMIN_DATABASES_COLLECTION_NAME, AdminDbEntry::fromJsonObject,
                    databases);
        }
        if (!pkIndexAdminCollEntries.isEmpty()) {
            final var adminCollectionsColl = readWholeCollection(Globals.ADMIN_DB_NAME,
                    Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            loadAdminEntries(adminCollectionsColl, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                    AdminCollEntry::fromJsonObject, collections);
        }
        if (!pkIndexAdminUserEntriesMap.isEmpty()) {
            final var adminUsersColl = readWholeCollection(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);
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
        final var collId = getCollectionIdentifier(dbName, collName);
        final var pkIdx = fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, pagesCollName);
        // The PK index loaded here belongs to pagesCollName (the file on disk that holds the
        // AdminPageEntries for `collName`). It must be keyed by (admin, pagesCollName) because
        // that's where insertAdminPages / updateTouchedPagesInFileSystem look it up.
        pagesPkIndexes.put(getCollectionIdentifier(Globals.ADMIN_DB_NAME, pagesCollName), new ArrayList<>(pkIdx));
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
        pages.put(getCollectionIdentifier(Globals.ADMIN_DB_NAME, collName), entries);
    }

    private Map<String, DbEntry> readWholeCollection(String dbName, String collName) throws IOException {
        final var result = new HashMap<String, DbEntry>();
        try (var pagesStream = fs.streamPages(dbName, collName)) {
            pagesStream.forEach(result::putAll);
        }
        return result;
    }

    public static String getCollectionIdentifier(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    public static String getIndexIdentifier(String fieldName, Class<?> fieldType) {
        final var parts = fieldType.getName().split("\\.");
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + parts[parts.length - 1];
    }

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName) throws IOException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        var primaryKeyIndex = pkIndexMap.get(collectionIdentifier);
        if (primaryKeyIndex == null) {
            primaryKeyIndex = fs.readWholePkIndexFile(dbName, collName);
            if (shouldCache(dbName, estimatePkIndexSize(primaryKeyIndex))) {
                pkIndexMap.put(collectionIdentifier, primaryKeyIndex);
            }
        }
        return primaryKeyIndex;
    }

    private boolean isCachingDisabled(String dbName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return false;
        }
        return configuration.isCachingDisabled();
    }

    private boolean shouldCache(String dbName, long estimatedBytes) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return true;
        }
        return memoryManagement().admissionCheck(estimatedBytes) == AdmissionDecision.ADMIT;
    }

    public Map<String, List<FieldIndexEntry<?>>> getAllFieldIndexesAndLoadIfNecessary(String dbName, String collName,
            String fieldName) {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        var indexes = fieldIndexMap.get(collectionIdentifier);
        Map<String, List<FieldIndexEntry<?>>> indexMap;
        if (indexes == null) {
            final var allIndexesForField = fs.readAllWholeFieldIndexFiles(dbName, collName, fieldName);
            indexMap = new ConcurrentHashMap<>(allIndexesForField);
            long total = 0L;
            for (var list : indexMap.values()) {
                total += estimateFieldIndexSize(list);
            }
            if (shouldCache(dbName, total)) {
                fieldIndexMap.put(collectionIdentifier, indexMap);
            }
            return allIndexesForField;
        } else {
            indexMap = indexes;
        }
        return indexMap;
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var indexIdentifier = getIndexIdentifier(fieldName, indexType);
        var index = fieldIndexMap.get(collectionIdentifier);
        List<FieldIndexEntry<T>> indexEntries = null;
        if (index == null || index.keySet().stream().noneMatch(string -> string.contains(indexIdentifier))) {
            indexEntries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType);
            if (indexEntries == null) {
                return null;
            } else if (index == null) {
                index = new ConcurrentHashMap<>();
            }
            final var asWildcard = new ArrayList<FieldIndexEntry<?>>(indexEntries);
            if (shouldCache(dbName, estimateFieldIndexSize(asWildcard))) {
                index.put(indexIdentifier, new ArrayList<>(indexEntries));
                fieldIndexMap.put(collectionIdentifier, index);
            }
        } else {
            final var existingIndex = index.get(indexIdentifier);
            if (existingIndex != null) {
                indexEntries = existingIndex.stream()
                        .map(fieldIndexEntry -> new FieldIndexEntry<>(fieldIndexEntry.getDatabaseName(),
                                fieldIndexEntry.getCollectionName(), indexType.cast(fieldIndexEntry.getValue()),
                                fieldIndexEntry.getIds()))
                        .collect(Collectors.toList());
            }
        }
        return indexEntries;
    }

    public <T> Set<String> getIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator,
            T value) throws IOException {
        final var result = doGetIdsFromIndex(dbName, collName, fieldName, operator, value);
        if (result != null && !Globals.ADMIN_DB_NAME.equals(dbName)) {
            recordFieldIndexAccess(dbName, collName, fieldName);
        }
        return result;
    }

    private void recordFieldIndexAccess(String dbName, String collName, String fieldName) {
        memoryManagement().recordAccess(AccessKind.FIELD_INDEX, dbName, collName, fieldName);
        taskManager().submitBackgroundTask(new CollectionUsageEvent(AccessKind.FIELD_INDEX, dbName, collName, fieldName,
                System.currentTimeMillis()));
    }

    @SuppressWarnings("unchecked")
    private <T> Set<String> doGetIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator,
            T value) throws IOException {
        return switch (value) {
            case Number n -> {
                final var numberIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class);
                if (numberIndex != null) {
                    yield SearchUtils.findingByOperator(numberIndex, operator.getFieldOperatorType(), n);
                } else {
                    yield null;
                }
            }
            case Boolean b -> {
                final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class);
                if (booleanIndex != null) {
                    yield SearchUtils.findingByOperator(booleanIndex, operator.getFieldOperatorType(), b);
                } else {
                    yield null;
                }
            }
            case String s -> {
                final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class);
                if (stringIndex != null) {
                    yield SearchUtils.findingByOperator(stringIndex, operator.getFieldOperatorType(), s);
                } else {
                    yield null;
                }
            }
            case JsonCustom<?> c -> {
                final var customTypes = CustomTypeFactory.getCustomTypes();
                final var customClass = customTypes.get(c.getCustomTypeName());
                final var customIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                        (Class<T>) customClass);
                if (customIndex != null) {
                    yield SearchUtils.findingByOperator(customIndex, operator.getFieldOperatorType(), (T) c);
                } else {
                    yield null;
                }
            }
            case JsonArray arr -> {
                final var firstElement = arr.get(0);
                if (firstElement.isJsonPrimitive()) {
                    final var prim = firstElement.asJsonPrimitive();
                    final var listStream = arr.asList().stream();
                    switch (prim) {
                        case JsonCustom<?> c -> {
                            final var customTypes = CustomTypeFactory.getCustomTypes();
                            final var customClass = customTypes.get(c.getCustomTypeName());
                            final var customIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    (Class<T>) customClass);
                            if (customIndex != null) {
                                final var custList = listStream.map(JsonBaseElement::asJsonCustom).toList();
                                yield SearchUtils.findingInNotIn(customIndex, operator.getFieldOperatorType(),
                                        (List<T>) custList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonString ignored -> {
                            final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    String.class);
                            if (stringIndex != null) {
                                final var strList = listStream.map(x -> x.asJsonString().getValue()).toList();
                                yield SearchUtils.findingInNotIn(stringIndex, operator.getFieldOperatorType(), strList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonNumber ignored -> {
                            final var numberIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    Number.class);
                            if (numberIndex != null) {
                                final var numberList = listStream.map(x -> x.asJsonNumber().getValue()).toList();
                                yield SearchUtils.findingInNotIn(numberIndex, operator.getFieldOperatorType(),
                                        numberList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonBoolean ignored -> {
                            final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                    Boolean.class);
                            if (booleanIndex != null) {
                                final var booleanList = listStream.map(x -> x.asJsonBoolean().getValue()).toList();
                                yield SearchUtils.findingInNotIn(booleanIndex, operator.getFieldOperatorType(),
                                        booleanList);
                            } else {
                                yield null;
                            }
                        }
                        default -> {
                            yield null;
                        }
                    }
                } else {
                    yield null;
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        if (!shouldCache(dbName, entry.byteSize())) {
            return;
        }
        final var collId = getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        coll.put(entry.get_id(), entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        long total = 0L;
        for (var e : entries) {
            total += e.byteSize();
        }
        if (!shouldCache(dbName, total)) {
            return;
        }
        final var collId = getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        coll.putAll(entries.stream().collect(Collectors.toMap(DbEntry::get_id, o -> o, (_, b) -> b)));
    }

    public DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        if (isCachingDisabled(dbName)) {
            return fs.getById(idxEntry);
        }
        final var coll = collectionMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>());
        final var pk = idxEntry.getValue();
        var entry = coll.get(pk);
        if (entry == null) {
            entry = fs.getById(idxEntry);
            if (shouldCache(dbName, entry.byteSize())) {
                coll.put(pk, entry);
            }
        }
        return entry;
    }

    public Map<String, DbEntry> getWholeCollection(String dbName, String collName) {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var wholeCollection = collectionMap.get(collectionIdentifier);
        final var collPages = pages.get(collectionIdentifier);
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
            if (!isCachingDisabled(dbName)) {
                final var asMap = new ConcurrentHashMap<>(loaded);
                if (shouldCache(dbName, estimateCollectionSize(asMap))) {
                    collectionMap.put(collectionIdentifier, asMap);
                    return asMap;
                }
            }
            return loaded;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids) throws IOException {
        final var result = new ArrayList<DbEntry>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var cachingDisabled = isCachingDisabled(dbName);
        final var cached = cachingDisabled ? null : collectionMap.get(collectionIdentifier);
        // Serve cache hits directly by id; only ids that are not cached need to be
        // resolved through the PK index and targeted-read from disk.
        final var missingIds = new ArrayList<String>();
        for (var id : ids) {
            final var hit = cached != null ? cached.get(id) : null;
            if (hit != null) {
                result.add(hit);
            } else {
                missingIds.add(id);
            }
        }
        if (missingIds.isEmpty()) {
            return result;
        }
        final var pkIndex = getPkIndexAndLoadIfNecessary(dbName, collName);
        // Snapshot the resolved PkIndexEntry references. They are immutable value carriers,
        // so reading against this snapshot is unaffected by a concurrent write that shifts
        // offsets mid-read; read/write isolation for aggregates is a separate, pre-existing
        // concern (the aggregate path already reads lock-free).
        final var toRead = new ArrayList<PkIndexEntry>();
        for (var id : missingIds) {
            final var pos = Collections.binarySearch(pkIndex, id);
            if (pos >= 0) {
                toRead.add(pkIndex.get(pos));
            }
        }
        if (toRead.isEmpty()) {
            return result;
        }
        final var read = fs.getByIndexEntries(toRead);
        result.addAll(read);
        if (!cachingDisabled) {
            long bytes = 0L;
            for (var e : read) {
                bytes += e.byteSize();
            }
            if (shouldCache(dbName, bytes)) {
                final var coll = collectionMap.computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>());
                for (var e : read) {
                    coll.put(e.get_id(), e);
                }
            }
        }
        return result;
    }

    public Stream<DbEntry> streamCollection(String dbName, String collName) throws IOException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        if (!isCachingDisabled(dbName)) {
            final var cached = collectionMap.get(collectionIdentifier);
            if (cached != null && !cached.isEmpty()) {
                final var collPages = pages.get(collectionIdentifier);
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
        final var collPages = pages.get(getCollectionIdentifier(dbName, collName));
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

    public long selectPageForInsert(String dbName, String collName, int entryByteSize) {
        return selectPageForInsert(dbName, collName, entryByteSize, Map.of());
    }

    public long selectPageForInsert(String dbName, String collName, int entryByteSize,
            Map<Long, Long> pendingPageBytes) {
        final var maxPageBytes = configuration.getMaxPageSize();
        final var pageEntries = pages.computeIfAbsent(getCollectionIdentifier(dbName, collName),
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
        return collections.get(getCollectionIdentifier(dbName, collName));
    }

    public List<AdminPageEntry> getAdminPageEntries(String dbName, String collName) {
        return pages.get(getCollectionIdentifier(dbName, collName));
    }

    public AdminPageEntry getAdminPageEntry(String dbName, String collName, long page) {
        final var entries = pages.get(getCollectionIdentifier(dbName, collName));
        if (entries == null)
            return null;
        return entries.stream().filter(p -> p.getPage() == page).findFirst().orElse(null);
    }

    public void putAdminPageEntries(String dbName, String collName, List<AdminPageEntry> adminPageEntries) {
        pages.put(getCollectionIdentifier(dbName, collName), adminPageEntries);
    }

    public void addAdminPageEntries(String dbName, String collName, AdminPageEntry adminPageEntry) {
        pages.computeIfAbsent(getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>()).add(adminPageEntry);
    }

    public void updatePageSizeInMemory(String dbName, String collName, long page, long bytesDelta) {
        final var pageEntries = pages.computeIfAbsent(getCollectionIdentifier(dbName, collName),
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
        return pagesPkIndexes.computeIfAbsent(getCollectionIdentifier(dbName, collName), _ -> new ArrayList<>());
    }

    public void removeAdminPageEntries(String dbName, String collName) {
        final var collId = getCollectionIdentifier(dbName, collName);
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

    public void evictEntry(String dbName, String collName, String pk) {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var coll = collectionMap.get(collectionIdentifier);
        if (coll != null) {
            coll.remove(pk);
        }
    }

    public void evictDatabase(String dbName) {
        final var toRemove = collectionMap.keySet().stream().filter(s -> s.startsWith(dbName)).toList();
        for (var entryKeyToRemove : toRemove) {
            pkIndexMap.remove(entryKeyToRemove);
            collectionMap.remove(entryKeyToRemove);
        }
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
        collectionMap.remove(collIdentifier);
    }

    public void evictCollectionDocuments(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = getCollectionIdentifier(dbName, collName);
        collectionMap.remove(collIdentifier);
    }

    public void evictPkIndex(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
    }

    public void evictFieldIndex(String dbName, String collName, String indexKey) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        final var collIdentifier = getCollectionIdentifier(dbName, collName);
        final var indexes = fieldIndexMap.get(collIdentifier);
        if (indexes != null) {
            indexes.remove(indexKey);
            if (indexes.isEmpty()) {
                fieldIndexMap.remove(collIdentifier);
            }
        }
    }

    public List<CacheableResource> listCacheableResources() {
        final var result = new ArrayList<CacheableResource>();
        for (var entry : pkIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            result.add(new CacheableResource(AccessKind.PK_INDEX, parts[0], parts[1], null,
                    estimatePkIndexSize(entry.getValue())));
        }
        for (var entry : collectionMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            result.add(new CacheableResource(AccessKind.COLLECTION, parts[0], parts[1], null,
                    estimateCollectionSize(entry.getValue())));
        }
        for (var entry : fieldIndexMap.entrySet()) {
            final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            if (parts.length < 2 || Globals.ADMIN_DB_NAME.equals(parts[0]))
                continue;
            for (var inner : entry.getValue().entrySet()) {
                result.add(new CacheableResource(AccessKind.FIELD_INDEX, parts[0], parts[1], inner.getKey(),
                        estimateFieldIndexSize(inner.getValue())));
            }
        }
        return result;
    }

    private long estimatePkIndexSize(List<PkIndexEntry> entries) {
        if (entries == null)
            return 0L;
        return (long) entries.size() * ESTIMATED_PK_ENTRY_BYTES;
    }

    private long estimateCollectionSize(Map<String, DbEntry> entries) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries.values()) {
            total += e.byteSize();
        }
        return total;
    }

    private long estimateFieldIndexSize(List<FieldIndexEntry<?>> entries) {
        if (entries == null)
            return 0L;
        long total = 0L;
        for (var e : entries) {
            final var value = e.getValue();
            final var valueLen = value == null ? 0 : value.toString().length() * 2L;
            final var ids = e.getIds();
            final var idsLen = ids == null ? 0L : ids.size() * ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES;
            total += valueLen + idsLen + ESTIMATED_FIELD_ENTRY_OVERHEAD_BYTES;
        }
        return total;
    }

    public Stream<JsonObject> initializeStreamIfNecessary(Stream<JsonObject> resultStream, String dbName,
            String collName) throws IOException {
        if (resultStream != null) {
            return resultStream;
        }
        return streamCollection(dbName, collName).map(DbEntry::getData);
    }

    public boolean hasIndex(String dbName, String collName, String fieldName) {
        return getIndexesForCollection(dbName, collName).contains(fieldName);
    }

    public boolean hasLoadedIndex(String dbName, String collName, String fieldName) {
        final var fieldIndexes = fieldIndexMap.get(getCollectionIdentifier(dbName, collName));
        if (fieldIndexes != null) {
            return fieldIndexes.containsKey(fieldName);
        }
        return false;
    }

    public Set<String> getIndexesForCollection(String dbName, String collName) {
        final var collection = collections.get(getCollectionIdentifier(dbName, collName));
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
