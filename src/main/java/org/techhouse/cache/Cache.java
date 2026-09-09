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

/**
 * Facade over the two separated cache stores: {@link AdminCache} for admin
 * (internal metadata) entries and {@link UserCache} for user document/index
 * entries. Admin methods delegate to {@link AdminCache} and user methods to
 * {@link UserCache}; the cross-cutting read/stream methods are implemented here
 * because they combine admin page metadata with the user document cache. The
 * facade stays a concrete IoC singleton so the existing
 * {@code IocContainer.get(Cache.class)} call sites are unchanged.
 */
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

    /**
     * Applies the in-memory PK position fix described by a {@link PkCompaction} returned from a
     * delete/update, routing to the admin or user cache by database. Null-safe: a {@code null}
     * compaction (no survivor moved) is a no-op.
     */
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

    // Records documents touched by a read for AGGREGATE analyze mode. A no-op when analyze is off.
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
        // The completeness gate uses the synchronously-maintained PK index size as the authoritative
        // document count, NOT the admin page entry counts: those are updated by the background worker
        // and lag behind committed writes, so under memory pressure (when a freshly inserted document
        // is not admitted into the cache) a stale low entry count could wrongly accept an incomplete
        // cached map. The PK index is written synchronously on every save/delete (see CountOperatorHelper).
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

    // The exact, currently-consistent document count from the synchronously-maintained PK index.
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
            // See getWholeCollection: gate on the synchronous PK index size, not the lagging admin
            // page entry counts, so a stale count can never accept an incomplete cached collection.
            if (cached != null && !cached.isEmpty() && cached.size() >= pkIndexSize(dbName, collName)) {
                return decorateScan(cached.values().stream());
            }
        }
        return decorateScan(streamCollectionFromDisk(dbName, collName));
    }

    // Counts entries as they are consumed for AGGREGATE analyze mode (the stream is lazy, so the
    // count reflects documents actually scanned). The context is captured on the consuming thread,
    // which is the same virtual thread that registered it. A no-op when analyze is off.
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
