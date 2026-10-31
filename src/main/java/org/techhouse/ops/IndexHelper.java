package org.techhouse.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.bckg_ops.events.IndexPriors;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.ops.index.IndexBuild;
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.ops.index.IndexValueCodec;
import org.techhouse.ops.index.IndexedValue;
import org.techhouse.utils.FieldPath;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();

    public static List<String> getSortedIdsForField(String dbName, String collName, String fieldName,
            java.util.Comparator<FieldIndexEntry<?>> order, long maxIds, ReadObserver observer) throws IOException {
        return IndexEntryReader.sortedIdsForField(dbName, collName, fieldName, order, maxIds, observer);
    }

    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String dbName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        return IndexEntryReader.getIndexEntriesForField(dbName, collName, fieldName, observer);
    }

    public static List<Object> getDistinctIndexValues(String dbName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        return IndexEntryReader.distinctValuesForField(dbName, collName, fieldName, observer);
    }

    public static Set<String> getMatchingIdsForJoin(String dbName, String collName, String fieldName,
            Set<JsonBaseElement> localValues, ReadObserver observer) throws IOException {
        return IndexEntryReader.getMatchingIdsForJoin(dbName, collName, fieldName, localValues, observer);
    }

    public static List<String> idsInOrder(List<FieldIndexEntry<?>> sortedEntries,
            java.util.Comparator<FieldIndexEntry<?>> order, long maxIds) {
        return IndexEntryReader.idsInOrder(sortedEntries, order, maxIds);
    }

    public static JsonBaseElement indexValueToElement(Object value) {
        return IndexValueCodec.indexValueToElement(value);
    }

    public static Object elementToLookupValue(JsonBaseElement element) {
        return IndexValueCodec.elementToLookupValue(element);
    }

    public static void createIndex(String dbName, String collName, String fieldName) throws InterruptedException {
        try {
            IndexBuild.buildAll(dbName, collName, List.of(fieldName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean dropIndex(String dbName, String collName, String fieldName) throws InterruptedException {
        rl.lockIndex(dbName, collName, fieldName);
        try {
            return fs.dropIndex(dbName, collName, fieldName);
        } finally {
            cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }

    private static List<String> maintainedIndexes(String dbName, String collName) {
        final var registered = cache.getIndexesForCollection(dbName, collName);
        final var maintained = new ArrayList<String>(registered.size());
        for (final var fieldName : registered) {
            if (!fs.indexMarkers().isMarked(dbName, collName, fieldName)) {
                maintained.add(fieldName);
            }
        }
        return maintained;
    }

    private static boolean maintainsNoIndex(String dbName, String collName) {
        for (final var fieldName : cache.getIndexesForCollection(dbName, collName)) {
            if (!fs.indexMarkers().isMarked(dbName, collName, fieldName)) {
                return false;
            }
        }
        return true;
    }

    private static void lockCollectionForMaintenance(String dbName, String collName, long budget)
            throws InterruptedException {
        if (!rl.tryLockRead(dbName, collName, budget)) {
            throw new CollectionBusyException(Cache.getCollectionIdentifier(dbName, collName), budget);
        }
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<String> ids)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(dbName, collName, ids, configuration.getTransactionLockTimeoutMs());
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<String> ids, long lockBudgetMs)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(dbName, collName, ids, lockBudgetMs, IndexPriors.NONE);
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<String> ids, long lockBudgetMs,
            IndexPriors priors) throws IOException, InterruptedException {
        if (ids.isEmpty() || maintainsNoIndex(dbName, collName)) {
            return;
        }
        lockCollectionForMaintenance(dbName, collName, lockBudgetMs);
        try {
            final var existingIndexes = maintainedIndexes(dbName, collName);
            if (existingIndexes.isEmpty()) {
                return;
            }
            final var byId = new HashMap<String, DbEntry>();
            for (var doc : cache.getEntriesByIds(dbName, collName,
                    ids.size() == 1 ? Set.of(ids.getFirst()) : new HashSet<>(ids))) {
                byId.put(doc.get_id(), doc);
            }
            for (var fieldName : existingIndexes) {
                maintainField(dbName, collName, fieldName, ids, byId, priors.forField(fieldName));
            }
        } finally {
            rl.releaseRead(dbName, collName);
        }
    }

    public static void updateIndexes(String dbName, String collName, String id)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(dbName, collName, Collections.singletonList(id));
    }

    private static void maintainField(String dbName, String collName, String fieldName, List<String> ids,
            Map<String, DbEntry> byId, Map<String, List<IndexedValue>> priors)
            throws IOException, InterruptedException {
        rl.lockIndex(dbName, collName, fieldName);
        var flushed = false;
        try {
            final var batch = FieldIndexBatch.open(dbName, collName, fieldName, ids, priors);
            final var path = FieldPath.of(fieldName);
            for (var id : ids) {
                final var doc = byId.get(id);
                batch.apply(id, doc == null ? null : path.valueIn(doc.getData()));
            }
            batch.flush();
            flushed = true;
        } finally {
            if (!flushed) {
                cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
            }
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }
}
