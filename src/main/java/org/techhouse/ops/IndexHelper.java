package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.ops.index.IndexValueCodec;
import org.techhouse.utils.JsonUtils;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    // The element-match (hashed) index families; the remaining IndexKind values are scalar.
    private static final IndexKind[] HASH_INDEX_KINDS = {IndexKind.OBJECT, IndexKind.ARRAY};

    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String dbName, String collName, String fieldName)
            throws IOException {
        return IndexEntryReader.getIndexEntriesForField(dbName, collName, fieldName);
    }

    public static Set<String> getMatchingIdsForJoin(String dbName, String collName, String fieldName,
            Set<JsonBaseElement> localValues) throws IOException {
        return IndexEntryReader.getMatchingIdsForJoin(dbName, collName, fieldName, localValues);
    }

    public static JsonBaseElement indexValueToElement(Object value) {
        return IndexValueCodec.indexValueToElement(value);
    }

    public static Object elementToLookupValue(JsonBaseElement element) {
        return IndexValueCodec.elementToLookupValue(element);
    }

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
        cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
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
        final var result = fs.dropIndex(dbName, collName, fieldName);
        cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
        return result;
    }

    // Bulk counterpart of updateIndexes: re-reads the current state of every affected id once, then
    // applies it to each field index. Same order-independent convergence as updateIndexes.
    public static void bulkUpdateIndexes(String dbName, String collName, List<String> ids)
            throws IOException, InterruptedException {
        final var existingIndexes = cache.getIndexesForCollection(dbName, collName);
        if (existingIndexes.isEmpty() || ids.isEmpty()) {
            return;
        }
        rl.lockRead(dbName, collName);
        try {
            final var byId = new HashMap<String, DbEntry>();
            for (var doc : cache.getEntriesByIds(dbName, collName, new HashSet<>(ids))) {
                byId.put(doc.get_id(), doc);
            }
            for (var fieldName : existingIndexes) {
                rl.lockIndex(dbName, collName, fieldName);
                try {
                    for (var id : ids) {
                        applyCurrentState(dbName, collName, fieldName, id, byId.get(id));
                    }
                    cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
                } finally {
                    rl.releaseIndex(dbName, collName, fieldName);
                }
            }
        } finally {
            rl.releaseRead(dbName, collName);
        }
    }

    // Index maintenance for a single committed write. Background events for the same id may be
    // processed out of order by the worker pool, so rather than trusting the (possibly stale) event
    // snapshot we re-read the CURRENT committed document by id and index that — whichever event runs
    // last observes the final committed state, so the index converges regardless of processing order.
    // The collection read lock gives a stable view of the document's existence and value (it blocks a
    // concurrent commit until maintenance finishes; that commit then converges via its own event).
    public static void updateIndexes(String dbName, String collName, String id)
            throws IOException, InterruptedException {
        final var existingIndexes = cache.getIndexesForCollection(dbName, collName);
        if (existingIndexes.isEmpty()) {
            return;
        }
        rl.lockRead(dbName, collName);
        try {
            final var current = cache.getEntriesByIds(dbName, collName, Set.of(id));
            final var doc = current.isEmpty() ? null : current.getFirst();
            for (var fieldName : existingIndexes) {
                rl.lockIndex(dbName, collName, fieldName);
                try {
                    applyCurrentState(dbName, collName, fieldName, id, doc);
                    // The cached index now diverges from the rewritten .idx files; drop it so the next
                    // read reloads the up-to-date index from disk.
                    cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
                } finally {
                    rl.releaseIndex(dbName, collName, fieldName);
                }
            }
        } finally {
            rl.releaseRead(dbName, collName);
        }
    }

    // Applies the current committed state of a document to one field index: upsert when the document
    // still exists (internalSelectIndexType clears the id from every family, then adds it to the
    // current value), or remove the id from every family when it no longer exists (deleted). Must be
    // called holding the field index write lock.
    private static void applyCurrentState(String dbName, String collName, String fieldName, String id, DbEntry doc)
            throws IOException {
        if (doc != null) {
            internalSelectIndexType(doc, dbName, collName, fieldName);
        } else {
            removeIdFromScalarIndexes(dbName, collName, fieldName, id);
            removeIdFromHashIndexes(dbName, collName, fieldName, id);
        }
    }

    private static void internalSelectIndexType(DbEntry entry, String dbName, String collName, String fieldName)
            throws IOException {
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
                case JsonNumber jsonNumber -> findExistingValuesAndUpdate(dbName, collName, fieldName, entryId,
                        jsonNumber.getValue(), Number.class);
                case JsonString jsonString -> {
                    if (jsonString.isJsonCustom()) {
                        final var custom = CustomTypeFactory.getCustomTypeInstance(jsonString);
                        findExistingValuesAndUpdate(dbName, collName, fieldName, entryId, custom, null);
                    } else {
                        findExistingValuesAndUpdate(dbName, collName, fieldName, entryId, jsonString.getValue(),
                                String.class);
                    }
                }
                case JsonBoolean jsonBoolean -> findExistingValuesAndUpdate(dbName, collName, fieldName, entryId,
                        jsonBoolean.getValue(), Boolean.class);
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            }
        } else if (element.isJsonObject()) {
            removeIdFromScalarIndexes(dbName, collName, fieldName, entryId);
            addToHashIndex(dbName, collName, fieldName, entryId, IndexKind.OBJECT, JsonUtils.hashElement(element));
        } else if (element.isJsonArray()) {
            removeIdFromScalarIndexes(dbName, collName, fieldName, entryId);
            addToHashIndex(dbName, collName, fieldName, entryId, IndexKind.ARRAY, JsonUtils.hashElement(element));
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
            String hash) throws IOException {
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
            String entryId, T value, Class<T> tClass) throws IOException {
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
            internalUpdateCustomIndex(dbName, collName, fieldName, entryId, jsonCustom, toRemoveBoolean, toRemoveNumber,
                    toRemoveString, toRemoveJsonCustom);
        } else {
            internalUpdateIndex(dbName, collName, fieldName, entryId, value, tClass, toRemoveBoolean, toRemoveNumber,
                    toRemoveString, toRemoveJsonCustom);
        }
    }

    private static void internalUpdateCustomIndex(String dbName, String collName, String fieldName, String entryId,
            JsonCustom<?> value, FieldIndexEntry<Boolean> toRemoveBoolean, FieldIndexEntry<Number> toRemoveNumber,
            FieldIndexEntry<String> toRemoveString, FieldIndexEntry<JsonCustom<?>> toRemoveJsonCustom)
            throws IOException {
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
    }

    private static <T> void internalUpdateIndex(String dbName, String collName, String fieldName, String entryId,
            T value, Class<T> tClass, FieldIndexEntry<Boolean> toRemoveBoolean, FieldIndexEntry<Number> toRemoveNumber,
            FieldIndexEntry<String> toRemoveString, FieldIndexEntry<JsonCustom<?>> toRemoveJsonCustom)
            throws IOException {
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
