package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
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
    private final Map<String, FieldIndexEntry<Object>> byKey = new HashMap<>();
    private final List<FieldIndexEntry<Object>> created = new ArrayList<>();
    private final Map<FieldIndexEntry<Object>, Boolean> touched = new IdentityHashMap<>();
    private List<FieldIndexEntry<Object>> resident;
    private boolean onDisk;
    private List<FieldIndexEntry<Object>> entries;
    private List<FieldIndexEntry<Object>> written;
    private long bytesBefore;
    private boolean dirty;

    record Located(FieldIndexSlot slot, FieldIndexEntry<Object> entry, Located next) {
        boolean alreadyHolds(FieldIndexSlot target, String key) {
            return next == null && slot == target && slot.byKey.get(key) == entry;
        }

        void removeEverywhere(String id) {
            for (var at = this; at != null; at = at.next) {
                at.slot.removeId(id, at.entry);
            }
        }
    }

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

    void load(Map<String, Located> idToEntry) throws IOException {
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
        for (final var entry : entries) {
            byKey.putIfAbsent(FieldIndexEntry.fileKeyOf(entry.getValue()), entry);
            for (final var id : entry.getIds()) {
                idToEntry.put(id, new Located(this, entry, idToEntry.get(id)));
            }
        }
    }

    FieldIndexEntry<Object> addId(String id, String key, Object value) {
        var entry = byKey.get(key);
        if (entry == null) {
            entry = new FieldIndexEntry<>(dbName, collName, value, new HashSet<>());
            byKey.put(key, entry);
            created.add(entry);
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

    private void removeId(String id, FieldIndexEntry<Object> entry) {
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
        written = survivors();
        fs.rewriteIndexType(dbName, collName, fieldName, fileLabel, written);
        return true;
    }

    void publish() {
        if (resident == null) {
            admitWhatWasLoaded();
            return;
        }
        if (!dirty) {
            return;
        }
        if (written.isEmpty()) {
            cache.evictFieldIndex(dbName, collName, identifier);
            return;
        }
        final var previousCount = resident.size();
        resident.clear();
        resident.addAll(written);
        cache.rememberFieldIndexBatch(dbName, collName, identifier, previousCount, bytesAfter() - bytesBefore);
    }

    private void admitWhatWasLoaded() {
        final var current = dirty ? written : entries;
        if ((dirty || onDisk) && !current.isEmpty()) {
            cache.admitMaintainedFieldIndex(dbName, collName, identifier, cast(current));
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

    private List<FieldIndexEntry<Object>> survivors() {
        final var fresh = new ArrayList<FieldIndexEntry<Object>>(created.size());
        for (final var entry : created) {
            if (!entry.getIds().isEmpty()) {
                fresh.add(entry);
            }
        }
        fresh.sort(order);
        final var kept = new ArrayList<FieldIndexEntry<Object>>(entries.size() + fresh.size());
        var next = 0;
        for (final var entry : entries) {
            if (entry.getIds().isEmpty()) {
                continue;
            }
            while (next < fresh.size() && order.compare(fresh.get(next), entry) < 0) {
                kept.add(fresh.get(next++));
            }
            kept.add(entry);
        }
        while (next < fresh.size()) {
            kept.add(fresh.get(next++));
        }
        return kept;
    }
}
