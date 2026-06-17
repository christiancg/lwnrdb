package org.techhouse.ops;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.*;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.*;
import org.techhouse.ops.resp.*;

import java.util.List;
import java.util.UUID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

public class OperationProcessor {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

    private void recordCollectionAccess(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        memoryManagement.recordAccess(AccessKind.COLLECTION, dbName, collName, null);
        taskManager.submitBackgroundTask(new CollectionUsageEvent(AccessKind.COLLECTION, dbName, collName, null,
                System.currentTimeMillis()));
    }

    private void recordPkIndexAccess(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        memoryManagement.recordAccess(AccessKind.PK_INDEX, dbName, collName, null);
        taskManager.submitBackgroundTask(new CollectionUsageEvent(AccessKind.PK_INDEX, dbName, collName, null,
                System.currentTimeMillis()));
    }

    public OperationResponse processMessage(OperationRequest operationRequest) {
        return processMessage(operationRequest, null);
    }

    public OperationResponse processMessage(OperationRequest operationRequest, UUID clientId) {
        return switch (operationRequest.getType()) {
            case BULK_SAVE -> processBulkSaveOperation((BulkSaveRequest) operationRequest);
            case SAVE -> processSaveOperation((SaveRequest) operationRequest);
            case FIND_BY_ID -> processFindByIdOperation((FindByIdRequest) operationRequest);
            case AGGREGATE -> processAggregateOperation((AggregateRequest) operationRequest);
            case DELETE -> processDeleteOperation((DeleteRequest) operationRequest);
            case CREATE_DATABASE -> processCreateDatabaseOperation((CreateDatabaseRequest) operationRequest, clientId);
            case DROP_DATABASE -> processDropDatabaseOperation((DropDatabaseRequest) operationRequest);
            case LIST_DATABASES -> processListDatabasesOperation();
            case CREATE_COLLECTION -> processCreateCollectionOperation((CreateCollectionRequest) operationRequest);
            case DROP_COLLECTION -> processDropCollectionOperation((DropCollectionRequest) operationRequest);
            case LIST_COLLECTIONS -> processListCollectionsOperation((ListCollectionsRequest) operationRequest);
            case CREATE_INDEX -> processCreateIndex((CreateIndexRequest) operationRequest);
            case DROP_INDEX -> processDropIndex((DropIndexRequest) operationRequest);
            case CLOSE_CONNECTION -> new CloseConnectionResponse();
            case AUTHENTICATE -> UserOperationHelper.processAuthenticate((AuthenticateRequest) operationRequest, clientId);
            case CREATE_USER -> UserOperationHelper.processCreateUser((CreateUserRequest) operationRequest);
            case DELETE_USER -> UserOperationHelper.processDeleteUser((DeleteUserRequest) operationRequest);
            case CHANGE_PERMISSIONS -> UserOperationHelper.processChangePermissions((ChangePermissionsRequest) operationRequest);
            case SET_DATABASE_OWNERS -> processSetDatabaseOwners((SetDatabaseOwnersRequest) operationRequest);
            case LIST_USERS -> processListUsers((ListUsersRequest) operationRequest);
            case SET_PASSWORD -> UserOperationHelper.processSetPassword((SetPasswordRequest) operationRequest, clientId);
            case GET_DATABASE_STATS -> DatabaseStatsHelper.processGetDatabaseStats();
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
        final var maxEntrySize = Configuration.getInstance().getMaxEntrySizeBytes();
        for (var entry : entries) {
            if (entry.byteSize() > maxEntrySize) {
                return new BulkSaveResponse(OperationStatus.ERROR,
                        "Entry size of " + entry.byteSize() + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes", null, null);
            }
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
                final var pendingPageBytes = new HashMap<Long, Long>();
                for (var e : entriesToInsert) {
                    final var size = e.byteSize();
                    final var target = cache.selectPageForInsert(dbName, collName, size, pendingPageBytes);
                    e.setPage(target);
                    pendingPageBytes.merge(target, (long) size, Long::sum);
                }
                insertedIndexEntries = fs.bulkInsertIntoCollection(dbName, collName, entriesToInsert);
                for (var ie : insertedIndexEntries) {
                    cache.updatePageSizeInMemory(dbName, collName, ie.getIndex().getPage(), ie.getIndex().getLength());
                }
            }
            primaryKeyIndex.addAll(insertedIndexEntries.stream().map(IndexedDbEntry::getIndex).toList());
            primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
            final var updatedDbEntries = updatedIndexEntries.stream().map(IndexedDbEntry::toDbEntry).toList();
            cache.addEntriesToCache(dbName, collName, updatedDbEntries);
            final var insertedDbEntries = insertedIndexEntries.stream().map(IndexedDbEntry::toDbEntry).toList();
            cache.addEntriesToCache(dbName, collName, insertedDbEntries);
            taskManager.submitBackgroundTask(new BulkEntityEvent(dbName, collName, insertedDbEntries, updatedDbEntries));
            recordCollectionAccess(dbName, collName);
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
        final var maxEntrySize = Configuration.getInstance().getMaxEntrySizeBytes();
        if (entry.byteSize() > maxEntrySize) {
            return new SaveResponse(OperationStatus.ERROR,
                    "Entry size of " + entry.byteSize() + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes", null);
        }
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
                entry.setPage(idxEntry.getPage());
                savedPkIndexEntry = fs.updateFromCollection(entry, idxEntry);
                primaryKeyIndex.remove(idxEntry);
                eventType = EventType.UPDATED;
            } else {
                entry.setPage(cache.selectPageForInsert(dbName, collName, entry.byteSize()));
                savedPkIndexEntry = fs.insertIntoCollection(entry);
                cache.updatePageSizeInMemory(dbName, collName, savedPkIndexEntry.getPage(), savedPkIndexEntry.getLength());
            }
            int insertAt = Collections.binarySearch(primaryKeyIndex, savedPkIndexEntry.getValue());
            if (insertAt < 0) {
                insertAt = -(insertAt + 1);
            }
            primaryKeyIndex.add(insertAt, savedPkIndexEntry);
            cache.addEntryToCache(dbName, collName, entry);
            taskManager.submitBackgroundTask(new EntityEvent(eventType, dbName, collName, entry));
            recordCollectionAccess(dbName, collName);
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
                recordPkIndexAccess(dbName, collName);
                recordCollectionAccess(dbName, collName);
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
            recordCollectionAccess(aggregateRequest.getDatabaseName(), aggregateRequest.getCollectionName());
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
                recordCollectionAccess(dbName, collName);
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

    private CreateDatabaseResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest, UUID clientId) {
        try {
            final var dbName = createDatabaseRequest.getDatabaseName();
            final var result = fs.createDatabaseFolder(dbName);
            if (result) {
                final var username = clientTracker.getAuthenticatedUsername(clientId);
                final var owners = username != null ? List.of(username) : List.<String>of();
                final var newEntry = new AdminDbEntry(dbName, new java.util.ArrayList<>(), new java.util.ArrayList<>(owners));
                AdminOperationHelper.saveDatabaseEntry(newEntry);
                taskManager.submitBackgroundTask(new DatabaseEvent(EventType.CREATED, dbName));
                return new CreateDatabaseResponse(OperationStatus.OK, "Database created successfully");
            }
            return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
        } catch (Exception exception) {
            return new CreateDatabaseResponse(OperationStatus.ERROR, "Error while creating database");
        }
    }

    private SetDatabaseOwnersResponse processSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        try {
            final var dbName = request.getDatabaseName();
            if (cache.getAdminDbEntry(dbName) == null) {
                return new SetDatabaseOwnersResponse(OperationStatus.NOT_FOUND, "Database '" + dbName + "' not found");
            }
            AdminOperationHelper.updateDatabaseOwners(dbName, request.getOwners());
            return new SetDatabaseOwnersResponse(OperationStatus.OK, "Database owners updated successfully");
        } catch (Exception e) {
            return new SetDatabaseOwnersResponse(OperationStatus.ERROR, "Error updating database owners: " + e.getMessage());
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

    private ListDatabasesResponse processListDatabasesOperation() {
        try {
            final var names = cache.getUserDatabaseNames();
            return new ListDatabasesResponse(OperationStatus.OK, "Ok", names);
        } catch (Exception e) {
            return new ListDatabasesResponse(OperationStatus.ERROR,
                    "Error while listing databases: " + e.getMessage(), null);
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

    private ListCollectionsResponse processListCollectionsOperation(ListCollectionsRequest request) {
        try {
            final var dbName = request.getDatabaseName();
            if (dbName == null || dbName.isBlank()) {
                return new ListCollectionsResponse(OperationStatus.ERROR,
                        "Database name is required", null);
            }
            if (Globals.ADMIN_DB_NAME.equals(dbName)) {
                return new ListCollectionsResponse(OperationStatus.OK, "Ok", List.of());
            }
            if (cache.getAdminDbEntry(dbName) == null) {
                return new ListCollectionsResponse(OperationStatus.NOT_FOUND,
                        "Database " + dbName + " not found", null);
            }
            final var names = cache.getCollectionNamesForDatabase(dbName);
            return new ListCollectionsResponse(OperationStatus.OK, "Ok", names);
        } catch (Exception e) {
            return new ListCollectionsResponse(OperationStatus.ERROR,
                    "Error while listing collections: " + e.getMessage(), null);
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

    private ListUsersResponse processListUsers(ListUsersRequest request) {
        try {
            final var userStream = cache.getAllAdminUserEntries().stream()
                    .map(user -> user.toResponseJson(
                            cache.getAllAdminDbEntries().stream()
                                    .filter(db -> db.isOwner(user.get_id()))
                                    .map(DbEntry::get_id)
                                    .toList()));
            final var results = AggregationOperationHelper.processStepsOnStream(
                    request.getAggregationSteps(), userStream);
            return results.isEmpty()
                    ? new ListUsersResponse(OperationStatus.NOT_FOUND, "No users found", null)
                    : new ListUsersResponse(OperationStatus.OK, "Ok", results);
        } catch (Exception e) {
            return new ListUsersResponse(OperationStatus.ERROR, "Error listing users: " + e.getMessage(), null);
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
