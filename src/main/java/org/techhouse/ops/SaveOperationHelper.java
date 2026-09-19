package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.BulkUpdateResult;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

public final class SaveOperationHelper {
    private static final Logger logger = Logger.logFor(SaveOperationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    // Not final so tests can substitute a task manager whose workers are never started.
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);

    private SaveOperationHelper() {
    }

    private static OperationResponse reconcileSaveId(SaveRequest saveRequest) {
        final var object = saveRequest.getObject();
        final var requestId = saveRequest.get_id();
        final var objectId = object.get(Globals.PK_FIELD) instanceof JsonString jsonString
                ? jsonString.getValue()
                : null;
        if (requestId != null && objectId != null && !requestId.equals(objectId)) {
            return new OperationResponse(OperationType.SAVE,
                    "The _id sent with the request does not match the _id inside the object",
                    ErrorCode.VALIDATION_ERROR);
        }
        if (requestId != null && objectId == null) {
            object.addProperty(Globals.PK_FIELD, requestId);
        } else if (requestId == null && objectId != null) {
            saveRequest.set_id(objectId);
        }
        return null;
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeSave(SaveRequest saveRequest) throws Exception {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        final var idError = reconcileSaveId(saveRequest);
        if (idError != null) {
            return idError;
        }
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        entry.setVersion(hybridClock.next());
        final var sizeError = EntrySizeGuard.check(entry, OperationType.SAVE);
        if (sizeError != null) {
            return sizeError;
        }
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
                // The grown document no longer fits its page; relocate as delete + insert so page metadata
                // and field indexes stay correct through the standard background events.
                final var relocatedPkIndexEntry = relocateOnGrowUpdate(dbName, collName, entry, idxEntry,
                        primaryKeyIndex);
                listenManager.markDirty(dbName, collName);
                CollectionAccessHelper.recordCollectionAccess(dbName, collName);
                return new SaveResponse("Successfully saved", relocatedPkIndexEntry.getValue());
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
            cache.updatePageSizeInMemory(dbName, collName, savedPkIndexEntry.getPage(), savedPkIndexEntry.getLength());
        }
        int insertAt = Collections.binarySearch(primaryKeyIndex, savedPkIndexEntry.getValue());
        if (insertAt < 0) {
            insertAt = -(insertAt + 1);
        }
        primaryKeyIndex.add(insertAt, savedPkIndexEntry);
        cache.addEntryToCache(dbName, collName, entry);
        // Mark pending before releasing the write lock, so index-backed reads reconcile it until indexed.
        pendingIndexWrites.mark(dbName, collName, entry.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(eventType, dbName, collName, entry));
        listenManager.markDirty(dbName, collName);
        CollectionAccessHelper.recordCollectionAccess(dbName, collName);
        return new SaveResponse("Successfully saved", savedPkIndexEntry.getValue(), eventType == EventType.CREATED);
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest) throws Exception {
        return executeBulkSave(bulkSaveRequest, null);
    }

