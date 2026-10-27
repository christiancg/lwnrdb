package org.techhouse.cache;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public final class CacheResidents {
    private CacheResidents() {
    }

    public static void reset(UserCache cache) throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(cache, "collectionMap", new ConcurrentHashMap<String, ResidentDocuments>());
        TestUtils.setPrivateField(cache, "fieldIndexMap", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "fieldLookups", new FieldIndexLookups());
        TestUtils.setPrivateField(cache, "pkIndexMap", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(cache, "sizes", new UserCacheSizes());
    }

    public static PkIndexList residentPk(UserCache cache, String collectionIdentifier, List<PkIndexEntry> rows) {
        final var list = PkIndexList.of(rows);
        list.chargeTo(cache.sizes().footprint().newCell(AccessKind.PK_INDEX),
                CacheSizeEstimator.pkIndexBytesPerEntry(rows));
        final var replaced = cache.pkIndexes().put(collectionIdentifier, list);
        if (replaced instanceof PkIndexList previous) {
            previous.retireCharge();
        }
        return list;
    }

    public static void residentDocument(UserCache cache, String collectionIdentifier, DbEntry entry) {
        cache.residentDocuments()
                .computeIfAbsent(collectionIdentifier,
                        _ -> new ResidentDocuments(cache.sizes().footprint().newCell(AccessKind.COLLECTION)))
                .put(entry);
    }

    public static Map<String, DbEntry> residentDocuments(UserCache cache, String collectionIdentifier) {
        final var resident = cache.residentDocuments().get(collectionIdentifier);
        return resident == null ? null : resident.documents();
    }

    public static Map<String, Map<String, DbEntry>> documentsByCollection(UserCache cache) {
        return new ResidentCollectionsView(cache);
    }

    public static void residentFieldIndex(UserCache cache, String collectionIdentifier, String indexKey,
            List<FieldIndexEntry<?>> entries) {
        final var measured = CacheSizeEstimator.estimateFieldIndexSize(entries);
        cache.fieldIndexes().computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>()).put(indexKey,
                entries);
        cache.sizes().rememberMeasuredFieldIndex(collectionIdentifier, indexKey, measured, entries);
    }

    public static void residentUnmeasuredFieldIndex(UserCache cache, String collectionIdentifier, String indexKey,
            List<FieldIndexEntry<?>> entries) {
        cache.fieldIndexes().computeIfAbsent(collectionIdentifier, _ -> new ConcurrentHashMap<>()).put(indexKey,
                entries);
        cache.sizes().adjustFieldIndex(collectionIdentifier, indexKey, entries.size(), entries.size(), 0L, 0);
    }

    public static long footprint(UserCache cache, AccessKind tier) {
        return cache.sizes().footprint().bytes(tier);
    }

    private static final class ResidentCollectionsView extends AbstractMap<String, Map<String, DbEntry>> {
        private final UserCache cache;

        private ResidentCollectionsView(UserCache cache) {
            this.cache = cache;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Set<Entry<String, Map<String, DbEntry>>> entrySet() {
            final var entries = new LinkedHashSet<Entry<String, Map<String, DbEntry>>>();
            for (final var collectionIdentifier : cache.residentDocuments().keySet()) {
                entries.add(new SimpleEntry<>(collectionIdentifier, get(collectionIdentifier)));
            }
            return entries;
        }

        @Override
        public boolean containsKey(Object key) {
            return cache.residentDocuments().containsKey(key);
        }

        @Override
        public Map<String, DbEntry> get(Object key) {
            final var resident = cache.residentDocuments().get(key);
            return resident == null ? null : new ResidentDocumentsView(resident);
        }

        @Override
        public Map<String, DbEntry> put(String key, Map<String, DbEntry> documents) {
            final var previous = get(key);
            final var resident = new ResidentDocuments(cache.sizes().footprint().newCell(AccessKind.COLLECTION));
            documents.forEach(resident::put);
            final var replaced = cache.residentDocuments().put(key, resident);
            if (replaced != null) {
                replaced.retire();
            }
            return previous;
        }

        @Override
        public Map<String, DbEntry> remove(Object key) {
            final var previous = get(key);
            final var removed = cache.residentDocuments().remove(key);
            if (removed != null) {
                removed.retire();
            }
            return previous;
        }

        @Override
        public Map<String, DbEntry> putIfAbsent(String key, Map<String, DbEntry> documents) {
            final var existing = get(key);
            if (existing != null) {
                return existing;
            }
            put(key, documents);
            return null;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Map<String, DbEntry> computeIfAbsent(String key,
                Function<? super String, ? extends Map<String, DbEntry>> created) {
            final var existing = get(key);
            if (existing != null) {
                return existing;
            }
            final var documents = created.apply(key);
            if (documents == null) {
                return null;
            }
            put(key, documents);
            return get(key);
        }

        @Override
        public void clear() {
            for (final var key : List.copyOf(cache.residentDocuments().keySet())) {
                remove(key);
            }
        }
    }

    private static final class ResidentDocumentsView extends AbstractMap<String, DbEntry> {
        private final ResidentDocuments resident;

        private ResidentDocumentsView(ResidentDocuments resident) {
            this.resident = resident;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Set<Entry<String, DbEntry>> entrySet() {
            return Collections.unmodifiableMap(resident.documents()).entrySet();
        }

        @Override
        public DbEntry get(Object key) {
            return resident.documents().get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return resident.documents().containsKey(key);
        }

        @Override
        public int size() {
            return resident.size();
        }

        @Override
        public DbEntry put(String key, DbEntry value) {
            final var previous = resident.get(key);
            resident.put(key, value);
            return previous;
        }

        @Override
        public DbEntry remove(Object key) {
            final var id = (String) key;
            final var previous = resident.documents().get(id);
            resident.remove(id);
            return previous;
        }
    }
}
