package org.techhouse.cache;

import com.google.gson.JsonObject;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

import java.util.List;
import java.util.Map;
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

    public static String getIndexIdentifier(String fieldName, String fieldType) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + fieldType;
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

    public List<FieldIndexEntry<?>> getFieldIndexAndLoadIfNecessary(String dbName, String collName,
                                                                                 String fieldName, String indexType)
            throws ExecutionException, InterruptedException {
        final var collectionIdentifier = getCollectionIdentifier(dbName, collName);
        final var indexIdentifier = getIndexIdentifier(fieldName, indexType);
        var index = fieldIndexMap.get(collectionIdentifier);
        List<FieldIndexEntry<?>> indexEntries;
        if (index == null || index.keySet().stream().noneMatch(string ->
                string.contains(FileSystem.INDEX_FILE_NAME_SEPARATOR + fieldName + FileSystem.INDEX_FILE_NAME_SEPARATOR))
        ) {
            indexEntries = fs.readWholeFieldIndexFiles(dbName, collName, fieldName, indexType);
            if (index == null) {
                index = new ConcurrentHashMap<>();
            }
            index.put(indexIdentifier, indexEntries);
            fieldIndexMap.put(collectionIdentifier, index);
        } else {
            indexEntries = index.get(indexIdentifier);
        }
        return indexEntries;
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
