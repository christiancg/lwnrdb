package org.techhouse.ops;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.*;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.*;
import org.techhouse.ops.resp.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class OperationProcessor {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    public OperationResponse processMessage(OperationRequest operationRequest) {
        return switch (operationRequest.getType()) {
            case BULK_SAVE -> processBulkSaveOperation((BulkSaveRequest) operationRequest);
            case SAVE -> processSaveOperation((SaveRequest) operationRequest);
            case FIND_BY_ID -> processFindByIdOperation((FindByIdRequest) operationRequest);
            case AGGREGATE -> processAggregateOperation((AggregateRequest) operationRequest);
            case DELETE -> processDeleteOperation((DeleteRequest) operationRequest);
            case CREATE_DATABASE -> processCreateDatabaseOperation((CreateDatabaseRequest) operationRequest);
            case DROP_DATABASE -> processDropDatabaseOperation((DropDatabaseRequest) operationRequest);
            case CREATE_COLLECTION -> processCreateCollectionOperation((CreateCollectionRequest) operationRequest);
            case DROP_COLLECTION -> processDropCollectionOperation((DropCollectionRequest) operationRequest);
            case CREATE_INDEX -> processCreateIndex((CreateIndexRequest) operationRequest);
            case DROP_INDEX -> processDropIndex((DropIndexRequest) operationRequest);
            case CLOSE_CONNECTION -> new CloseConnectionResponse();
        };
    }

    private BulkSaveResponse processBulkSaveOperation(BulkSaveRequest bulkSaveRequest) {
        // TODO: validate that there shouldn't be more than 1 object with the same id
        final var dbName = bulkSaveRequest.getDatabaseName();
        final var collName = bulkSaveRequest.getCollectionName();
        final var entries = new ArrayList<DbEntry>();
        for (var entry : bulkSaveRequest.getObjects()) {
            entries.add(DbEntry.fromJsonObject(dbName, collName, entry));
        }
        try {
            locks.lock(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var indexedDbEntriesToUpdate = new ArrayList<IndexedDbEntry>();
            for (var i : entries) {
                final var data = i.getData();
                if (data.has(Globals.PK_FIELD)) {
                    final var id = data.get(Globals.PK_FIELD).asJsonString().getValue();
                    i.set_id(id);
                    final var foundIndexEntry = Collections.binarySearch(primaryKeyIndex, id);
                    if (foundIndexEntry > 0) {
                        final var foundIndex = primaryKeyIndex.get(foundIndexEntry);
                        final var indexedDbEntry = new IndexedDbEntry();
                        indexedDbEntry.setIndex(foundIndex);
                        indexedDbEntry.setDatabaseName(dbName);
                        indexedDbEntry.setCollectionName(collName);
                        indexedDbEntry.set_id(id);
                        indexedDbEntry.setData(data);
                        indexedDbEntriesToUpdate.add(indexedDbEntry);
                    }
                }
            }
            final List<IndexedDbEntry> updatedIndexEntries = new ArrayList<>();
            if (!indexedDbEntriesToUpdate.isEmpty()) {
                updatedIndexEntries.addAll(fs.bulkUpdateFromCollection(dbName, collName, indexedDbEntriesToUpdate));
                primaryKeyIndex.removeIf(pkIndexEntry -> updatedIndexEntries.stream().anyMatch(pkIndexEntry1 -> pkIndexEntry1.get_id().equals(pkIndexEntry.getValue())));
            }
            primaryKeyIndex.addAll(updatedIndexEntries.stream().map(IndexedDbEntry::getIndex).toList());
            final var entriesToInsert = entries.stream()
                    .filter(dbEntry ->
                            indexedDbEntriesToUpdate.stream()
                                    .noneMatch(indexedDbEntry -> indexedDbEntry.get_id().equals(dbEntry.get_id())))
                    .toList();
            List<IndexedDbEntry> insertedIndexEntries = new ArrayList<>();
            if (!entriesToInsert.isEmpty()) {
                insertedIndexEntries = fs.bulkInsertIntoCollection(dbName, collName, entriesToInsert);
            }
            primaryKeyIndex.addAll(insertedIndexEntries.stream().map(IndexedDbEntry::getIndex).toList());
            primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
            final var updatedDbEntries = updatedIndexEntries.stream().map(IndexedDbEntry::toDbEntry).toList();
            cache.addEntriesToCache(dbName, collName, updatedDbEntries);
            final var insertedDbEntries = insertedIndexEntries.stream().map(IndexedDbEntry::toDbEntry).toList();
            cache.addEntriesToCache(dbName, collName, insertedDbEntries);
            taskManager.submitBackgroundTask(new BulkEntityEvent(dbName, collName, insertedDbEntries, updatedDbEntries));
            final var updatedIds = updatedDbEntries.stream().map(DbEntry::get_id).toList();
            final var insertedIds = insertedDbEntries.stream().map(DbEntry::get_id).toList();
            return new BulkSaveResponse(OperationStatus.OK, "Successfully saved entries", insertedIds, updatedIds);
        } catch (Exception exception) {
            return new BulkSaveResponse(OperationStatus.ERROR, "Error while saving entries: " + exception.getMessage(), null, null);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private SaveResponse processSaveOperation(SaveRequest saveRequest) {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        try {
            locks.lock(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            var foundIndexEntry = -1;
            if (saveRequest.get_id() != null) {
                foundIndexEntry = Collections.binarySearch(primaryKeyIndex, saveRequest.get_id());
            }
            var eventType = EventType.CREATED;
            PkIndexEntry savedPkIndexEntry;
            if (foundIndexEntry >= 0) {
                final var idxEntry = primaryKeyIndex.get(foundIndexEntry);
                savedPkIndexEntry = fs.updateFromCollection(entry, idxEntry);
                primaryKeyIndex.remove(idxEntry);
                eventType = EventType.UPDATED;
            } else {
                savedPkIndexEntry = fs.insertIntoCollection(entry);
            }
            primaryKeyIndex.add(savedPkIndexEntry);
            primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
            cache.addEntryToCache(dbName, collName, entry);
            taskManager.submitBackgroundTask(new EntityEvent(eventType, dbName, collName, entry));
            return new SaveResponse(OperationStatus.OK, "Successfully saved", savedPkIndexEntry.getValue());
        } catch (Exception exception) {
            return new SaveResponse(OperationStatus.ERROR, "Error while saving entry: " + exception.getMessage(), null);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private FindByIdResponse processFindByIdOperation(FindByIdRequest findbyIdRequest) {
        final var dbName = findbyIdRequest.getDatabaseName();
        final var collName = findbyIdRequest.getCollectionName();
        final var id = findbyIdRequest.get_id();
        try {
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = Collections.binarySearch(primaryKeyIndex, id);
            if (foundIndexEntry >= 0) {
                final var primaryKeyIndexEntry = primaryKeyIndex.get(foundIndexEntry);
                final var entry = cache.getById(dbName, collName, primaryKeyIndexEntry);
                return new FindByIdResponse(OperationStatus.OK, "Ok", entry.getData());
            } else {
                return new FindByIdResponse(OperationStatus.NOT_FOUND, "Not found", null);
            }
        } catch (Exception exception) {
            return new FindByIdResponse(OperationStatus.ERROR, "Error while retrieving entry: " + exception.getMessage(), null);
        }
    }

    private AggregateResponse processAggregateOperation(AggregateRequest aggregateRequest) {
        try {
            final var results = AggregationOperationHelper.processAggregation(aggregateRequest);
            return results.isEmpty() ?
                    new AggregateResponse(OperationStatus.NOT_FOUND, "No results", null) :
                    new AggregateResponse(OperationStatus.OK, "Ok", results);
        } catch (Exception e) {
            return new AggregateResponse(OperationStatus.ERROR, "An error occurred while processing the aggregation: " + e.getMessage(), null);
        }
    }

    private DeleteResponse processDeleteOperation(DeleteRequest deleteRequest) {
        final var dbName = deleteRequest.getDatabaseName();
        final var collName = deleteRequest.getCollectionName();
        try {
            locks.lock(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = primaryKeyIndex.stream().filter(pkIndexEntry -> pkIndexEntry.getValue().equals(deleteRequest.get_id())).findFirst();
            if (foundIndexEntry.isPresent()) {
                final var idxEntry = foundIndexEntry.get();
                final var entryToBeDeleted = cache.getById(dbName, collName, idxEntry);
                fs.deleteFromCollection(idxEntry);
                primaryKeyIndex.remove(idxEntry);
                primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
                cache.evictEntry(dbName, collName, entryToBeDeleted.get_id());
                taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, entryToBeDeleted));
                return new DeleteResponse(OperationStatus.OK, "Entry with id " + deleteRequest.get_id() + " deleted successfully");
            } else {
                return new DeleteResponse(OperationStatus.NOT_FOUND, "Entry with id " + deleteRequest.get_id() + " not found");
            }
        } catch (Exception exception) {
            return new DeleteResponse(OperationStatus.ERROR, "Error while deleting entry with id: " + deleteRequest.get_id() + ". Error message: " + exception.getMessage());
        } finally {
            locks.release(dbName, collName);
        }
    }

    private CreateDatabaseResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest) {
        try {
            final var dbName = createDatabaseRequest.getDatabaseName();
            final var result = fs.createDatabaseFolder(dbName);
            if (result) {
                taskManager.submitBackgroundTask(new DatabaseEvent(EventType.CREATED, dbName));
                return new CreateDatabaseResponse(OperationStatus.OK, "Database created successfully");
            }
            return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
        } catch (Exception exception) {
            return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
        }
    }

    private DropDatabaseResponse processDropDatabaseOperation(DropDatabaseRequest dropDatabaseRequest) {
        try {
            final var dbName = dropDatabaseRequest.getDatabaseName();
            final var result = fs.deleteDatabase(dbName);
            if (result) {
                cache.evictDatabase(dbName);
                taskManager.submitBackgroundTask(new DatabaseEvent(EventType.DELETED, dbName));
                return new DropDatabaseResponse(OperationStatus.OK, "Database dropped successfully");
            }
            return new DropDatabaseResponse(OperationStatus.ERROR, "Error while dropping database");
        } catch (Exception exception) {
            return new DropDatabaseResponse(OperationStatus.ERROR, "Error while dropping database");
        }
    }

    private CreateCollectionResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        try {
            final var dbName = createCollectionRequest.getDatabaseName();
            final var collName = createCollectionRequest.getCollectionName();
            final var result = fs.createCollectionFile(dbName, collName);
            if (result) {
                taskManager.submitBackgroundTask(new CollectionEvent(EventType.CREATED, dbName, collName));
                return new CreateCollectionResponse(OperationStatus.OK, "Collection created successfully");
            }
            return new CreateCollectionResponse(OperationStatus.ERROR, "Error while creating collection");
        } catch (Exception e) {
            return new CreateCollectionResponse(OperationStatus.ERROR, "Error while creating collection: " + e.getMessage());
        }
    }

    private DropCollectionResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest) {
        final var dbName = dropCollectionRequest.getDatabaseName();
        final var collName = dropCollectionRequest.getCollectionName();
        try {
            locks.lock(dbName, collName);
            final var result = fs.deleteCollectionFiles(dbName, collName);
            if (result) {
                cache.evictCollection(dbName, collName);
                taskManager.submitBackgroundTask(new CollectionEvent(EventType.DELETED, dbName, collName));
                return new DropCollectionResponse(OperationStatus.OK, "Collection dropped successfully");
            }
            locks.release(dbName, collName);
            locks.removeLock(dbName, collName);
            return new DropCollectionResponse(OperationStatus.ERROR, "Error while dropping collection");
        } catch (Exception e) {
            return new DropCollectionResponse(OperationStatus.ERROR, "Error while dropping collection: " + e.getMessage());
        } finally {
            locks.release(dbName, collName);
        }
    }

    private CreateIndexResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        try {
            final var dbName = createIndexRequest.getDatabaseName();
            final var collName = createIndexRequest.getCollectionName();
            final var fieldName = createIndexRequest.getFieldName();
            IndexHelper.createIndex(dbName, collName, fieldName);
            taskManager.submitBackgroundTask(new IndexEvent(EventType.CREATED, dbName, collName, fieldName));
            return new CreateIndexResponse(OperationStatus.OK, "Created index for field: " + fieldName);
        } catch (Exception e) {
            return new CreateIndexResponse(OperationStatus.ERROR, "Error while creating index: " + e.getMessage());
        }
    }

    private DropIndexResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        try {
            final var dbName = dropIndexRequest.getDatabaseName();
            final var collName = dropIndexRequest.getCollectionName();
            final var fieldName = dropIndexRequest.getFieldName();
            final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
            if (result) {
                taskManager.submitBackgroundTask(new IndexEvent(EventType.DELETED, dbName, collName, fieldName));
                return new DropIndexResponse(OperationStatus.OK, "Successfully dropped index: " + fieldName);
            } else {
                return new DropIndexResponse(OperationStatus.ERROR, "Error while dropping index: " + fieldName);
            }
        } catch (Exception e) {
            return new DropIndexResponse(OperationStatus.ERROR, "Error while dropping index: " + e.getMessage());
        }
    }
}
