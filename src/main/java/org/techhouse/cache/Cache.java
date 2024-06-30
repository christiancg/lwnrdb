package org.techhouse.cache;

import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.*;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.SearchUtils;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Cache {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();
    private final Map<String, AdminDbEntry> databases = new ConcurrentHashMap<>();
    private final Map<String, AdminCollEntry> collections = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> databasesPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionsPkIndex = new ConcurrentHashMap<>();

    public void loadAdminData() throws IOException {
        final var pkIndexAdminDbEntries =
                fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME);
        final var pkIndexAdminDbEntriesMap = pkIndexAdminDbEntries.stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry));
        databasesPkIndex.putAll(pkIndexAdminDbEntriesMap);
        final var pkIndexAdminCollEntries =
                fs.readWholePkIndexFile(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        final var pkIndexAdminCollEntriesMap = pkIndexAdminCollEntries.stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry));
        collectionsPkIndex.putAll(pkIndexAdminCollEntriesMap);
        if (!pkIndexAdminDbEntriesMap.isEmpty()) {
            final var adminDatabasesColl =
                    fs.readWholeCollection(getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME));
            final var adminDatabasesCollMap = adminDatabasesColl.entrySet().stream()
                    .map(stringDbEntryEntry -> new AbstractMap.SimpleEntry<>(stringDbEntryEntry.getKey(),
                            AdminDbEntry.fromJsonObject(stringDbEntryEntry.getValue().getData())))
                    .collect(Collectors.toConcurrentMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
            databases.putAll(adminDatabasesCollMap);
        }
        if (!pkIndexAdminCollEntries.isEmpty()) {
            final var adminCollectionsColl =
                    fs.readWholeCollection(getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME));
            final var adminCollectionsCollMap = adminCollectionsColl.entrySet().stream()
                    .map(stringDbEntryEntry -> new AbstractMap.SimpleEntry<>(stringDbEntryEntry.getKey(),
                            AdminCollEntry.fromJsonObject(stringDbEntryEntry.getValue().getData())))
                    .collect(Collectors.toConcurrentMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
            collections.putAll(adminCollectionsCollMap);
        }
    }

    public static String getCollectionIdentifier(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    public static String getIndexIdentifier(String fieldName, Class<?> fieldType) {
        final var parts = fieldType.getName().split("\\.");
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + parts[parts.length - 1];
    }

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String dbName, String collName)
            throws IOException {
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
            throws IOException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var indexIdentifier = getIndexIdentifier(fieldName, indexType);
        var index = fieldIndexMap.get(collectionIdentifier);
        List<FieldIndexEntry<T>> indexEntries;
        if (index == null || index.keySet().stream().noneMatch(string ->
                string.contains(Globals.INDEX_FILE_NAME_SEPARATOR + fieldName + Globals.INDEX_FILE_NAME_SEPARATOR))
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
            throws IOException {
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
                    final var prim = firstElement.asJsonPrimitive();
                    final var listStream = arr.asList().stream();
                    switch (prim) {
                        case JsonString ignored -> {
                            final var stringIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, String.class);
                            if (stringIndex != null) {
                                final var strList = listStream.map(x -> x.asJsonString().getValue()).toList();
                                yield SearchUtils.findingInNotIn(stringIndex, operator.getFieldOperatorType(), strList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonDouble ignored -> {
                            final var doubleIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Double.class);
                            if (doubleIndex != null) {
                                final var doubleList = listStream.map(x -> x.asJsonDouble().getValue()).toList();
                                yield SearchUtils.findingInNotIn(doubleIndex, operator.getFieldOperatorType(), doubleList);
                            } else {
                                yield null;
                            }
                        }
                        case JsonBoolean ignored -> {
                            final var booleanIndex = getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class);
                            if (booleanIndex != null) {
                                final var booleanList = listStream.map(x -> x.asJsonBoolean().getValue()).toList();
                                yield SearchUtils.findingInNotIn(booleanIndex, operator.getFieldOperatorType(), booleanList);
                            } else {
                                yield null;
                            }
                        }
                        default -> {
                            yield null;
                        }
                    }
                } else {
                    yield null;
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    public void addEntryToCache(String dbName, String collName, DbEntry entry) {
        final var collId = getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.computeIfAbsent(collId, k -> new ConcurrentHashMap<>());
        coll.put(entry.get_id(), entry);
    }

    public void addEntriesToCache(String dbName, String collName, List<DbEntry> entries) {
        final var collId = getCollectionIdentifier(dbName, collName);
        var coll = collectionMap.computeIfAbsent(collId, k -> new ConcurrentHashMap<>());
        coll.putAll(entries.stream().collect(Collectors.toMap(DbEntry::get_id, o -> o)));
    }

    public DbEntry getById(String dbName, String collName, PkIndexEntry idxEntry) throws Exception {
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
        final var collData = collections.get(collectionIdentifier);
        final var wholeCollection = collectionMap.get(collectionIdentifier);
        if (wholeCollection == null || wholeCollection.isEmpty() || wholeCollection.size() < collData.getEntryCount()) {
            try {
                return fs.readWholeCollection(dbName, collName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return wholeCollection;
    }

    public PkIndexEntry getPkIndexAdminDbEntry(String dbName) {
        return databasesPkIndex.get(dbName);
    }

    public void putPkIndexAdminDbEntry(PkIndexEntry adminPkIndexAdminDbEntry) {
        databasesPkIndex.put(adminPkIndexAdminDbEntry.getValue(), adminPkIndexAdminDbEntry);
    }

    public AdminDbEntry getAdminDbEntry(String dbName) {
        return databases.get(dbName);
    }

    public PkIndexEntry getPkIndexAdminCollEntry(String collIdentifier) {
        return collectionsPkIndex.get(collIdentifier);
    }

    public void putPkIndexAdminCollEntry(PkIndexEntry adminPkIndexAdminCollEntry) {
        collectionsPkIndex.put(adminPkIndexAdminCollEntry.getValue(), adminPkIndexAdminCollEntry);
    }

    public AdminCollEntry getAdminCollectionEntry(String dbName, String collName) {
        return collections.get(getCollectionIdentifier(dbName, collName));
    }

    public void putAdminDbEntry(AdminDbEntry dbEntry, PkIndexEntry indexEntry) {
        databases.put(dbEntry.get_id(), dbEntry);
        databasesPkIndex.put(dbEntry.get_id(), indexEntry);
    }

    public void removeAdminDbEntry(String dbName) {
        databases.remove(dbName);
        databasesPkIndex.remove(dbName);
    }

    public void putAdminCollectionEntry(AdminCollEntry dbEntry, PkIndexEntry indexEntry) {
        final var collIdentifier = dbEntry.get_id();
        collections.put(collIdentifier, dbEntry);
        collectionsPkIndex.put(collIdentifier, indexEntry);
    }

    public void removeAdminCollEntry(String collIdentifier) {
        collections.remove(collIdentifier);
        collectionsPkIndex.remove(collIdentifier);
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
            throws IOException {
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

    public boolean hasIndex(String dbName, String collName, String fieldName) {
        final var fieldIndexes = fieldIndexMap.get(getCollectionIdentifier(dbName, collName));
        if (fieldIndexes != null) {
            return fieldIndexes.containsKey(fieldName);
        }
        return false;
    }

    public Set<String> getIndexesForCollection(String dbName, String collName) {
        final var collection = collections.get(getCollectionIdentifier(dbName, collName));
        return collection.getIndexes();
    }
}
