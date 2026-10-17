package org.techhouse.fs;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public final class LegacyIndexMaintenance {
    private final FileSystem fs;
    private final LegacyIndexLines lines;
    private final String dbName;
    private final String collName;
    private final String fieldName;

    public LegacyIndexMaintenance(FileSystem fs, String dbName, String collName, String fieldName) {
        this.fs = fs;
        this.lines = LegacyIndexLines.of(fs);
        this.dbName = dbName;
        this.collName = collName;
        this.fieldName = fieldName;
    }

    public void apply(String id, DbEntry doc) throws IOException {
        if (doc != null) {
            selectIndexType(doc);
        } else {
            removeIdFromScalarIndexes(id);
            removeIdFromHashIndexes(id);
        }
    }

    private void selectIndexType(DbEntry entry) throws IOException {
        final var element = JsonUtils.getFromPath(entry.getData(), fieldName);
        final var entryId = entry.get_id();
        removeIdFromHashIndexes(entryId);
        if (element != JsonNull.INSTANCE && element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            switch (primitive) {
                case JsonNumber jsonNumber -> findExistingValuesAndUpdate(entryId, jsonNumber.getValue(), Number.class);
                case JsonString jsonString -> {
                    if (jsonString.isJsonCustom()) {
                        findExistingValuesAndUpdate(entryId, CustomTypeFactory.getCustomTypeInstance(jsonString), null);
                    } else {
                        findExistingValuesAndUpdate(entryId, jsonString.getValue(), String.class);
                    }
                }
                case JsonBoolean jsonBoolean ->
                    findExistingValuesAndUpdate(entryId, jsonBoolean.getValue(), Boolean.class);
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            }
        } else if (element.isJsonObject()) {
            removeIdFromScalarIndexes(entryId);
            addToHashIndex(entryId, IndexKind.OBJECT, JsonUtils.hashElement(element));
        } else if (element.isJsonArray()) {
            removeIdFromScalarIndexes(entryId);
            addToHashIndex(entryId, IndexKind.ARRAY, JsonUtils.hashElement(element));
        } else {
            removeIdFromScalarIndexes(entryId);
        }
    }

    private void addToHashIndex(String entryId, IndexKind kind, String hash) throws IOException {
        final var entries = fs.readWholeHashIndexFile(dbName, collName, fieldName, kind);
        final var found = entries == null
                ? null
                : entries.stream().filter(e -> e.getValue().equals(hash)).findFirst().orElse(null);
        if (found != null) {
            found.getIds().add(entryId);
            lines.updateHashIndexFiles(dbName, collName, fieldName, kind, found, null);
        } else {
            final var indexEntry = new FieldIndexEntry<>(dbName, collName, hash, new HashSet<>(Set.of(entryId)));
            lines.updateHashIndexFiles(dbName, collName, fieldName, kind, indexEntry, null);
        }
    }

    private void removeIdFromHashIndexes(String entryId) throws IOException {
        for (var kind : IndexKind.HASH_KINDS) {
            final var entries = fs.readWholeHashIndexFile(dbName, collName, fieldName, kind);
            if (entries != null) {
                for (var entry : entries) {
                    if (entry.getIds().remove(entryId)) {
                        lines.updateHashIndexFiles(dbName, collName, fieldName, kind, null, entry);
                        break;
                    }
                }
            }
        }
    }

    private void removeIdFromScalarIndexes(String entryId) throws IOException {
        for (var type : List.of(Boolean.class, Number.class, String.class)) {
            removeScalarOfType(entryId, type);
        }
        for (var customType : CustomTypeFactory.getCustomTypes().values()) {
            removeScalarOfType(entryId, customType);
        }
    }

    private <T> void removeScalarOfType(String entryId, Class<T> type) throws IOException {
        final var removed = existingEntry(entryId, type);
        if (removed != null) {
            lines.updateIndexFiles(dbName, collName, fieldName, null, removed);
        }
    }

    private <T> void findExistingValuesAndUpdate(String entryId, T value, Class<T> tClass) throws IOException {
        FieldIndexEntry<?> toRemove = existingEntry(entryId, Boolean.class);
        if (toRemove == null) {
            toRemove = existingEntry(entryId, Number.class);
        }
        if (toRemove == null) {
            toRemove = existingEntry(entryId, String.class);
        }
        if (toRemove == null) {
            for (var customType : CustomTypeFactory.getCustomTypes().values()) {
                toRemove = existingEntry(entryId, customType);
                if (toRemove != null) {
                    break;
                }
            }
        }
        final FieldIndexEntry<?> found = value instanceof JsonCustom<?> custom
                ? matchingCustom(custom)
                : matching(value, tClass);
        if (found != null) {
            found.getIds().add(entryId);
            lines.updateIndexFiles(dbName, collName, fieldName, found, toRemove);
        } else {
            lines.updateIndexFiles(dbName, collName, fieldName,
                    new FieldIndexEntry<>(dbName, collName, value, new HashSet<>(Set.of(entryId))), toRemove);
        }
    }

    private FieldIndexEntry<?> matchingCustom(JsonCustom<?> value) throws IOException {
        final var indexEntries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, value.getClass());
        if (indexEntries == null) {
            return null;
        }
        return indexEntries.stream().filter(indexEntry -> indexEntry.getValue().getValue().equals(value.getValue()))
                .findFirst().orElse(null);
    }

    private <T> FieldIndexEntry<T> matching(T value, Class<T> tClass) throws IOException {
        final var indexEntries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, tClass);
        if (indexEntries == null) {
            return null;
        }
        return indexEntries.stream()
                .filter(indexEntry -> FieldIndexEntry.sameIndexedValue(indexEntry.getValue(), value)).findFirst()
                .orElse(null);
    }

    private <T> FieldIndexEntry<T> existingEntry(String entityId, Class<T> tClass) throws IOException {
        final var entries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, tClass);
        if (entries == null) {
            return null;
        }
        return entries.stream().filter(entry -> entry.getIds().contains(entityId)).findFirst().map(entry -> {
            entry.getIds().remove(entityId);
            return entry;
        }).orElse(null);
    }
}
