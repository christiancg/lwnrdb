package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.JsonUtils;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    // The element-match (hashed) index families; the remaining IndexKind values are scalar.
    private static final IndexKind[] HASH_INDEX_KINDS = {IndexKind.OBJECT, IndexKind.ARRAY};

    public static void createIndex(String dbName, String collName, String fieldName) {
        final var coll = cache.getWholeCollection(dbName, collName);
        final var entriesToBeIndexed = coll.values().stream().map(DbEntry::getData)
                .filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName))
                .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, fieldName)));
        final Map<Class<?>, List<FieldIndexEntry<?>>> indexes = entriesToBeIndexed.entrySet().stream()
                .map(jsonElementListEntry -> {
                    final var key = jsonElementListEntry.getKey();
                    final var ids = jsonElementListEntry.getValue().stream()
                            .map(jsonObject -> jsonObject.get(Globals.PK_FIELD).asJsonString().getValue())
                            .collect(Collectors.toSet());
                    if (key.isJsonPrimitive()) {
                        final var primitive = key.asJsonPrimitive();
                        return switch (primitive) {
                            case JsonNumber jsonNumber ->
                                new FieldIndexEntry<>(dbName, collName, jsonNumber.getValue(), ids);
                            case JsonString jsonString -> {
                                if (jsonString.isJsonCustom()) {
                                    final var custom = CustomTypeFactory.getCustomTypeInstance(jsonString);
                                    yield new FieldIndexEntry<>(dbName, collName, custom, ids);
                                } else {
                                    yield new FieldIndexEntry<>(dbName, collName, jsonString.getValue(), ids);
                                }
                            }
                            case JsonBoolean jsonBoolean ->
                                new FieldIndexEntry<>(dbName, collName, jsonBoolean.getValue(), ids);
                            default -> throw new IllegalStateException("Unexpected value: " + primitive);
                        };
                    } else if (key.isJsonNull()) {
                        return new FieldIndexEntry<>(dbName, collName, JsonNull.INSTANCE, ids);
                    } else {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.groupingBy(fieldIndexEntry -> {
                    final var clazz = fieldIndexEntry.getValue().getClass();
                    if (Number.class.isAssignableFrom(clazz)) {
                        return Number.class;
                    }
                    return clazz;
                }));
        fs.writeIndexFile(dbName, collName, fieldName, indexes);
        writeHashIndexes(dbName, collName, fieldName, entriesToBeIndexed);
    }

    // Object- and array-valued documents are reduced to a single hex hash (element-match key) and
    // written to their own per-kind index files, keeping them fully separate from the scalar/custom
    // index built above. Equal objects/arrays already grouped together via their equals/hashCode.
    private static void writeHashIndexes(String dbName, String collName, String fieldName,
            Map<JsonBaseElement, List<JsonObject>> grouped) {
        final var objectEntries = new ArrayList<FieldIndexEntry<String>>();
        final var arrayEntries = new ArrayList<FieldIndexEntry<String>>();
        for (var groupedEntry : grouped.entrySet()) {
            final var key = groupedEntry.getKey();
            if (!key.isJsonObject() && !key.isJsonArray()) {
                continue;
            }
            final var ids = groupedEntry.getValue().stream()
                    .map(jsonObject -> jsonObject.get(Globals.PK_FIELD).asJsonString().getValue())
                    .collect(Collectors.toSet());
            final var hashEntry = new FieldIndexEntry<>(dbName, collName, JsonUtils.hashElement(key), ids);
            if (key.isJsonObject()) {
                objectEntries.add(hashEntry);
            } else {
                arrayEntries.add(hashEntry);
            }
        }
        fs.writeHashIndexFile(dbName, collName, fieldName, IndexKind.OBJECT, objectEntries);
        fs.writeHashIndexFile(dbName, collName, fieldName, IndexKind.ARRAY, arrayEntries);
    }

    public static boolean dropIndex(String dbName, String collName, String fieldName) {
        return fs.dropIndex(dbName, collName, fieldName);
    }

    // Read-side index usage for aggregation steps (GROUP_BY, JOIN, SORT, DISTINCT). Returns every
    // FieldIndexEntry across all value-type files for the field (value -> ids), or null when the
    // field is not indexed or no index entries could be loaded, in which case the caller falls back
    // to the existing scan path. Entries are gathered through the field-and-type-specific loader (the
    // same one the FILTER path uses) so only this field's indexes are returned. A field index access
    // is recorded so index-backed aggregations feed the LFU usage stats, exactly like indexed
    // filters do.
    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String dbName, String collName, String fieldName)
            throws IOException {
        if (!cache.hasIndex(dbName, collName, fieldName)) {
            return null;
        }
        final var combined = new ArrayList<FieldIndexEntry<?>>();
        addEntriesOfType(combined, dbName, collName, fieldName, Number.class);
        addEntriesOfType(combined, dbName, collName, fieldName, Boolean.class);
        addEntriesOfType(combined, dbName, collName, fieldName, String.class);
        for (var customType : CustomTypeFactory.getCustomTypes().values()) {
            addEntriesOfType(combined, dbName, collName, fieldName, customType);
        }
        if (combined.isEmpty()) {
            return null;
        }
        if (!Globals.ADMIN_DB_NAME.equals(dbName)) {
            cache.recordFieldIndexAccess(dbName, collName, fieldName);
        }
        return combined;
    }

    private static void addEntriesOfType(List<FieldIndexEntry<?>> combined, String dbName, String collName,
            String fieldName, Class<?> type) throws IOException {
        final var entries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type);
        if (entries != null) {
            combined.addAll(entries);
        }
    }

    // Converts a FieldIndexEntry value back to its wire element so it can be used as a group key,
    // distinct value, or join key. Numbers/strings/booleans are stored as raw Java types; custom
    // types and nulls are already JsonBaseElements and pass through unchanged.
    public static JsonBaseElement indexValueToElement(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case JsonBaseElement element -> element;
            case Number number -> numberToElement(number);
            case Boolean bool -> new JsonBoolean(bool);
            case String string -> new JsonString(string);
            default -> throw new IllegalStateException("Unexpected index value: " + value);
        };
    }

    // Field indexes persist numbers as doubles, but documents represent integral numbers as
    // integers; normalize so an index-derived number hashes and compares equal to the same value
    // read from a document (this matters for JOIN key lookups, which rely on hashCode).
    private static JsonBaseElement numberToElement(Number number) {
        final var asDouble = number.doubleValue();
        if (asDouble % 1.0 == 0 && asDouble >= Integer.MIN_VALUE && asDouble <= Integer.MAX_VALUE) {
            return new JsonNumber((int) asDouble);
        }
        return new JsonNumber(asDouble);
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries) throws IOException, InterruptedException {
        final var existingIndexes = cache.getIndexesForCollection(dbName, collName);
        for (var fieldName : existingIndexes) {
            rl.lockIndex(dbName, collName, fieldName);
            for (var insertedEntry : insertedEntries) {
                internalSelectIndexType(insertedEntry, dbName, collName, fieldName, EventType.CREATED);
            }
            for (var updatedEntry : updatedEntries) {
                internalSelectIndexType(updatedEntry, dbName, collName, fieldName, EventType.UPDATED);
            }
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }

    public static void updateIndexes(String dbName, String collName, DbEntry entry, EventType type)
            throws IOException, InterruptedException {
        final var existingIndexes = cache.getIndexesForCollection(dbName, collName);
        for (var fieldName : existingIndexes) {
            rl.lockIndex(dbName, collName, fieldName);
            internalSelectIndexType(entry, dbName, collName, fieldName, type);
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }

    private static void internalSelectIndexType(DbEntry entry, String dbName, String collName, String fieldName,
            EventType type) throws IOException {
        final var element = JsonUtils.getFromPath(entry.getData(), fieldName);
        final var entryId = entry.get_id();
        // The id may currently live in any family, and an update can change its value or even its kind
        // (object <-> array, scalar <-> object/array). Clearing it from the hash families up front in a
        // single, unconditional pass covers every shape change; the scalar families are then cleared on
        // the branches that don't already rewrite them in place.
        removeIdFromHashIndexes(dbName, collName, fieldName, entryId);
        if (element != JsonNull.INSTANCE && element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            switch (primitive) {
                case JsonNumber jsonNumber -> findExistingValuesAndUpdate(dbName, collName, fieldName, entryId, type,
                        jsonNumber.getValue(), Number.class);
                case JsonString jsonString -> {
                    if (jsonString.isJsonCustom()) {
                        final var custom = CustomTypeFactory.getCustomTypeInstance(jsonString);
                        findExistingValuesAndUpdate(dbName, collName, fieldName, entryId, type, custom, null);
                    } else {
                        findExistingValuesAndUpdate(dbName, collName, fieldName, entryId, type, jsonString.getValue(),
                                String.class);
                    }
                }
                case JsonBoolean jsonBoolean -> findExistingValuesAndUpdate(dbName, collName, fieldName, entryId, type,
                        jsonBoolean.getValue(), Boolean.class);
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            }
        } else if (element.isJsonObject()) {
            removeIdFromScalarIndexes(dbName, collName, fieldName, entryId);
            addToHashIndex(dbName, collName, fieldName, entryId, IndexKind.OBJECT, JsonUtils.hashElement(element),
                    type);
        } else if (element.isJsonArray()) {
            removeIdFromScalarIndexes(dbName, collName, fieldName, entryId);
            addToHashIndex(dbName, collName, fieldName, entryId, IndexKind.ARRAY, JsonUtils.hashElement(element), type);
        } else {
            // null or absent value (e.g. DELETE, or a field removed on update): the id must not linger
            // in any index family, and there is nothing to add.
            removeIdFromScalarIndexes(dbName, collName, fieldName, entryId);
        }
    }

    // Element-match counterpart of the scalar add: for a CREATE/UPDATE, adds the id to the matching
    // hash entry of the target kind (creating the entry when the value is new). The id has already
    // been cleared from every hash family by internalSelectIndexType, so this only adds.
    private static void addToHashIndex(String dbName, String collName, String fieldName, String entryId, IndexKind kind,
            String hash, EventType type) throws IOException {
        if (type != EventType.CREATED && type != EventType.UPDATED) {
            return;
        }
        final var entries = cache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind);
        final var found = entries == null
                ? null
                : entries.stream().filter(e -> e.getValue().equals(hash)).findFirst().orElse(null);
        if (found != null) {
            found.getIds().add(entryId);
            fs.updateHashIndexFiles(dbName, collName, fieldName, kind, found, null);
        } else {
            final var indexEntry = new FieldIndexEntry<>(dbName, collName, hash, new HashSet<>(Set.of(entryId)));
            fs.updateHashIndexFiles(dbName, collName, fieldName, kind, indexEntry, null);
        }
    }

    private static void removeIdFromHashIndexes(String dbName, String collName, String fieldName, String entryId)
            throws IOException {
        for (var kind : HASH_INDEX_KINDS) {
            final var entries = cache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind);
            if (entries != null) {
                for (var entry : entries) {
                    if (entry.getIds().remove(entryId)) {
                        fs.updateHashIndexFiles(dbName, collName, fieldName, kind, null, entry);
                        break;
                    }
                }
            }
        }
    }

    private static void removeIdFromScalarIndexes(String dbName, String collName, String fieldName, String entryId)
            throws IOException {
        removeScalarOfType(dbName, collName, fieldName, entryId, Boolean.class);
        removeScalarOfType(dbName, collName, fieldName, entryId, Number.class);
        removeScalarOfType(dbName, collName, fieldName, entryId, String.class);
        for (var customType : CustomTypeFactory.getCustomTypes().values()) {
            removeScalarOfType(dbName, collName, fieldName, entryId, customType);
        }
    }

    private static <T> void removeScalarOfType(String dbName, String collName, String fieldName, String entryId,
            Class<T> type) throws IOException {
        final var removed = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId, type);
        if (removed != null) {
            fs.updateIndexFiles(dbName, collName, fieldName, null, removed);
        }
    }

    private static <T> void findExistingValuesAndUpdate(String dbName, String collName, String fieldName,
            String entryId, EventType type, T value, Class<T> tClass) throws IOException {
        FieldIndexEntry<Boolean> toRemoveBoolean = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId,
                Boolean.class);
        FieldIndexEntry<Number> toRemoveNumber = null;
        FieldIndexEntry<String> toRemoveString = null;
        FieldIndexEntry<JsonCustom<?>> toRemoveJsonCustom = null;
        if (toRemoveBoolean == null) {
            toRemoveNumber = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId, Number.class);
            if (toRemoveNumber == null) {
                toRemoveString = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId, String.class);
                if (toRemoveString == null) {
                    final var customTypes = CustomTypeFactory.getCustomTypes();
                    final var customTypeValues = customTypes.values();
                    for (var customType : customTypeValues) {
                        final var foundJsonCustom = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId,
                                customType);
                        if (foundJsonCustom != null) {
                            //noinspection unchecked
                            toRemoveJsonCustom = (FieldIndexEntry<JsonCustom<?>>) foundJsonCustom;
                            break;
                        }
                    }
                }
            }
        }
        if (value instanceof JsonCustom<?> jsonCustom) {
            internalUpdateCustomIndex(dbName, collName, fieldName, entryId, jsonCustom, type, toRemoveBoolean,
                    toRemoveNumber, toRemoveString, toRemoveJsonCustom);
        } else {
            internalUpdateIndex(dbName, collName, fieldName, entryId, value, type, tClass, toRemoveBoolean,
                    toRemoveNumber, toRemoveString, toRemoveJsonCustom);
        }
    }

    private static void internalUpdateCustomIndex(String dbName, String collName, String fieldName, String entryId,
            JsonCustom<?> value, EventType type, FieldIndexEntry<Boolean> toRemoveBoolean,
            FieldIndexEntry<Number> toRemoveNumber, FieldIndexEntry<String> toRemoveString,
            FieldIndexEntry<JsonCustom<?>> toRemoveJsonCustom) throws IOException {
        if (type == EventType.CREATED || type == EventType.UPDATED) {
            FieldIndexEntry<?> found = findMatchingEntryFromCustomJson(dbName, collName, fieldName, value);
            if (found != null) {
                final var ids = found.getIds();
                ids.add(entryId);
                updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveNumber, toRemoveString,
                        toRemoveJsonCustom, found);
            } else {
                FieldIndexEntry<?> indexEntry = new FieldIndexEntry<>(dbName, collName, value, Set.of(entryId));
                updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveNumber, toRemoveString,
                        toRemoveJsonCustom, indexEntry);
            }
        } else {
            updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveNumber, toRemoveString,
                    toRemoveJsonCustom, null);
        }
    }

    private static <T> void internalUpdateIndex(String dbName, String collName, String fieldName, String entryId,
            T value, EventType type, Class<T> tClass, FieldIndexEntry<Boolean> toRemoveBoolean,
            FieldIndexEntry<Number> toRemoveNumber, FieldIndexEntry<String> toRemoveString,
            FieldIndexEntry<JsonCustom<?>> toRemoveJsonCustom) throws IOException {
        if (type == EventType.CREATED || type == EventType.UPDATED) {
            FieldIndexEntry<T> found = findMatchingEntry(dbName, collName, fieldName, value, tClass);
            if (found != null) {
                final var ids = found.getIds();
                ids.add(entryId);
                updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveNumber, toRemoveString,
                        toRemoveJsonCustom, found);
            } else {
                FieldIndexEntry<T> indexEntry = new FieldIndexEntry<>(dbName, collName, value, Set.of(entryId));
                updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveNumber, toRemoveString,
                        toRemoveJsonCustom, indexEntry);
            }
        } else {
            updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveNumber, toRemoveString,
                    toRemoveJsonCustom, null);
        }
    }

    private static FieldIndexEntry<?> findMatchingEntryFromCustomJson(String dbName, String collName, String fieldName,
            JsonCustom<?> value) throws IOException {
        final var indexEntries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, value.getClass());
        if (indexEntries != null) {
            //noinspection unchecked
            return indexEntries.stream()
                    .filter(indexEntry -> indexEntry.getValue().compare(value.getCustomValue()) == 0).findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static <T> FieldIndexEntry<T> findMatchingEntry(String dbName, String collName, String fieldName, T value,
            Class<T> tClass) throws IOException {
        final var indexEntries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, tClass);
        if (indexEntries != null) {
            return indexEntries.stream().filter(indexEntry -> indexEntry.getValue().equals(value)).findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static <T> void updateFromFiles(String dbName, String collName, String fieldName,
            FieldIndexEntry<Boolean> toRemoveBoolean, FieldIndexEntry<Number> toRemoveNumber,
            FieldIndexEntry<String> toRemoveString, FieldIndexEntry<JsonCustom<?>> toRemoveJsonCustom,
            FieldIndexEntry<T> indexEntry) throws IOException {
        if (toRemoveBoolean != null) {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveBoolean);
        } else if (toRemoveNumber != null) {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveNumber);
        } else if (toRemoveJsonCustom != null) {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveJsonCustom);
        } else {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveString);
        }
    }

    private static <T> FieldIndexEntry<T> getExistingFieldIndexEntry(String dbName, String collName, String fieldName,
            String entityId, Class<T> tClass) throws IOException {
        final var fieldIndexEntry = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, tClass);
        if (fieldIndexEntry != null) {
            return fieldIndexEntry.stream().filter(tFieldIndexEntry -> tFieldIndexEntry.getIds().contains(entityId))
                    .findFirst().map(tFieldIndexEntry -> {
                        tFieldIndexEntry.getIds().remove(entityId);
                        return tFieldIndexEntry;
                    }).orElse(null);
        }
        return null;
    }
}
