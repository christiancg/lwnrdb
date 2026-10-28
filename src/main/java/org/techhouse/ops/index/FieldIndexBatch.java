package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.cache.FieldIndexLookup;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;

public final class FieldIndexBatch {
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final Collection<String> batchIds;
    private final List<FieldIndexSlot> slots = new ArrayList<>();
    private final Map<String, FieldIndexSlot> slotsByIdentifier = new HashMap<>();
    private final Map<Class<?>, FieldIndexSlot> scalarSlots = new HashMap<>();
    private final Map<IndexKind, FieldIndexSlot> hashSlots = new EnumMap<>(IndexKind.class);
    private FieldIndexLookup lookup;
    private Set<String> scopedTo;
    private boolean loaded;

    private FieldIndexBatch(String dbName, String collName, String fieldName, Collection<String> batchIds) {
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
        this.batchIds = batchIds;
    }

    public static FieldIndexBatch open(String dbName, String collName, String fieldName, Collection<String> batchIds) {
        return new FieldIndexBatch(dbName, collName, fieldName, batchIds);
    }

    private record Target(FieldIndexSlot slot, String key, Object value) {
    }

    public void apply(String id, JsonBaseElement currentValue) throws IOException {
        loadEveryType();
        final var target = targetOf(currentValue);
        final var found = target == null ? null : target.slot().find(target.key(), target.value());
        final var current = lookup.locate(id);
        if (current != null && found != null && alreadyHolds(current, target, found)) {
            return;
        }
        if (current != null) {
            for (var at = current; at != null; at = at.next()) {
                slotsByIdentifier.get(at.typeIdentifier()).removeId(id, at.entry());
            }
        }
        if (target != null) {
            final var entry = target.slot().addId(id, target.key(), target.value(), found);
            lookup.moved(id, new FieldIndexLookup.Located(target.slot().identifier(), entry, null));
        } else {
            lookup.moved(id, null);
        }
    }

    private static boolean alreadyHolds(FieldIndexLookup.Located current, Target target,
            FieldIndexEntry<Object> found) {
        return current.next() == null && current.typeIdentifier().equals(target.slot().identifier())
                && found == current.entry();
    }

    public void flush() throws IOException {
        var firstWritten = false;
        for (final var slot : slots) {
            if (slot.write() && !firstWritten) {
                firstWritten = true;
                faultInjector.at(FaultPoint.INDEX_BATCH_FLUSHED, Cache.getCollectionIdentifier(dbName, collName));
            }
        }
        for (final var slot : slots) {
            slot.publish();
        }
        if (scopedTo != null) {
            cache.evictFieldIndexLookup(dbName, collName, fieldName);
        } else {
            keepLookupWhileCovered();
        }
    }

    private void keepLookupWhileCovered() {
        if (lookup == null) {
            return;
        }
        for (final var slot : slots) {
            if (!slot.coveredByCache()) {
                cache.evictFieldIndexLookup(dbName, collName, fieldName);
                return;
            }
        }
        cache.admitFieldIndexLookup(dbName, collName, fieldName, lookup);
    }

    private void loadEveryType() throws IOException {
        if (loaded) {
            return;
        }
        loaded = true;
        var covered = true;
        for (final var type : IndexKind.scalarIndexClasses()) {
            if (mayHaveAFile(Cache.getIndexIdentifier(fieldName, type))) {
                covered &= register(scalarSlots.computeIfAbsent(type,
                        _ -> FieldIndexSlot.scalar(dbName, collName, fieldName, type)));
            }
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            if (mayHaveAFile(Cache.getHashIndexIdentifier(fieldName, kind.label()))) {
                covered &= register(
                        hashSlots.computeIfAbsent(kind, _ -> FieldIndexSlot.hash(dbName, collName, fieldName, kind)));
            }
        }
        lookup = covered && slots.stream().anyMatch(FieldIndexSlot::isResident)
                ? cache.fieldIndexLookup(dbName, collName, fieldName)
                : null;
        if (lookup == null) {
            buildLookup(covered);
        }
    }

    private void buildLookup(boolean covered) {
        var ids = 0L;
        for (final var slot : slots) {
            ids += slot.idCount();
        }
        final var full = covered
                && cache.mayAdmitFieldIndexLookup(dbName, collName, fieldName, (int) Math.min(Integer.MAX_VALUE, ids));
        scopedTo = full ? null : new HashSet<>(batchIds);
        lookup = new FieldIndexLookup(full ? (int) Math.min(Integer.MAX_VALUE, ids) : scopedTo.size());
        for (final var slot : slots) {
            slot.indexInto(lookup, scopedTo);
        }
    }

    private boolean register(FieldIndexSlot slot) throws IOException {
        slots.add(slot);
        slotsByIdentifier.put(slot.identifier(), slot);
        return slot.attach();
    }

    private boolean mayHaveAFile(String indexIdentifier) {
        return !cache.isFieldIndexTypeAbsent(dbName, collName, indexIdentifier);
    }

    private FieldIndexSlot slotFor(Class<?> type) throws IOException {
        final var existing = scalarSlots.get(type);
        if (existing != null) {
            return existing;
        }
        final var slot = FieldIndexSlot.scalar(dbName, collName, fieldName, type);
        scalarSlots.put(type, slot);
        return registerLate(slot);
    }

    private FieldIndexSlot hashSlotFor(IndexKind kind) throws IOException {
        final var existing = hashSlots.get(kind);
        if (existing != null) {
            return existing;
        }
        final var slot = FieldIndexSlot.hash(dbName, collName, fieldName, kind);
        hashSlots.put(kind, slot);
        return registerLate(slot);
    }

    private FieldIndexSlot registerLate(FieldIndexSlot slot) throws IOException {
        register(slot);
        slot.indexInto(lookup, scopedTo);
        return slot;
    }

    private Target targetOf(JsonBaseElement value) throws IOException {
        final var indexed = IndexedValue.of(value);
        if (indexed == null) {
            return null;
        }
        final var slot = indexed.isHashed() ? hashSlotFor(indexed.kind()) : slotFor(indexed.type());
        return new Target(slot, indexed.key(), indexed.value());
    }
}
