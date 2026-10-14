package org.techhouse.ops.index;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.techhouse.cache.Cache;
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

    private final String dbName;
    private final String collName;
    private final String fieldName;
    private final Map<String, FieldIndexSlot> slots = new LinkedHashMap<>();
    private final Map<String, FieldIndexSlot.Located> idToEntry = new HashMap<>();
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
        final var current = idToEntry.get(id);
        if (current != null && target != null && current.alreadyHolds(target.slot(), target.key())) {
            return;
        }
        if (current != null) {
            current.removeEverywhere(id);
            idToEntry.remove(id);
        }
        if (target != null) {
            final var entry = target.slot().addId(id, target.key(), target.value());
            idToEntry.put(id, new FieldIndexSlot.Located(target.slot(), entry, null));
        }
    }

    public void flush() throws IOException {
        var firstWritten = false;
        for (final var slot : slots.values()) {
            if (slot.write() && !firstWritten) {
                firstWritten = true;
                faultInjector.at(FaultPoint.INDEX_BATCH_FLUSHED, Cache.getCollectionIdentifier(dbName, collName));
            }
        }
        for (final var slot : slots.values()) {
            slot.publish();
        }
    }

    private void loadEveryType() throws IOException {
        if (loaded) {
            return;
        }
        loaded = true;
        for (final var type : IndexKind.scalarIndexClasses()) {
            slotFor(type);
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            slotFor(kind);
        }
    }

    private FieldIndexSlot slotFor(Class<?> type) throws IOException {
        final var identifier = Cache.getIndexIdentifier(fieldName, type);
        final var existing = slots.get(identifier);
        return existing != null ? existing : register(FieldIndexSlot.scalar(dbName, collName, fieldName, type));
    }

    private FieldIndexSlot slotFor(IndexKind kind) throws IOException {
        final var identifier = Cache.getHashIndexIdentifier(fieldName, kind.label());
        final var existing = slots.get(identifier);
        return existing != null ? existing : register(FieldIndexSlot.hash(dbName, collName, fieldName, kind));
    }

    private FieldIndexSlot register(FieldIndexSlot slot) throws IOException {
        slots.put(slot.identifier(), slot);
        slot.load(idToEntry);
        return slot;
    }

    private Target targetOf(JsonBaseElement value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value != JsonNull.INSTANCE && value.isJsonPrimitive()) {
            final var primitive = value.asJsonPrimitive();
            return switch (primitive) {
                case JsonNumber number -> scalarTarget(Number.class, canonicalNumber(number.getValue()));
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

    private static Double canonicalNumber(Number number) {
        return number.doubleValue() + 0.0;
    }

    private Target scalarTarget(Class<?> type, Object value) throws IOException {
        return new Target(slotFor(type), FieldIndexEntry.fileKeyOf(value), value);
    }

    private Target customTarget(JsonCustom<?> custom) throws IOException {
        return scalarTarget(custom.getClass(), custom);
    }

    private Target hashTarget(IndexKind kind, String hash) throws IOException {
        return new Target(slotFor(kind), FieldIndexEntry.fileKeyOf(hash), hash);
    }
}
