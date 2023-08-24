package org.techhouse.cache;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.SearchUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

public class Cache {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();

    public static String getCollectionIdentifier(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    public static String getIndexIdentifier(String fieldName, Class<?> fieldType) {
        final var parts = fieldType.getName().split("\\.");
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + parts[parts.length - 1];
    }

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName)
            throws ExecutionException, InterruptedException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        var primaryKeyIndex = pkIndexMap.get(collectionIdentifier);
        if (primaryKeyIndex == null) {
            primaryKeyIndex = fs.readWholePkIndexFile(dbName, collName);
            pkIndexMap.put(collectionIdentifier, primaryKeyIndex);
        }
        return primaryKeyIndex;
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
                                                                        String fieldName, Class<T> indexType)
            throws ExecutionException, InterruptedException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var indexIdentifier = getIndexIdentifier(fieldName, indexType);
        var index = fieldIndexMap.get(collectionIdentifier);
        List<FieldIndexEntry<T>> indexEntries;
        if (index == null || index.keySet().stream().noneMatch(string ->
                string.contains(FileSystem.INDEX_FILE_NAME_SEPARATOR + fieldName + FileSystem.INDEX_FILE_NAME_SEPARATOR))
        ) {
            indexEntries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType);
            if (indexEntries == null) {
                return null;
            } else if (index == null) {
                index = new ConcurrentHashMap<>();
            }
            index.put(indexIdentifier, (List) indexEntries);
            fieldIndexMap.put(collectionIdentifier, index);
        } else {
            indexEntries = (List) index.get(indexIdentifier);
        }
        return indexEntries;
    }

    public <T> Set<String> getIdsFromIndex(String dbName, String collName, String fieldName, FieldOperator operator, T value)
            throws ExecutionException, InterruptedException {
        return switch (value) {
            case Double d -> {
                final var doubleIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);
                if (doubleIndex != null) {
                    yield SearchUtils.findingByOperator(doubleIndex, operator.getFieldOperatorType(), d);
                } else {
                    yield null;
                }
            }
            case Boolean b -> {
                final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class);
                if (booleanIndex != null) {
                    yield SearchUtils.findingByOperator(booleanIndex, operator.getFieldOperatorType(), b);
                } else {
                    yield null;
                }
            }
            case String s -> {
                final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class);
                if (stringIndex != null) {
                    yield SearchUtils.findingByOperator(stringIndex, operator.getFieldOperatorType(), s);
                } else {
                    yield null;
                }
            }
            case JsonArray arr -> {
                final var firstElement = arr.get(0);
                if (firstElement.isJsonPrimitive()) {
                    final var prim = firstElement.getAsJsonPrimitive();
                    final var listStream = arr.asList().stream();
                    if (prim.isString()) {
                        final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class);
                        if (stringIndex != null) {
                            final var strList = listStream.map(JsonElement::getAsString).toList();
                            yield SearchUtils.findingInNotIn(stringIndex, operator.getFieldOperatorType(), strList);
                        } else {
                            yield null;
                        }
                    } else if (prim.isNumber()) {
                        final var doubleIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);
                        if (doubleIndex != null) {
                            final var doubleList = listStream.map(JsonElement::getAsDouble).toList();
                            yield SearchUtils.findingInNotIn(doubleIndex, operator.getFieldOperatorType(), doubleList);
                        } else {
                            yield null;
                        }
                    } else if (prim.isBoolean()) {
                        final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class);
                        if (booleanIndex != null) {
                            final var booleanList = listStream.map(JsonElement::getAsBoolean).toList();
                            yield SearchUtils.findingInNotIn(booleanIndex, operator.getFieldOperatorType(), booleanList);
                        } else {
                            yield null;
                        }
                    }
                    yield null;
                } else {
                    yield null;
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        final var collId = getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.get(collId);
        if (coll == null) {
            coll = new ConcurrentHashMap<>();
            collectionMap.put(collId, coll);
        }
        coll.put(entry.get_id(), entry);
    }

    public DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws ExecutionException, InterruptedException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var coll = collectionMap.computeIfAbsent(collectionIdentifier, k -> new ConcurrentHashMap<>());
        final var pk = idxEntry.getValue();
        var entry = coll.get(pk);
        if (entry == null) {
            entry = fs.getById(idxEntry);
            coll.put(pk, entry);
        }
        return entry;
    }

    public Map<String, DbEntry> getWholeCollection(String dbName, String collName) {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        return collectionMap.computeIfAbsent(collectionIdentifier, k -> {
            try {
                return fs.readWholeCollection(dbName, collName);
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void evictEntry(String dbName, String collName, String pk) {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var coll = collectionMap.get(collectionIdentifier);
        if (coll != null) {
            coll.remove(pk);
        }
    }

    public void evictDatabase(String dbName) {
        final var toRemove = collectionMap.keySet().stream().filter(s -> s.startsWith(dbName)).toList();
        for (var entryKeyToRemove : toRemove) {
            pkIndexMap.remove(entryKeyToRemove);
            collectionMap.remove(entryKeyToRemove);
        }
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = getCollectionIdentifier(dbName, collName);
        pkIndexMap.remove(collIdentifier);
        collectionMap.remove(collIdentifier);
    }

    public Stream<JsonObject> initializeStreamIfNecessary(Stream<JsonObject> resultStream, String dbName, String collName)
            throws ExecutionException, InterruptedException {
        if (resultStream != null) {
            return resultStream;
        } else {
            final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
            var coll = collectionMap.get(collectionIdentifier);
            if (coll == null) {
                coll = fs.readWholeCollection(collectionIdentifier);
                collectionMap.put(collectionIdentifier, coll);
            }
            return coll.values().stream().map(DbEntry::getData);
        }
    }
}