    // A non-null versions list supplies the owner's version per object, so replicas do not assign their own.
    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest, List<Long> versions)
            throws Exception {
        final var dbName = bulkSaveRequest.getDatabaseName();
        final var collName = bulkSaveRequest.getCollectionName();
        final var entries = new ArrayList<DbEntry>();
        final var objects = bulkSaveRequest.getObjects();
        for (var i = 0; i < objects.size(); i++) {
            final var entry = DbEntry.fromJsonObject(dbName, collName, objects.get(i));
            final var version = versions != null ? versions.get(i) : null;
            if (version != null) {
                entry.setVersion(version);
                hybridClock.observe(version);
            } else {
                entry.setVersion(hybridClock.next());
            }
            entries.add(entry);
        }
        for (var entry : entries) {
            final var sizeError = EntrySizeGuard.check(entry, OperationType.BULK_SAVE);
            if (sizeError != null) {
                return sizeError;
            }
        }
        final var seenIds = new HashSet<String>();
        for (var entry : entries) {
            if (!seenIds.add(entry.get_id())) {
                return new OperationResponse(OperationType.BULK_SAVE,
                        "Duplicate _id in bulk save request: " + entry.get_id(), ErrorCode.DUPLICATE_ID);
            }
        }
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var indexedDbEntriesToUpdate = new ArrayList<IndexedDbEntry>();
        for (var i : entries) {
            final var data = i.getData();
            if (data.has(org.techhouse.config.Globals.PK_FIELD)) {
                final var id = data.get(org.techhouse.config.Globals.PK_FIELD).asJsonString().getValue();
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
                    indexedDbEntry.setVersion(i.getVersion());
                    indexedDbEntriesToUpdate.add(indexedDbEntry);
                }
            }
        }
        final List<IndexedDbEntry> updatedIndexEntries = new ArrayList<>();
        if (!indexedDbEntriesToUpdate.isEmpty()) {
            final BulkUpdateResult bulkResult;
            try {
                bulkResult = fs.bulkUpdateFromCollection(dbName, collName, indexedDbEntriesToUpdate);
            } catch (Exception e) {
                cache.userCache().evictPkIndex(dbName, collName);
                throw e;
            }
            updatedIndexEntries.addAll(bulkResult.updated());
            // Fix the in-memory positions of survivors shifted by the batch before replacing the updated ones.
            bulkResult.compactions().forEach(cache::shiftPkPositionsAfterCompaction);
            primaryKeyIndex.removeIf(pkIndexEntry -> updatedIndexEntries.stream()
                    .anyMatch(pkIndexEntry1 -> pkIndexEntry1.get_id().equals(pkIndexEntry.getValue())));
        }
        final var entriesToInsert = entries.stream().filter(dbEntry -> indexedDbEntriesToUpdate.stream()
                .noneMatch(indexedDbEntry -> indexedDbEntry.get_id().equals(dbEntry.get_id()))).toList();
        List<IndexedDbEntry> insertedIndexEntries = new ArrayList<>();
        try {
            primaryKeyIndex.addAll(updatedIndexEntries.stream().map(IndexedDbEntry::getIndex).toList());
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
        } finally {
            primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
        }
        final var updatedDbEntries = updatedIndexEntries.stream().map(IndexedDbEntry::toDbEntry).toList();
        cache.addEntriesToCache(dbName, collName, updatedDbEntries);
        final var insertedDbEntries = insertedIndexEntries.stream().map(IndexedDbEntry::toDbEntry).toList();
        cache.addEntriesToCache(dbName, collName, insertedDbEntries);
        final var updatedIds = updatedDbEntries.stream().map(DbEntry::get_id).toList();
        final var insertedIds = insertedDbEntries.stream().map(DbEntry::get_id).toList();
        // Mark the committed ids pending before releasing the write lock, so index-backed reads reconcile them.
        pendingIndexWrites.mark(dbName, collName, updatedIds);
        pendingIndexWrites.mark(dbName, collName, insertedIds);
        taskManager.submitBackgroundTask(new BulkEntityEvent(dbName, collName, insertedDbEntries, updatedDbEntries));
        listenManager.markDirty(dbName, collName);
        CollectionAccessHelper.recordCollectionAccess(dbName, collName);
        return new BulkSaveResponse("Successfully saved entries", insertedIds, updatedIds);
    }

    public static boolean wouldOverflowPage(String dbName, String collName, PkIndexEntry idxEntry, DbEntry entry) {
        final var pageEntry = cache.getAdminPageEntry(dbName, collName, idxEntry.getPage());
        if (pageEntry == null) {
            return false;
        }
        final var projectedPageSize = pageEntry.getPageSize() - idxEntry.getLength() + entry.byteSize();
        return projectedPageSize > configuration.getMaxPageSize();
    }

    // The caller must already hold the collection write lock.
    public static PkIndexEntry relocateOnGrowUpdate(String dbName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, List<PkIndexEntry> primaryKeyIndex) throws Exception {
        final var oldEntry = cache.getById(dbName, collName, idxEntry);
        oldEntry.setPage(idxEntry.getPage());
        final var compaction = fs.deleteFromCollection(idxEntry);
        cache.shiftPkPositionsAfterCompaction(compaction);
        primaryKeyIndex.remove(idxEntry);
        cache.evictEntry(dbName, collName, entry.get_id());
        pendingIndexWrites.mark(dbName, collName, entry.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, oldEntry));

        final PkIndexEntry relocatedPkIndexEntry;
        try {
            entry.setPage(cache.selectPageForInsert(dbName, collName, entry.byteSize()));
            relocatedPkIndexEntry = fs.insertIntoCollection(entry);
        } catch (Exception e) {
            restoreAfterFailedRelocation(dbName, collName, oldEntry, primaryKeyIndex);
            throw e;
        }
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
        return relocatedPkIndexEntry;
    }

    private static void restoreAfterFailedRelocation(String dbName, String collName, DbEntry oldEntry,
            List<PkIndexEntry> primaryKeyIndex) {
        try {
            oldEntry.setPage(cache.selectPageForInsert(dbName, collName, oldEntry.byteSize()));
            final var restored = fs.insertIntoCollection(oldEntry);
            cache.updatePageSizeInMemory(dbName, collName, restored.getPage(), restored.getLength());
            var insertAt = Collections.binarySearch(primaryKeyIndex, restored.getValue());
            if (insertAt < 0) {
                insertAt = -(insertAt + 1);
            }
            primaryKeyIndex.add(insertAt, restored);
            cache.addEntryToCache(dbName, collName, oldEntry);
            taskManager.submitBackgroundTask(new EntityEvent(EventType.CREATED, dbName, collName, oldEntry));
        } catch (Exception restoreFailure) {
            logger.error(
                    "Relocation of " + oldEntry.get_id() + " in " + dbName + "|" + collName
                            + " failed and the original could not be restored; run REINDEX on this collection",
                    restoreFailure);
        }
    }
}
