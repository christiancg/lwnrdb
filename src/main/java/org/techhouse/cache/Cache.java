package org.techhouse.cache;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PkCompaction;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;

public class Cache implements UserCacheDelegate, AdminCacheDelegate {
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    @Override
    public UserCache userCache() {
        return userCache;
    }

    @Override
    public AdminCache adminCache() {
        return adminCache;
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

    public void shiftPkPositionsAfterCompaction(PkCompaction compaction) {
        if (compaction == null) {
            return;
        }
        if (Globals.ADMIN_DB_NAME.equals(compaction.dbName())
                || Globals.ADMIN_PAGES_DB_NAME.equals(compaction.dbName())) {
            adminCache.shiftPkPositionsAfterCompaction(compaction.collName(), compaction.page(),
                    compaction.removedPosition(), compaction.removedLength());
        } else {
            userCache.shiftPkPositionsAfterCompaction(compaction.dbName(), compaction.collName(), compaction.page(),
                    compaction.removedPosition(), compaction.removedLength());
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

    public List<DbEntry> getEntriesByIds(String dbName, String collName, Set<String> ids) throws IOException {
        final var entries = userCache.getEntriesByIds(dbName, collName, ids);
        recordScanned(entries.size());
        return entries;
    }

    private void recordScanned(long count) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext != null) {
            analyzeContext.addScanned(count);
        }
    }

    public void evictDatabase(String dbName) {
        userCache.evictDatabase(dbName);
        adminCache.removeCollectionSchemasForDatabase(dbName);
        adminCache.removeProceduresForDatabase(dbName);
        adminCache.removeSchedulesForDatabase(dbName);
        adminCache.removeTriggersForDatabase(dbName);
    }

    public void evictCollection(String dbName, String collName) {
        userCache.evictCollection(dbName, collName);
        adminCache.removeCollectionSchema(dbName, collName);
        adminCache.removeTriggers(dbName, collName);
    }

    public Map<String, DbEntry> getWholeCollection(String dbName, String collName) {
        final var wholeCollection = userCache.getCachedCollection(dbName, collName);
        // Gate completeness on the synchronous PK index size, never on the admin page entry counts:
        // those lag behind committed writes and would accept an incomplete cached map.
        if (wholeCollection != null && !wholeCollection.isEmpty()
                && wholeCollection.size() >= pkIndexSize(dbName, collName)) {
            recordScanned(wholeCollection.size());
            return wholeCollection;
        }
        try {
            final var loaded = readWholeCollection(dbName, collName);
            final var admitted = userCache.admitWholeCollection(dbName, collName, loaded);
            recordScanned(admitted.size());
            return admitted;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int pkIndexSize(String dbName, String collName) {
        try {
            return userCache.getPkIndexAndLoadIfNecessary(dbName, collName).size();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Stream<DbEntry> streamCollection(String dbName, String collName) throws IOException {
        if (!userCache.isCachingDisabled(dbName)) {
            final var cached = userCache.getCachedCollection(dbName, collName);
            if (cached != null && !cached.isEmpty() && cached.size() >= pkIndexSize(dbName, collName)) {
                return decorateScan(cached.values().stream());
            }
        }
        return decorateScan(streamCollectionFromDisk(dbName, collName));
    }

    private Stream<DbEntry> decorateScan(Stream<DbEntry> stream) {
        final var analyzeContext = AnalyzeContext.current();
        if (analyzeContext == null) {
            return stream;
        }
        return stream.peek(_ -> analyzeContext.addScanned(1));
    }

    private Stream<DbEntry> streamCollectionFromDisk(String dbName, String collName) throws IOException {
        final var collPages = adminCache.getAdminPageEntries(dbName, collName);
        if (collPages == null || collPages.isEmpty()) {
            return fs.streamEntries(dbName, collName);
        }
        final var maxPageBytes = configuration.getMaxPageSize();
        final var sortedPages = collPages.stream().sorted(Comparator.comparingLong(AdminPageEntry::getPage)).toList();
        // flatMap keeps this lazy, so only one page map is resident at a time.
        return sortedPages.stream().flatMap(pageEntry -> {
            final var estimate = pageEntry.getPageSize() > 0 ? pageEntry.getPageSize() : maxPageBytes;
            memoryManagement.ensureHeadroomForBytes(estimate);
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

    public long selectPageForInsert(String dbName, String collName, int entryByteSize,
            Map<Long, Long> pendingPageBytes) {
        return adminCache.selectPageForInsert(dbName, collName, entryByteSize, pendingPageBytes);
    }

    public boolean hasNoIndex(String dbName, String collName, String fieldName) {
        return !adminCache.hasIndex(dbName, collName, fieldName);
    }

}
