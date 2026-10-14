package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.cache.FieldIndexLookup;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.fs.FieldIndexLoader;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

final class FieldIndexSlot {
    private static final Cache cache = IocContainer.get(Cache.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final String identifier;
    private final String fileLabel;
    private final Comparator<FieldIndexEntry<Object>> order;
    private final Loader loader;
    private final Map<String, FieldIndexEntry<Object>> created = new HashMap<>();
    private final Map<FieldIndexEntry<Object>, Boolean> touched = new IdentityHashMap<>();
    private List<FieldIndexEntry<Object>> resident;
    private boolean onDisk;
    private List<FieldIndexEntry<Object>> entries;
    private int previousCount;
    private long bytesBefore;
    private boolean dirty;

    @FunctionalInterface
    private interface Loader {
        List<? extends FieldIndexEntry<?>> load() throws IOException;
    }

    private FieldIndexSlot(String dbName, String collName, String fieldName, String identifier, String fileLabel,
            Comparator<FieldIndexEntry<Object>> order, Loader loader) {
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
        this.identifier = identifier;
        this.fileLabel = fileLabel;
        this.order = order;
        this.loader = loader;
    }

    static FieldIndexSlot scalar(String dbName, String collName, String fieldName, Class<?> type) {
        return new FieldIndexSlot(dbName, collName, fieldName, Cache.getIndexIdentifier(fieldName, type),
                IndexKind.fileLabel(type), cast(FieldIndexLoader.orderFor(type)),
                () -> fs.readWholeFieldIndexFiles(dbName, collName, fieldName, type));
    }

    static FieldIndexSlot hash(String dbName, String collName, String fieldName, IndexKind kind) {
        return new FieldIndexSlot(dbName, collName, fieldName, Cache.getHashIndexIdentifier(fieldName, kind.label()),
                kind.label(), cast(FieldIndexLoader.hashOrder()),
                () -> fs.readWholeHashIndexFile(dbName, collName, fieldName, kind));
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    String identifier() {
        return identifier;
    }

    boolean attach() throws IOException {
        resident = cast(cache.residentFieldIndex(dbName, collName, identifier));
        if (resident != null) {
            entries = resident;
        } else {
            final var fromDisk = loader.load();
            onDisk = fromDisk != null;
            if (!onDisk && fs.indexTypeFileExists(dbName, collName, fieldName, fileLabel)) {
                throw new IOException(
                        "The " + fileLabel + " index file of " + Cache.getCollectionIdentifier(dbName, collName) + "."
                                + fieldName + " could not be read, so this batch leaves it untouched;"
                                + " run REINDEX on the collection to rebuild it");
            }
            entries = fromDisk != null ? cast(fromDisk) : new ArrayList<>();
        }
        previousCount = entries.size();
        return resident != null || entries.isEmpty();
    }

    boolean isResident() {
        return resident != null;
    }

    void indexInto(FieldIndexLookup lookup) {
        for (final var entry : entries) {
            for (final var id : entry.getIds()) {
                lookup.add(id, identifier, entry);
            }
        }
    }

    FieldIndexEntry<Object> find(String key, Object value) {
        final var fresh = created.get(key);
        if (fresh != null) {
            return fresh;
        }
        final var probe = new FieldIndexEntry<>(dbName, collName, value, Set.of());
        final var at = Collections.binarySearch(entries, probe, order);
        if (at < 0) {
            return null;
        }
        for (var i = at; i >= 0 && order.compare(entries.get(i), probe) == 0; i--) {
            if (FieldIndexEntry.fileKeyOf(entries.get(i).getValue()).equals(key)) {
                return entries.get(i);
            }
        }
        for (var i = at + 1; i < entries.size() && order.compare(entries.get(i), probe) == 0; i++) {
            if (FieldIndexEntry.fileKeyOf(entries.get(i).getValue()).equals(key)) {
                return entries.get(i);
            }
        }
        return null;
    }

    FieldIndexEntry<Object> addId(String id, String key, Object value) {
        var entry = find(key, value);
        if (entry == null) {
            entry = new FieldIndexEntry<>(dbName, collName, value, new HashSet<>());
            created.put(key, entry);
            if (resident != null) {
                touched.put(entry, Boolean.TRUE);
            }
        } else {
            touch(entry);
        }
        entry.getIds().add(id);
        dirty = true;
        return entry;
    }

    void removeId(String id, FieldIndexEntry<Object> entry) {
        touch(entry);
        if (entry.getIds().remove(id)) {
            dirty = true;
        }
    }

    private void touch(FieldIndexEntry<Object> entry) {
        if (resident != null && touched.putIfAbsent(entry, Boolean.TRUE) == null) {
            bytesBefore += sizeOf(entry);
        }
    }

    boolean write() throws IOException {
        if (!dirty) {
            return false;
        }
        applyInPlace();
        fs.rewriteIndexType(dbName, collName, fieldName, fileLabel, entries);
        return true;
    }

    private void applyInPlace() {
        entries.removeIf(entry -> entry.getIds().isEmpty());
        for (final var entry : created.values()) {
            if (entry.getIds().isEmpty()) {
                continue;
            }
            final var at = Collections.binarySearch(entries, entry, order);
            entries.add(at < 0 ? -at - 1 : at, entry);
        }
    }

    void publish() {
        if (resident == null) {
            admitWhatWasLoaded();
            return;
        }
        if (!dirty) {
            return;
        }
        if (entries.isEmpty()) {
            cache.evictFieldIndex(dbName, collName, identifier);
            return;
        }
        cache.rememberFieldIndexBatch(dbName, collName, identifier, previousCount, bytesAfter() - bytesBefore);
    }

    boolean coveredByCache() {
        return entries.isEmpty() || cache.residentFieldIndex(dbName, collName, identifier) != null;
    }

    private void admitWhatWasLoaded() {
        if ((dirty || onDisk) && !entries.isEmpty()) {
            cache.admitMaintainedFieldIndex(dbName, collName, identifier, cast(entries));
        }
    }

    private long bytesAfter() {
        var total = 0L;
        for (final var entry : touched.keySet()) {
            if (!entry.getIds().isEmpty()) {
                total += sizeOf(entry);
            }
        }
        return total;
    }

    private static long sizeOf(FieldIndexEntry<Object> entry) {
        return CacheSizeEstimator.estimateFieldIndexSize(List.of(entry));
    }
}
