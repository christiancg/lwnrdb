package org.techhouse.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.cache.FieldIndexLookup;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.JsonUtils;

public final class FieldIndexBatch {
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final Cache cache = IocContainer.get(Cache.class);

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final List<FieldIndexSlot> slots = new ArrayList<>();
    private final Map<String, FieldIndexSlot> slotsByIdentifier = new HashMap<>();
    private final Map<Class<?>, FieldIndexSlot> scalarSlots = new HashMap<>();
    private final Map<IndexKind, FieldIndexSlot> hashSlots = new EnumMap<>(IndexKind.class);
    private FieldIndexLookup lookup;
    private boolean loaded;

    private FieldIndexBatch(String dbName, String collName, String fieldName) {
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
    }

    public static FieldIndexBatch open(String dbName, String collName, String fieldName) {
        return new FieldIndexBatch(dbName, collName, fieldName);
    }

    private record Target(FieldIndexSlot slot, String key, Object value) {
    }

    public void apply(String id, JsonBaseElement currentValue) throws IOException {
        loadEveryType();
        final var target = targetOf(currentValue);
        final var current = lookup.locate(id);
        if (current != null && target != null && alreadyHolds(current, target)) {
            return;
        }
        if (current != null) {
            for (var at = current; at != null; at = at.next()) {
                slotsByIdentifier.get(at.typeIdentifier()).removeId(id, at.entry());
            }
        }
        if (target != null) {
            final var entry = target.slot().addId(id, target.key(), target.value());
            lookup.moved(id, new FieldIndexLookup.Located(target.slot().identifier(), entry, null));
        } else {
            lookup.moved(id, null);
        }
    }

    private static boolean alreadyHolds(FieldIndexLookup.Located current, Target target) {
        return current.next() == null && current.typeIdentifier().equals(target.slot().identifier())
                && target.slot().find(target.key(), target.value()) == current.entry();
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
        keepLookupWhileCovered();
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
            lookup = new FieldIndexLookup();
            for (final var slot : slots) {
                slot.indexInto(lookup);
            }
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
        slot.indexInto(lookup);
        return slot;
    }

    private Target targetOf(JsonBaseElement value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value != JsonNull.INSTANCE && value.isJsonPrimitive()) {
            final var primitive = value.asJsonPrimitive();
            return switch (primitive) {
                case JsonNumber number -> scalarTarget(Number.class, number.doubleValue() + 0.0);
                case JsonString string -> string.isJsonCustom()
                        ? customTarget(CustomTypeFactory.getCustomTypeInstance(string))
                        : scalarTarget(String.class, string.getValue());
                case JsonBoolean bool -> scalarTarget(Boolean.class, bool.getValue());
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            };
        }
        if (value.isJsonObject()) {
            return hashTarget(IndexKind.OBJECT, JsonUtils.hashElement(value));
        }
        if (value.isJsonArray()) {
            return hashTarget(IndexKind.ARRAY, JsonUtils.hashElement(value));
        }
        return null;
    }

    private Target scalarTarget(Class<?> type, Object value) throws IOException {
        return new Target(slotFor(type), FieldIndexEntry.fileKeyOf(value), value);
    }

    private Target customTarget(JsonCustom<?> custom) throws IOException {
        return scalarTarget(custom.getClass(), custom);
    }

    private Target hashTarget(IndexKind kind, String hash) throws IOException {
        return new Target(hashSlotFor(kind), FieldIndexEntry.fileKeyOf(hash), hash);
    }
}
