package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
import org.techhouse.fs.IndexLogChange;
import org.techhouse.ioc.IocContainer;

final class FieldIndexSlot {
    static final int POINT_EDIT_LIMIT = 64;
    private static final int CREATED_IN_BATCH = -1;
    private static final Cache cache = IocContainer.get(Cache.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final String identifier;
    private final String fileLabel;
    private final Comparator<FieldIndexEntry<Object>> order;
    private final boolean spellingMatters;
    private final Loader loader;
    private final Map<String, FieldIndexEntry<Object>> created = new HashMap<>();
    private final Map<FieldIndexEntry<Object>, Integer> touched = new IdentityHashMap<>();
    private final Map<FieldIndexEntry<Object>, Boolean> emptied = new IdentityHashMap<>();
    private Map<String, Map<String, Boolean>> logged;
    private List<FieldIndexEntry<Object>> resident;
    private boolean onDisk;
    private List<FieldIndexEntry<Object>> entries;
    private int previousCount;
    private long idBytesDelta;
    private boolean dirty;

    @FunctionalInterface
    private interface Loader {
        List<? extends FieldIndexEntry<?>> load() throws IOException;
    }

    private FieldIndexSlot(String dbName, String collName, String fieldName, String identifier, String fileLabel,
            Comparator<FieldIndexEntry<Object>> order, boolean spellingMatters, Loader loader) {
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
        this.identifier = identifier;
        this.fileLabel = fileLabel;
        this.order = order;
        this.spellingMatters = spellingMatters;
        this.loader = loader;
    }

    static FieldIndexSlot scalar(String dbName, String collName, String fieldName, Class<?> type) {
        return new FieldIndexSlot(dbName, collName, fieldName, Cache.getIndexIdentifier(fieldName, type),
                IndexKind.fileLabel(type), cast(FieldIndexLoader.orderFor(type)), hasSpellings(type),
                () -> fs.readWholeFieldIndexFiles(dbName, collName, fieldName, type));
    }

    static FieldIndexSlot hash(String dbName, String collName, String fieldName, IndexKind kind) {
        return new FieldIndexSlot(dbName, collName, fieldName, Cache.getHashIndexIdentifier(fieldName, kind.label()),
                kind.label(), cast(FieldIndexLoader.hashOrder()), true,
                () -> fs.readWholeHashIndexFile(dbName, collName, fieldName, kind));
    }

    private static boolean hasSpellings(Class<?> type) {
        return !Number.class.isAssignableFrom(type) && type != Boolean.class;
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
            if (!onDisk && !fs.indexTypeFileExists(dbName, collName, fieldName, fileLabel)) {
                cache.rememberFieldIndexTypeAbsent(dbName, collName, identifier);
            } else if (!onDisk) {
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
        if (!spellingMatters && isAloneInItsRun(at, probe)) {
            return entries.get(at);
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

    private boolean isAloneInItsRun(int at, FieldIndexEntry<Object> probe) {
        return (at == 0 || order.compare(entries.get(at - 1), probe) != 0)
                && (at + 1 == entries.size() || order.compare(entries.get(at + 1), probe) != 0);
    }

    FieldIndexEntry<Object> addId(String id, String key, Object value, FieldIndexEntry<Object> found) {
        var entry = found;
        if (entry == null) {
            entry = new FieldIndexEntry<>(dbName, collName, value, new HashSet<>());
            created.put(key, entry);
            if (resident != null) {
                touched.put(entry, CREATED_IN_BATCH);
            }
        } else {
            touch(entry);
        }
        if (entry.getIds().add(id) && resident != null) {
            idBytesDelta += CacheSizeEstimator.fieldIndexIdSize(id);
        }
        log(key, id, true);
        dirty = true;
        return entry;
    }

    void removeId(String id, FieldIndexEntry<Object> entry) {
        touch(entry);
        if (entry.getIds().remove(id)) {
            if (resident != null) {
                idBytesDelta -= CacheSizeEstimator.fieldIndexIdSize(id);
            }
            log(FieldIndexEntry.fileKeyOf(entry.getValue()), id, false);
            dirty = true;
            if (entry.getIds().isEmpty()) {
                emptied.put(entry, Boolean.TRUE);
            }
        }
    }

    private void log(String key, String id, boolean present) {
        if (logged == null) {
            logged = new LinkedHashMap<>();
        }
        logged.computeIfAbsent(key, _ -> new LinkedHashMap<>()).put(id, present);
    }

    private void touch(FieldIndexEntry<Object> entry) {
        if (resident != null) {
            touched.putIfAbsent(entry, entry.getIds().size());
        }
    }

    boolean write() throws IOException {
        if (!dirty) {
            return false;
        }
        applyInPlace();
        cache.forgetFieldIndexTypeAbsent(dbName, collName, identifier);
        fs.appendIndexLog(dbName, collName, fieldName, fileLabel, changes(), entries);
        return true;
    }

    private List<IndexLogChange> changes() {
        if (logged == null) {
            return List.of();
        }
        final var changes = new ArrayList<IndexLogChange>(logged.size());
        for (final var key : logged.entrySet()) {
            final var added = new ArrayList<String>();
            final var removed = new ArrayList<String>();
            for (final var id : key.getValue().entrySet()) {
                (id.getValue() ? added : removed).add(id.getKey());
            }
            changes.add(new IndexLogChange(key.getKey(), added, removed));
        }
        return changes;
    }

    private void applyInPlace() {
        final var removed = Collections.newSetFromMap(new IdentityHashMap<FieldIndexEntry<Object>, Boolean>());
        for (final var entry : emptied.keySet()) {
            if (entry.getIds().isEmpty()) {
                removed.add(entry);
            }
        }
        final var added = new ArrayList<FieldIndexEntry<Object>>(created.size());
        for (final var entry : created.values()) {
            if (!entry.getIds().isEmpty()) {
                added.add(entry);
            }
        }
        if (removed.size() + added.size() > POINT_EDIT_LIMIT) {
            mergeInOnePass(removed, added);
            return;
        }
        for (final var entry : removed) {
            removeExact(entry);
        }
        for (final var entry : added) {
            final var at = Collections.binarySearch(entries, entry, order);
            entries.add(at < 0 ? -at - 1 : at, entry);
        }
    }

    private void removeExact(FieldIndexEntry<Object> entry) {
        final var at = indexOfExact(entry);
        if (at >= 0) {
            entries.remove(at);
        }
    }

    private int indexOfExact(FieldIndexEntry<Object> entry) {
        final var at = Collections.binarySearch(entries, entry, order);
        if (at < 0) {
            return -1;
        }
        for (var i = at; i >= 0 && order.compare(entries.get(i), entry) == 0; i--) {
            if (entries.get(i) == entry) {
                return i;
            }
        }
        for (var i = at + 1; i < entries.size() && order.compare(entries.get(i), entry) == 0; i++) {
            if (entries.get(i) == entry) {
                return i;
            }
        }
        return -1;
    }

    private void mergeInOnePass(Set<FieldIndexEntry<Object>> removed, List<FieldIndexEntry<Object>> added) {
        added.sort(order);
        final var removedAt = positionsOf(removed);
        final var merged = new ArrayList<FieldIndexEntry<Object>>(entries.size() - removedAt.length + added.size());
        var from = 0;
        var removedCursor = 0;
        for (final var entry : added) {
            final var at = upperBound(entry, from);
            removedCursor = copyWithout(merged, removedAt, removedCursor, from, at);
            merged.add(entry);
            from = at;
        }
        copyWithout(merged, removedAt, removedCursor, from, entries.size());
        entries.clear();
        entries.addAll(merged);
    }

    private int[] positionsOf(Set<FieldIndexEntry<Object>> removed) {
        final var positions = new int[removed.size()];
        var count = 0;
        for (final var entry : removed) {
            final var at = indexOfExact(entry);
            if (at >= 0) {
                positions[count++] = at;
            }
        }
        final var found = Arrays.copyOf(positions, count);
        Arrays.sort(found);
        return found;
    }

    private int upperBound(FieldIndexEntry<Object> entry, int from) {
        var low = from;
        var high = entries.size();
        while (low < high) {
            final var middle = (low + high) >>> 1;
            if (order.compare(entries.get(middle), entry) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private int copyWithout(List<FieldIndexEntry<Object>> target, int[] removedAt, int cursor, int from, int to) {
        var start = from;
        var next = cursor;
        while (next < removedAt.length && removedAt[next] < to) {
            final var gone = removedAt[next++];
            target.addAll(entries.subList(start, gone));
            start = gone + 1;
        }
        target.addAll(entries.subList(start, to));
        return next;
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
        cache.rememberFieldIndexBatch(dbName, collName, identifier, previousCount, bytesDelta());
    }

    boolean coveredByCache() {
        return entries.isEmpty() || cache.residentFieldIndex(dbName, collName, identifier) != null;
    }

    private void admitWhatWasLoaded() {
        if ((dirty || onDisk) && !entries.isEmpty()) {
            cache.admitMaintainedFieldIndex(dbName, collName, identifier, cast(entries));
        }
    }

    private long bytesDelta() {
        var total = idBytesDelta;
        for (final var change : touched.entrySet()) {
            final var entry = change.getKey();
            final int countBefore = change.getValue();
            if (countBefore != CREATED_IN_BATCH) {
                total -= CacheSizeEstimator.fieldEntryShellSize(entry.getValue(), countBefore);
            }
            if (!entry.getIds().isEmpty()) {
                total += CacheSizeEstimator.fieldEntryShellSize(entry.getValue(), entry.getIds().size());
            }
        }
        return total;
    }
}
