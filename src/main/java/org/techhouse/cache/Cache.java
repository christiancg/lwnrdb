package org.techhouse.cache;

import com.google.gson.JsonObject;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

public class Cache {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, Map<String, List<IndexEntry>>> indexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DbEntry>> collectionMap = new ConcurrentHashMap<>();

    public static String getCollectionIdentifier(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    public List<IndexEntry> getIdIndexAndLoadIfNecessary(String dbName, String collName) throws ExecutionException, InterruptedException {
        final var fieldMapName = getCollectionIdentifier(dbName, collName);
        var indexedFields = indexMap.get(fieldMapName);
        List<IndexEntry> primaryKeyIndex;
        if (indexedFields != null) {
            primaryKeyIndex = indexedFields.get(Globals.PK_FIELD);
            if (primaryKeyIndex == null) {
                primaryKeyIndex = fs.readWholeIndexFile(dbName, collName, Globals.PK_FIELD);
                indexedFields.put(Globals.PK_FIELD, primaryKeyIndex);
            }
        } else {
            primaryKeyIndex = fs.readWholeIndexFile(dbName, collName, Globals.PK_FIELD);
            indexedFields = new ConcurrentHashMap<>();
            indexedFields.put(Globals.PK_FIELD, primaryKeyIndex);
            indexMap.put(fieldMapName, indexedFields);
        }
        return primaryKeyIndex;
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

    public DbEntry getById(String dbName, String collName, IndexEntry idxEntry) throws ExecutionException, InterruptedException {
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
            indexMap.remove(entryKeyToRemove);
            collectionMap.remove(entryKeyToRemove);
        }
    }

    public void evictCollection(String dbName, String collName) {
        final var collIdentifier = getCollectionIdentifier(dbName, collName);
        indexMap.remove(collIdentifier);
        collectionMap.remove(collIdentifier);
    }

    public Stream<JsonObject> initializeStreamIfNecessary(Stream<JsonObject> resultStream, String dbName,
                                                                 String collName) throws ExecutionException, InterruptedException {
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
