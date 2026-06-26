package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.CollectionEvent;
import org.techhouse.bckg_ops.events.CollectionUsageEvent;
import org.techhouse.bckg_ops.events.DatabaseEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
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
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.ops.req.DropIndexRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListUsersRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.CloseConnectionResponse;
import org.techhouse.ops.resp.CreateCollectionResponse;
import org.techhouse.ops.resp.CreateDatabaseResponse;
import org.techhouse.ops.resp.CreateIndexResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.DropCollectionResponse;
import org.techhouse.ops.resp.DropDatabaseResponse;
import org.techhouse.ops.resp.DropIndexResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.ListUsersResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ReindexResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.ops.resp.SetDatabaseOwnersResponse;

public class OperationProcessor {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final Configuration configuration = Configuration.getInstance();

    private void recordCollectionAccess(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        memoryManagement.recordAccess(AccessKind.COLLECTION, dbName, collName, null);
        taskManager.submitBackgroundTask(
                new CollectionUsageEvent(AccessKind.COLLECTION, dbName, collName, null, System.currentTimeMillis()));
    }

    private void recordPkIndexAccess(String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        memoryManagement.recordAccess(AccessKind.PK_INDEX, dbName, collName, null);
        taskManager.submitBackgroundTask(
                new CollectionUsageEvent(AccessKind.PK_INDEX, dbName, collName, null, System.currentTimeMillis()));
    }

    // Acquire shared read locks on the given collection identifiers in a deterministic (sorted)
    // order so two overlapping multi-collection reads can never deadlock. Returns the identifiers
    // actually locked (in acquisition order); a dirty read takes no collection lock and returns an
    // empty list, relying on FileSystem's per-file locks for read validity.
    private List<String> acquireReadLocks(boolean dirtyRead, List<String> collIdentifiers) throws InterruptedException {
        if (dirtyRead) {
            return List.of();
        }
        final var sorted = collIdentifiers.stream().distinct().sorted().toList();
        final var acquired = new ArrayList<String>();
        for (var identifier : sorted) {
            locks.lockReadByName(identifier);
            acquired.add(identifier);
        }
        return acquired;
    }

    private void releaseReadLocks(List<String> acquired) {
        for (var i = acquired.size() - 1; i >= 0; i--) {
            locks.releaseReadByName(acquired.get(i));
        }
    }

