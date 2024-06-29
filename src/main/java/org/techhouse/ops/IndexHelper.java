package org.techhouse.ops;

import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonDouble;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.JsonUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    public static void createIndex(String dbName, String collName, String fieldName) {
        final var coll = cache.getWholeCollection(dbName, collName);
        final var entriesToBeIndexed = coll.values().stream().map(DbEntry::getData)
                .filter(jsonObject -> JsonUtils.hasInPath(jsonObject, fieldName))
                .collect(Collectors.groupingBy(jsonObject -> JsonUtils.getFromPath(jsonObject, fieldName)));
        final Map<Class<?>, List<FieldIndexEntry<?>>> indexes = entriesToBeIndexed.entrySet().stream().map(jsonElementListEntry -> {
            final var key = jsonElementListEntry.getKey();
            final var primitive = key.asJsonPrimitive();
            final var ids = jsonElementListEntry.getValue().stream()
                    .map(jsonObject -> jsonObject.get(Globals.PK_FIELD).asJsonString().getValue()).collect(Collectors.toSet());
            return switch (primitive) {
                case JsonDouble jsonDouble -> new FieldIndexEntry<>(dbName, collName, jsonDouble.getValue(), ids);
                case JsonString jsonString -> new FieldIndexEntry<>(dbName, collName, jsonString.getValue(), ids);
                case JsonBoolean jsonBoolean -> new FieldIndexEntry<>(dbName, collName, jsonBoolean.getValue(), ids);
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            };
        }).collect(Collectors.groupingBy(fieldIndexEntry -> fieldIndexEntry.getValue().getClass()));
        fs.writeIndexFile(dbName, collName, fieldName, indexes);
    }

    public static boolean dropIndex(String dbName, String collName, String fieldName) {
        return fs.dropIndex(dbName, collName, fieldName);
    }

    public static void bulkUpdateIndexes(String dbName, String collName, List<DbEntry> insertedEntries, List<DbEntry> updatedEntries)
            throws IOException, InterruptedException {
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
        if (element != JsonNull.INSTANCE && element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            switch (primitive) {
                case JsonDouble jsonDouble -> internalUpdateIndex(dbName, collName, fieldName, entry.get_id(), jsonDouble.getValue(), type, Double.class);
                case JsonString jsonString -> internalUpdateIndex(dbName, collName, fieldName, entry.get_id(), jsonString.getValue(), type, String.class);
                case JsonBoolean jsonBoolean -> internalUpdateIndex(dbName, collName, fieldName, entry.get_id(), jsonBoolean.getValue(), type, Boolean.class);
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            }
        }
    }

    private static <T> void internalUpdateIndex(String dbName, String collName, String fieldName,
                                                String entryId, T value, EventType type, Class<T> tClass)
            throws IOException {
        FieldIndexEntry<Boolean> toRemoveBoolean = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId, Boolean.class);
        FieldIndexEntry<Double> toRemoveDouble = null;
        FieldIndexEntry<String> toRemoveString = null;
        if (toRemoveBoolean == null) {
            toRemoveDouble = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId, Double.class);
            if (toRemoveDouble == null) {
                toRemoveString = getExistingFieldIndexEntry(dbName, collName, fieldName, entryId, String.class);
            }
        }
        if (type == EventType.CREATED || type == EventType.UPDATED) {
            FieldIndexEntry<T> found = findMatchingEntry(dbName, collName, fieldName, value, tClass);
            if (found != null) {
                final var ids = found.getIds();
                ids.add(entryId);
                updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveDouble, toRemoveString, found);
            } else {
                FieldIndexEntry<T> indexEntry = new FieldIndexEntry<>(dbName, collName, value, Set.of(entryId));
                updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveDouble, toRemoveString, indexEntry);
            }
        } else {
            updateFromFiles(dbName, collName, fieldName, toRemoveBoolean, toRemoveDouble, toRemoveString, null);
        }
    }

    private static <T> FieldIndexEntry<T> findMatchingEntry(String dbName, String collName, String fieldName,
                                                            T value, Class<T> tClass)
            throws IOException {
        final var indexEntries = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, tClass);
        if (indexEntries != null) {
            return indexEntries.stream()
                    .filter(indexEntry -> indexEntry.getValue().equals(value))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static <T> void updateFromFiles(String dbName, String collName, String fieldName,
                                            FieldIndexEntry<Boolean> toRemoveBoolean,
                                            FieldIndexEntry<Double> toRemoveDouble,
                                            FieldIndexEntry<String> toRemoveString,
                                            FieldIndexEntry<T> indexEntry) throws IOException {
        if (toRemoveBoolean != null) {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveBoolean);
        } else if (toRemoveDouble != null) {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveDouble);
        } else {
            fs.updateIndexFiles(dbName, collName, fieldName, indexEntry, toRemoveString);
        }
    }

    private static <T> FieldIndexEntry<T> getExistingFieldIndexEntry(
            String dbName, String collName, String fieldName, String entityId, Class<T> tClass
    ) throws IOException {
        final var fieldIndexEntry = cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, tClass);
        if (fieldIndexEntry != null) {
            return fieldIndexEntry.stream()
                    .filter(tFieldIndexEntry -> tFieldIndexEntry.getIds().contains(entityId))
                    .findFirst()
                    .map(tFieldIndexEntry -> {
                        tFieldIndexEntry.getIds().remove(entityId);
                        return tFieldIndexEntry;
                    })
                    .orElse(null);
        }
        return null;
    }
}
