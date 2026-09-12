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
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.WriteVersion;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;

public final class SaveOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    // Not final so tests can substitute a task manager whose workers are never started.
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final Configuration configuration = Configuration.getInstance();

    private SaveOperationHelper() {
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeSave(SaveRequest saveRequest) throws Exception {
        final var dbName = saveRequest.getDatabaseName();
        final var collName = saveRequest.getCollectionName();
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        entry.setVersion(WriteVersion.next());
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
        return new SaveResponse("Successfully saved", savedPkIndexEntry.getValue());
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
            if (versions != null) {
                // Versions deserialize as a boxed Integer/Long/Double, so read them through Number.
                final Number version = versions.get(i);
                entry.setVersion(version.longValue());
                WriteVersion.observe(version.longValue());
            } else {
                entry.setVersion(WriteVersion.next());
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
                    indexedDbEntriesToUpdate.add(indexedDbEntry);
                }
            }
        }
        final List<IndexedDbEntry> updatedIndexEntries = new ArrayList<>();
        if (!indexedDbEntriesToUpdate.isEmpty()) {
            final var bulkResult = fs.bulkUpdateFromCollection(dbName, collName, indexedDbEntriesToUpdate);
            updatedIndexEntries.addAll(bulkResult.updated());
            // Fix the in-memory positions of survivors shifted by the batch before replacing the updated ones.
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
        return relocatedPkIndexEntry;
    }
}