    private List<String> aggregateLockSet(AggregateRequest request) {
        final var dbName = request.getDatabaseName();
        final var identifiers = new ArrayList<String>();
        identifiers.add(Cache.getCollectionIdentifier(dbName, request.getCollectionName()));
        if (request.getAggregationSteps() != null) {
            for (var step : request.getAggregationSteps()) {
                if (step instanceof JoinAggregationStep joinStep) {
                    identifiers.add(Cache.getCollectionIdentifier(dbName, joinStep.getJoinCollection()));
                }
            }
        }
        return identifiers;
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
            case REINDEX -> processReindex((ReindexRequest) operationRequest);
            case CLOSE_CONNECTION -> new CloseConnectionResponse();
            case AUTHENTICATE ->
                UserOperationHelper.processAuthenticate((AuthenticateRequest) operationRequest, clientId);
            case CREATE_USER -> UserOperationHelper.processCreateUser((CreateUserRequest) operationRequest);
            case DELETE_USER -> UserOperationHelper.processDeleteUser((DeleteUserRequest) operationRequest);
            case CHANGE_PERMISSIONS ->
                UserOperationHelper.processChangePermissions((ChangePermissionsRequest) operationRequest);
            case SET_DATABASE_OWNERS -> processSetDatabaseOwners((SetDatabaseOwnersRequest) operationRequest);
            case LIST_USERS -> processListUsers((ListUsersRequest) operationRequest);
            case SET_PASSWORD ->
                UserOperationHelper.processSetPassword((SetPasswordRequest) operationRequest, clientId);
            case GET_DATABASE_STATS -> DatabaseStatsHelper.processGetDatabaseStats();
        };
    }

    private OperationResponse processBulkSaveOperation(BulkSaveRequest bulkSaveRequest) {
        final var dbName = bulkSaveRequest.getDatabaseName();
        final var collName = bulkSaveRequest.getCollectionName();
        final var entries = new ArrayList<DbEntry>();
        for (var entry : bulkSaveRequest.getObjects()) {
            entries.add(DbEntry.fromJsonObject(dbName, collName, entry));
        }
        final var maxEntrySize = configuration.getMaxEntrySize();
        for (var entry : entries) {
            if (entry.byteSize() > maxEntrySize) {
                return new OperationResponse(
                        OperationType.BULK_SAVE, "Entry size of " + entry.byteSize()
                                + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes",
                        ErrorCode.ENTRY_TOO_LARGE);
            }
        }
        final var seenIds = new HashSet<String>();
        for (var entry : entries) {
            if (!seenIds.add(entry.get_id())) {
                return new OperationResponse(OperationType.BULK_SAVE,
                        "Duplicate _id in bulk save request: " + entry.get_id(), ErrorCode.DUPLICATE_ID);
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
                    if (foundIndexEntry >= 0) {
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
                final var bulkResult = fs.bulkUpdateFromCollection(dbName, collName, indexedDbEntriesToUpdate);
                updatedIndexEntries.addAll(bulkResult.updated());
                // Fix the in-memory positions of non-updated survivors shifted by the batch, then
                // replace the updated entries with their new (relocated) index entries.
                bulkResult.compactions().forEach(cache::shiftPkPositionsAfterCompaction);
                primaryKeyIndex.removeIf(pkIndexEntry -> updatedIndexEntries.stream()
                        .anyMatch(pkIndexEntry1 -> pkIndexEntry1.get_id().equals(pkIndexEntry.getValue())));
            }
            primaryKeyIndex.addAll(updatedIndexEntries.stream().map(IndexedDbEntry::getIndex).toList());
            final var entriesToInsert = entries.stream().filter(dbEntry -> indexedDbEntriesToUpdate.stream()
                    .noneMatch(indexedDbEntry -> indexedDbEntry.get_id().equals(dbEntry.get_id()))).toList();
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
            final var updatedIds = updatedDbEntries.stream().map(DbEntry::get_id).toList();
            final var insertedIds = insertedDbEntries.stream().map(DbEntry::get_id).toList();
            // Mark all committed ids pending before releasing the write lock, so index-backed reads
            // reconcile them until their asynchronous field-index update completes.
            pendingIndexWrites.mark(dbName, collName, updatedIds);
            pendingIndexWrites.mark(dbName, collName, insertedIds);
            taskManager
                    .submitBackgroundTask(new BulkEntityEvent(dbName, collName, insertedDbEntries, updatedDbEntries));
            recordCollectionAccess(dbName, collName);
            return new BulkSaveResponse("Successfully saved entries", insertedIds, updatedIds);
        } catch (Exception exception) {
            return new OperationResponse(OperationType.BULK_SAVE, ErrorCode.ERROR_BULK_SAVING);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processSaveOperation(SaveRequest saveRequest) {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        final var maxEntrySize = configuration.getMaxEntrySize();
        if (entry.byteSize() > maxEntrySize) {
            return new OperationResponse(
                    OperationType.SAVE, "Entry size of " + entry.byteSize()
                            + " bytes exceeds the maximum allowed size of " + maxEntrySize + " bytes",
                    ErrorCode.ENTRY_TOO_LARGE);
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
                if (wouldOverflowPage(dbName, collName, idxEntry, entry)) {
                    // The grown document no longer fits on its page; relocate it instead of rewriting in
                    // place (which would push the page past maxPageSize). Handled as a delete + insert so
                    // page metadata and field indexes stay correct via the standard background events.
                    return relocateOnGrowUpdate(dbName, collName, entry, idxEntry, primaryKeyIndex);
                }
                entry.setPage(idxEntry.getPage());
                final var updateResult = fs.updateFromCollection(entry, idxEntry);
                savedPkIndexEntry = updateResult.indexEntry();
                cache.shiftPkPositionsAfterCompaction(updateResult.compaction());
                primaryKeyIndex.remove(idxEntry);
                eventType = EventType.UPDATED;
            } else {
                entry.setPage(cache.selectPageForInsert(dbName, collName, entry.byteSize()));
                savedPkIndexEntry = fs.insertIntoCollection(entry);
                cache.updatePageSizeInMemory(dbName, collName, savedPkIndexEntry.getPage(),
                        savedPkIndexEntry.getLength());
            }
            int insertAt = Collections.binarySearch(primaryKeyIndex, savedPkIndexEntry.getValue());
            if (insertAt < 0) {
                insertAt = -(insertAt + 1);
            }
            primaryKeyIndex.add(insertAt, savedPkIndexEntry);
            cache.addEntryToCache(dbName, collName, entry);
            // Mark the id pending (committed, but its field-index update is asynchronous) before
            // releasing the write lock, so index-backed reads reconcile it until indexing completes.
            pendingIndexWrites.mark(dbName, collName, entry.get_id());
            taskManager.submitBackgroundTask(new EntityEvent(eventType, dbName, collName, entry));
            recordCollectionAccess(dbName, collName);
            return new SaveResponse("Successfully saved", savedPkIndexEntry.getValue());
        } catch (Exception exception) {
            return new OperationResponse(OperationType.SAVE, ErrorCode.ERROR_SAVING);
        } finally {
            locks.release(dbName, collName);
        }
    }

    // True when rewriting the existing entry in place with the new (larger) value would push its page
    // past maxPageSize. Uses the cached page byte size (minus the old entry, plus the new one); when no
    // page metadata is available the check is skipped (falls back to the in-place update).
    private boolean wouldOverflowPage(String dbName, String collName, PkIndexEntry idxEntry, DbEntry entry) {
        final var pageEntry = cache.getAdminPageEntry(dbName, collName, idxEntry.getPage());
        if (pageEntry == null) {
            return false;
        }
        final var projectedPageSize = pageEntry.getPageSize() - idxEntry.getLength() + entry.byteSize();
        return projectedPageSize > configuration.getMaxPageSize();
    }

    // Relocates a grown document that no longer fits on its page: removes it from the current page
    // (compacting the survivors) and re-inserts it into a fitting page. Modeled as a DELETE of the old
    // version followed by a CREATE of the new one so per-page metadata (the old page loses the entry,
    // the new page gains it) and the field indexes are maintained through the same background events the
    // standalone delete/insert paths emit. Runs while the caller already holds the collection write lock.
    private SaveResponse relocateOnGrowUpdate(String dbName, String collName, DbEntry entry, PkIndexEntry idxEntry,
            List<PkIndexEntry> primaryKeyIndex) throws Exception {
        final var oldEntry = cache.getById(dbName, collName, idxEntry);
        oldEntry.setPage(idxEntry.getPage());
        final var compaction = fs.deleteFromCollection(idxEntry);
        cache.shiftPkPositionsAfterCompaction(compaction);
        primaryKeyIndex.remove(idxEntry);
        cache.evictEntry(dbName, collName, entry.get_id());
        pendingIndexWrites.mark(dbName, collName, entry.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, oldEntry));

        entry.setPage(cache.selectPageForInsert(dbName, collName, entry.byteSize()));
        final var relocatedPkIndexEntry = fs.insertIntoCollection(entry);
        cache.updatePageSizeInMemory(dbName, collName, relocatedPkIndexEntry.getPage(),
                relocatedPkIndexEntry.getLength());
        int insertAt = Collections.binarySearch(primaryKeyIndex, relocatedPkIndexEntry.getValue());
        if (insertAt < 0) {
            insertAt = -(insertAt + 1);
        }
        primaryKeyIndex.add(insertAt, relocatedPkIndexEntry);
        cache.addEntryToCache(dbName, collName, entry);
        pendingIndexWrites.mark(dbName, collName, entry.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.CREATED, dbName, collName, entry));
        recordCollectionAccess(dbName, collName);
        return new SaveResponse("Successfully saved", relocatedPkIndexEntry.getValue());
    }

    private OperationResponse processFindByIdOperation(FindByIdRequest findbyIdRequest) {
        final var dbName = findbyIdRequest.getDatabaseName();
        final var collName = findbyIdRequest.getCollectionName();
        final var id = findbyIdRequest.get_id();
        List<String> readLocks = List.of();
        try {
            readLocks = acquireReadLocks(findbyIdRequest.isDirtyRead(),
                    List.of(Cache.getCollectionIdentifier(dbName, collName)));
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = Collections.binarySearch(primaryKeyIndex, id);
            if (foundIndexEntry >= 0) {
                final var primaryKeyIndexEntry = primaryKeyIndex.get(foundIndexEntry);
                final var entry = cache.getById(dbName, collName, primaryKeyIndexEntry);
                recordPkIndexAccess(dbName, collName);
                recordCollectionAccess(dbName, collName);
                return new FindByIdResponse("Ok", entry.getData());
            } else {
                return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ENTRY_NOT_FOUND);
            }
        } catch (Exception exception) {
            return new OperationResponse(OperationType.FIND_BY_ID, ErrorCode.ERROR_RETRIEVING);
        } finally {
            releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processAggregateOperation(AggregateRequest aggregateRequest) {
        List<String> readLocks = List.of();
        try {
            readLocks = acquireReadLocks(aggregateRequest.isDirtyRead(), aggregateLockSet(aggregateRequest));
            final var results = AggregationOperationHelper.processAggregation(aggregateRequest);
            recordCollectionAccess(aggregateRequest.getDatabaseName(), aggregateRequest.getCollectionName());
            return results.isEmpty()
                    ? new OperationResponse(OperationType.AGGREGATE, ErrorCode.NO_RESULTS)
                    : new AggregateResponse("Ok", results);
        } catch (Exception e) {
            return new OperationResponse(OperationType.AGGREGATE, ErrorCode.ERROR_AGGREGATING);
        } finally {
            releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processDeleteOperation(DeleteRequest deleteRequest) {
        final var dbName = deleteRequest.getDatabaseName();
        final var collName = deleteRequest.getCollectionName();
        try {
            locks.lock(dbName, collName);
            final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
            final var foundIndexEntry = primaryKeyIndex.stream()
                    .filter(pkIndexEntry -> pkIndexEntry.getValue().equals(deleteRequest.get_id())).findFirst();
            if (foundIndexEntry.isPresent()) {
                final var idxEntry = foundIndexEntry.get();
                final var entryToBeDeleted = cache.getById(dbName, collName, idxEntry);
                final var compaction = fs.deleteFromCollection(idxEntry);
                cache.shiftPkPositionsAfterCompaction(compaction);
                primaryKeyIndex.remove(idxEntry);
                primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
                cache.evictEntry(dbName, collName, entryToBeDeleted.get_id());
                // Mark pending until the async index removal completes: the field index still maps the
                // value to this id, so index-only reads that don't re-fetch the document (COUNT, DISTINCT)
                // would otherwise count/surface the deleted doc. The DELETED event clears it.
                pendingIndexWrites.mark(dbName, collName, entryToBeDeleted.get_id());
                taskManager
                        .submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, entryToBeDeleted));
                recordCollectionAccess(dbName, collName);
                return new DeleteResponse("Entry with id " + deleteRequest.get_id() + " deleted successfully");
            } else {
                return new OperationResponse(OperationType.DELETE,
                        "Entry with id " + deleteRequest.get_id() + " not found", ErrorCode.ENTRY_NOT_FOUND);
            }
        } catch (Exception exception) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.ERROR_DELETING);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processCreateDatabaseOperation(CreateDatabaseRequest createDatabaseRequest,
            UUID clientId) {
        try {
            final var dbName = createDatabaseRequest.getDatabaseName();
            final var result = fs.createDatabaseFolder(dbName);
            if (result) {
                final var username = clientTracker.getAuthenticatedUsername(clientId);
                final var owners = username != null ? List.of(username) : List.<String>of();
                final var newEntry = new AdminDbEntry(dbName, new java.util.ArrayList<>(),
                        new java.util.ArrayList<>(owners));
                AdminOperationHelper.saveDatabaseEntry(newEntry);
                taskManager.submitBackgroundTask(new DatabaseEvent(EventType.CREATED, dbName));
                return new CreateDatabaseResponse("Database created successfully");
            }
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.DATABASE_ALREADY_EXISTS);
        } catch (Exception exception) {
            return new OperationResponse(OperationType.CREATE_DATABASE, ErrorCode.ERROR_CREATING_DATABASE);
        }
    }

    private OperationResponse processSetDatabaseOwners(SetDatabaseOwnersRequest request) {
        try {
            final var dbName = request.getDatabaseName();
            if (cache.getAdminDbEntry(dbName) == null) {
                return new OperationResponse(OperationType.SET_DATABASE_OWNERS, "Database '" + dbName + "' not found",
                        ErrorCode.DATABASE_NOT_FOUND);
            }
            AdminOperationHelper.updateDatabaseOwners(dbName, request.getOwners());
            return new SetDatabaseOwnersResponse("Database owners updated successfully");
        } catch (Exception e) {
            return new OperationResponse(OperationType.SET_DATABASE_OWNERS, ErrorCode.ERROR_UPDATING_DATABASE_OWNERS);
        }
    }

    private OperationResponse processDropDatabaseOperation(DropDatabaseRequest dropDatabaseRequest) {
        final var dbName = dropDatabaseRequest.getDatabaseName();
        // Lock every collection of the database (in a stable order to avoid deadlock with other
        // multi-collection acquisitions) so a concurrent save/delete/read or a background index update
        // on any of them cannot race the file deletion and cache eviction below.
        final var dbEntry = cache.getAdminDbEntry(dbName);
        final var collNames = dbEntry != null ? new ArrayList<>(dbEntry.getCollections()) : new ArrayList<String>();
        Collections.sort(collNames);
        final var lockedColls = new ArrayList<String>();
        try {
            for (final var collName : collNames) {
                locks.lock(dbName, collName);
                lockedColls.add(collName);
            }
            final var result = fs.deleteDatabase(dbName);
            if (result) {
                cache.evictDatabase(dbName);
                for (final var collName : lockedColls) {
                    locks.removeLock(dbName, collName);
                }
                taskManager.submitBackgroundTask(new DatabaseEvent(EventType.DELETED, dbName));
                return new DropDatabaseResponse("Database dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        } catch (Exception exception) {
            return new OperationResponse(OperationType.DROP_DATABASE, ErrorCode.ERROR_DROPPING_DATABASE);
        } finally {
            for (final var collName : lockedColls) {
                locks.release(dbName, collName);
            }
        }
    }

    private OperationResponse processListDatabasesOperation() {
        try {
            final var names = cache.getUserDatabaseNames();
            return new ListDatabasesResponse("Ok", names);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_DATABASES, ErrorCode.ERROR_LISTING_DATABASES);
        }
    }

    private OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        try {
            final var dbName = createCollectionRequest.getDatabaseName();
            final var collName = createCollectionRequest.getCollectionName();
            final var result = fs.createCollectionFile(dbName, collName);
            if (result) {
                taskManager.submitBackgroundTask(new CollectionEvent(EventType.CREATED, dbName, collName));
                return new CreateCollectionResponse("Collection created successfully");
            }
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
        } catch (Exception e) {
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
        }
    }

    private OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest) {
        final var dbName = dropCollectionRequest.getDatabaseName();
        final var collName = dropCollectionRequest.getCollectionName();
        boolean dropSucceeded = false;
        try {
            locks.lock(dbName, collName);
            final var result = fs.deleteCollectionFiles(dbName, collName);
            if (result) {
                cache.evictCollection(dbName, collName);
                taskManager.submitBackgroundTask(new CollectionEvent(EventType.DELETED, dbName, collName));
                dropSucceeded = true;
                return new DropCollectionResponse("Collection dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } catch (Exception e) {
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } finally {
            locks.release(dbName, collName);
            if (dropSucceeded) {
                locks.removeLock(dbName, collName);
            }
        }
    }

    private OperationResponse processListCollectionsOperation(ListCollectionsRequest request) {
        List<String> readLocks = List.of();
        try {
            final var dbName = request.getDatabaseName();
            if (dbName == null || dbName.isBlank()) {
                return new OperationResponse(OperationType.LIST_COLLECTIONS, "Database name is required",
                        ErrorCode.VALIDATION_ERROR);
            }
            if (Globals.ADMIN_DB_NAME.equals(dbName)) {
                return new ListCollectionsResponse("Ok", List.of());
            }
            readLocks = acquireReadLocks(request.isDirtyRead(), List.of(
                    Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME)));
            if (cache.getAdminDbEntry(dbName) == null) {
                return new OperationResponse(OperationType.LIST_COLLECTIONS, "Database " + dbName + " not found",
                        ErrorCode.DATABASE_NOT_FOUND);
            }
            final var names = cache.getCollectionNamesForDatabase(dbName);
            return new ListCollectionsResponse("Ok", names);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_COLLECTIONS, ErrorCode.ERROR_LISTING_COLLECTIONS);
        } finally {
            releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        final var dbName = createIndexRequest.getDatabaseName();
        final var collName = createIndexRequest.getCollectionName();
        final var fieldName = createIndexRequest.getFieldName();
        try {
            // Hold the collection write lock so no save can commit a document between the index build
            // and its registration. Building the index files and registering the field as a known
            // index happen atomically here (synchronously), so any concurrent save is serialized: it
            // either commits before (and is captured by the whole-collection read) or after (and its
            // background index update sees the field already registered and indexes it).
            locks.lock(dbName, collName);
            IndexHelper.createIndex(dbName, collName, fieldName);
            AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
            return new CreateIndexResponse("Created index for field: " + fieldName);
        } catch (Exception e) {
            return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.ERROR_CREATING_INDEX);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processListUsers(ListUsersRequest request) {
        List<String> readLocks = List.of();
        try {
            readLocks = acquireReadLocks(request.isDirtyRead(),
                    List.of(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME)));
            final var userStream = cache.getAllAdminUserEntries().stream()
                    .map(user -> user.toResponseJson(cache.getAllAdminDbEntries().stream()
                            .filter(db -> db.isOwner(user.get_id())).map(DbEntry::get_id).toList()));
            final var results = AggregationOperationHelper.processStepsOnStream(request.getAggregationSteps(),
                    userStream);
            return results.isEmpty()
                    ? new OperationResponse(OperationType.LIST_USERS, ErrorCode.NO_USERS_FOUND)
                    : new ListUsersResponse("Ok", results);
        } catch (Exception e) {
            return new OperationResponse(OperationType.LIST_USERS, ErrorCode.ERROR_LISTING_USERS);
        } finally {
            releaseReadLocks(readLocks);
        }
    }

    private OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        final var dbName = dropIndexRequest.getDatabaseName();
        final var collName = dropIndexRequest.getCollectionName();
        final var fieldName = dropIndexRequest.getFieldName();
        try {
            // Hold the collection write lock so the index files are deleted and the field is
            // unregistered atomically with respect to saves and the background indexer. Unregister
            // first so no read or background index update can use the field after this returns.
            locks.lock(dbName, collName);
            final var result = IndexHelper.dropIndex(dbName, collName, fieldName);
            if (result) {
                AdminOperationHelper.deleteIndex(dbName, collName, fieldName);
                return new DropIndexResponse("Successfully dropped index: " + fieldName);
            } else {
                return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
            }
        } catch (Exception e) {
            return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.ERROR_DROPPING_INDEX);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private OperationResponse processReindex(ReindexRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        try {
            // Hold the collection write lock for the entire rebuild so no concurrent save can commit
            // between field rebuilds. Admin metadata is not changed — the indexes already exist.
            // If createIndex throws mid-loop (e.g. disk full), the catch returns ERROR; the operator
            // can retry once the underlying issue is resolved.
            locks.lock(dbName, collName);
            final var registeredIndexes = cache.getIndexesForCollection(dbName, collName);
            final List<String> targets;
            if (request.getFieldNames().isEmpty()) {
                targets = new ArrayList<>(registeredIndexes);
            } else {
                for (var fieldName : request.getFieldNames()) {
                    if (!registeredIndexes.contains(fieldName)) {
                        return new OperationResponse(OperationType.REINDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                    }
                }
                targets = request.getFieldNames();
            }
            if (targets.isEmpty()) {
                return new ReindexResponse("No indexes to rebuild", List.of());
            }
            for (var fieldName : targets) {
                IndexHelper.dropIndex(dbName, collName, fieldName);
                IndexHelper.createIndex(dbName, collName, fieldName);
            }
            return new ReindexResponse("Rebuilt " + targets.size() + " index(es)", targets);
        } catch (Exception e) {
            return new OperationResponse(OperationType.REINDEX, ErrorCode.ERROR_REINDEXING);
        } finally {
            locks.release(dbName, collName);
        }
    }
}
