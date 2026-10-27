package org.techhouse.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.BulkEntityEvent;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.PkIndexList;
import org.techhouse.cluster.HybridClock;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexedDbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.PartialBulkSaveException;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.PagePlacement;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.DirtySink;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.admin.CollectionIncarnation;
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
        return executeSave(saveRequest, 0L, listenManager);
    }

    public static OperationResponse executeSave(SaveRequest saveRequest, long version, DirtySink sink)
            throws Exception {
        final var names = Cache.canonicalNames(saveRequest.getDatabaseName(), saveRequest.getCollectionName());
        final var dbName = names.dbName();
        final var collName = names.collName();
        final var idError = reconcileSaveId(saveRequest);
        if (idError != null) {
            return idError;
        }
        final var entry = DbEntry.fromJsonObject(dbName, collName, saveRequest.getObject());
        entry.setVersion(stampFor(version));
        final var encodedLine = entry.encodeLine();
        final var sizeError = EntrySizeGuard.check(entry, OperationType.SAVE);
        if (sizeError != null) {
            return sizeError;
        }
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        var foundIndexEntry = -1;
        if (saveRequest.get_id() != null) {
            foundIndexEntry = PkIndexList.positionOf(primaryKeyIndex, saveRequest.get_id());
        }
        var eventType = EventType.CREATED;
        PkIndexEntry savedPkIndexEntry;
        final long pendingGeneration;
        if (foundIndexEntry >= 0) {
            final var idxEntry = primaryKeyIndex.get(foundIndexEntry);
            final var placement = placementFor(dbName, collName, idxEntry, encodedLine.length);
            if (placement == PagePlacement.RELOCATE) {
                // The grown document no longer fits its page; relocate as delete + insert so page metadata
                // and field indexes stay correct through the standard background events.
                return relocateWithListensHeld(dbName, collName, entry, idxEntry, primaryKeyIndex, encodedLine,
                        saveRequest.isBackgroundFollowOn(), sink);
            }
            adoptResidentId(entry, idxEntry);
            entry.setPage(idxEntry.getPage());
            final var previousLength = idxEntry.getLength();
            pendingGeneration = pendingIndexWrites.mark(dbName, collName, entry.get_id());
            savedPkIndexEntry = pendingIndexWrites.writeWhilePending(dbName, collName, List.of(entry.get_id()),
                    pendingGeneration, () -> fs.updateFromCollection(entry, idxEntry, encodedLine, placement,
                            primaryKeyIndex, cache.pageOccupancy(dbName, collName)));
            cache.updatePageSizeForUpdateInMemory(dbName, collName, savedPkIndexEntry.getPage(),
                    savedPkIndexEntry.getLength() - previousLength);
            eventType = EventType.UPDATED;
        } else {
            entry.setPage(cache.selectPageForInsert(dbName, collName, entry.byteSize()));
            pendingGeneration = pendingIndexWrites.mark(dbName, collName, entry.get_id());
            savedPkIndexEntry = pendingIndexWrites.writeWhilePending(dbName, collName, List.of(entry.get_id()),
                    pendingGeneration, () -> fs.insertIntoCollection(entry, encodedLine));
            cache.updatePageSizeInMemory(dbName, collName, savedPkIndexEntry.getPage(), savedPkIndexEntry.getLength());
        }
        if (eventType == EventType.UPDATED) {
            replaceRow(primaryKeyIndex, savedPkIndexEntry);
        } else {
            insertRow(primaryKeyIndex, savedPkIndexEntry);
        }
        cache.addEntryToCache(dbName, collName, entry);
        submit(new EntityEvent(eventType, dbName, collName, entry, CollectionIncarnation.current(dbName, collName),
                pendingGeneration), saveRequest.isBackgroundFollowOn());
        sink.markDirty(dbName, collName);
        cache.signalIfOverBudget();
        CollectionAccessHelper.recordCollectionAccess(dbName, collName);
        return new SaveResponse("Successfully saved", savedPkIndexEntry.getValue(), eventType == EventType.CREATED);
    }

    private static void adoptResidentId(DbEntry entry, PkIndexEntry residentRow) {
        final var residentId = residentRow.getValue();
        entry.adoptId(residentId);
        entry.getData().addProperty(Globals.PK_FIELD, residentId);
    }

    private static Set<String> idSetOf(List<IndexedDbEntry> entries) {
        final var ids = new HashSet<String>(entries.size() * 2);
        for (final var entry : entries) {
            ids.add(entry.get_id());
        }
        return ids;
    }

    private static long stampFor(long version) {
        if (version <= 0) {
            return hybridClock.next();
        }
        hybridClock.observe(version);
        return version;
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest) throws Exception {
        return executeBulkSave(bulkSaveRequest, null, listenManager);
    }

    // A non-null versions list supplies the owner's version per object, so replicas do not assign their own.
    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest, List<Long> versions,
            DirtySink sink) throws Exception {
        return executeBulkSave(bulkSaveRequest, versions, sink, true);
    }

    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest, List<Long> versions,
            DirtySink sink, boolean populateCache) throws Exception {
        final var names = Cache.canonicalNames(bulkSaveRequest.getDatabaseName(), bulkSaveRequest.getCollectionName());
        final var dbName = names.dbName();
        final var collName = names.collName();
        final var entries = new ArrayList<DbEntry>();
        final var objects = bulkSaveRequest.getObjects();
        for (var i = 0; i < objects.size(); i++) {
            final var entry = DbEntry.fromJsonObject(dbName, collName, objects.get(i));
            final var version = versions != null ? versions.get(i) : null;
            entry.setVersion(stampFor(version != null ? version : 0L));
            entries.add(entry);
        }
        final var linesById = new HashMap<String, byte[]>();
        for (var entry : entries) {
            final var encodedLine = entry.encodeLine();
            final var sizeError = EntrySizeGuard.check(entry, OperationType.BULK_SAVE);
            if (sizeError != null) {
                return sizeError;
            }
            linesById.put(entry.get_id(), encodedLine);
        }
        final var seenIds = new HashSet<String>();
        for (var entry : entries) {
            if (!seenIds.add(entry.get_id())) {
                return new OperationResponse(OperationType.BULK_SAVE,
                        "Duplicate _id in bulk save request: " + entry.get_id(), ErrorCode.DUPLICATE_ID);
            }
        }
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var pendingGeneration = pendingIndexWrites.mark(dbName, collName, idsOf(entries));
        final var indexedDbEntriesToUpdate = new ArrayList<IndexedDbEntry>();
        for (var i : entries) {
            final var data = i.getData();
            if (data.has(org.techhouse.config.Globals.PK_FIELD)) {
                final var id = data.get(org.techhouse.config.Globals.PK_FIELD).asJsonString().getValue();
                i.set_id(id);
                final var foundIndexEntry = PkIndexList.positionOf(primaryKeyIndex, id);
                if (foundIndexEntry >= 0) {
                    final var foundIndex = primaryKeyIndex.get(foundIndexEntry);
                    adoptResidentId(i, foundIndex);
                    final var indexedDbEntry = new IndexedDbEntry();
                    indexedDbEntry.setIndex(foundIndex);
                    indexedDbEntry.setDatabaseName(dbName);
                    indexedDbEntry.setCollectionName(collName);
                    indexedDbEntry.set_id(foundIndex.getValue());
                    indexedDbEntry.setData(data);
                    indexedDbEntry.setVersion(i.getVersion());
                    indexedDbEntriesToUpdate.add(indexedDbEntry);
                }
            }
        }
        final List<IndexedDbEntry> updatedIndexEntries = new ArrayList<>();
        if (!indexedDbEntriesToUpdate.isEmpty()) {
            final List<IndexedDbEntry> bulkUpdated;
            try {
                bulkUpdated = fs.bulkUpdateFromCollection(dbName, collName, indexedDbEntriesToUpdate, primaryKeyIndex,
                        updated -> linesById.get(updated.get_id()), cache.pageOccupancy(dbName, collName));
            } catch (Exception e) {
                clearUncommitted(dbName, collName, entries, List.of(), pendingGeneration);
                cache.userCache().evictPkIndex(dbName, collName);
                throw e;
            }
            updatedIndexEntries.addAll(bulkUpdated);
            applyUpdateSizeDeltas(dbName, collName, bulkUpdated);
            PkIndexEdits.replaceAll(primaryKeyIndex, rowsOf(bulkUpdated));
        }
        final var idsToUpdate = idSetOf(indexedDbEntriesToUpdate);
        final var entriesToInsert = entries.stream().filter(dbEntry -> !idsToUpdate.contains(dbEntry.get_id()))
                .toList();
        final var insertedIndexEntries = new ArrayList<IndexedDbEntry>();
        try {
            if (!entriesToInsert.isEmpty()) {
                final var sizes = new int[entriesToInsert.size()];
                for (var index = 0; index < sizes.length; index++) {
                    sizes[index] = linesById.get(entriesToInsert.get(index).get_id()).length;
                }
                final var targets = cache.selectPagesForInsert(dbName, collName, sizes);
                for (var index = 0; index < sizes.length; index++) {
                    entriesToInsert.get(index).setPage(targets[index]);
                }
                insertedIndexEntries.addAll(fs.bulkInsertIntoCollection(dbName, collName, entriesToInsert,
                        DbEntry::getPage, inserted -> linesById.get(inserted.get_id())));
                addInsertedPageSizes(dbName, collName, insertedIndexEntries);
            }
            PkIndexEdits.insertAll(primaryKeyIndex, rowsOf(insertedIndexEntries));
        } catch (Exception e) {
            final var committedUpdates = toDbEntries(updatedIndexEntries);
            final var committedInserts = toDbEntries(insertedIndexEntries);
            publishCommittedWrites(dbName, collName, committedUpdates, committedInserts, pendingGeneration,
                    bulkSaveRequest.isBackgroundFollowOn(), sink, populateCache);
            final var committed = new ArrayList<>(committedUpdates);
            committed.addAll(committedInserts);
            clearUncommitted(dbName, collName, entries, committed, pendingGeneration);
            throw withCommittedPart(e, committedInserts, committedUpdates);
        }
        final var updatedDbEntries = toDbEntries(updatedIndexEntries);
        final var insertedDbEntries = toDbEntries(insertedIndexEntries);
        publishCommittedWrites(dbName, collName, updatedDbEntries, insertedDbEntries, pendingGeneration,
                bulkSaveRequest.isBackgroundFollowOn(), sink, populateCache);
        cache.signalIfOverBudget();
        if (populateCache) {
            CollectionAccessHelper.recordCollectionAccess(dbName, collName);
        }
        return new BulkSaveResponse("Successfully saved entries", idsOf(insertedDbEntries), idsOf(updatedDbEntries));
    }

    private static List<PkIndexEntry> rowsOf(List<IndexedDbEntry> entries) {
        return entries.stream().map(IndexedDbEntry::getIndex).toList();
    }

    private static void addInsertedPageSizes(String dbName, String collName, List<IndexedDbEntry> inserted) {
        final var totalsByPage = new HashMap<Long, long[]>();
        for (final var insertedEntry : inserted) {
            final var index = insertedEntry.getIndex();
            final var totals = totalsByPage.computeIfAbsent(index.getPage(), _ -> new long[2]);
            totals[0] += index.getLength();
            totals[1]++;
        }
        totalsByPage.forEach(
                (page, totals) -> cache.updatePageSizeInMemory(dbName, collName, page, totals[0], (int) totals[1]));
    }

    private static void applyUpdateSizeDeltas(String dbName, String collName, List<IndexedDbEntry> updated) {
        for (final var updatedEntry : updated) {
            final var index = updatedEntry.getIndex();
            cache.updatePageSizeForUpdateInMemory(dbName, collName, index.getPage(),
                    index.getLength() - updatedEntry.getPreviousByteSize());
        }
    }

    private static void publishCommittedWrites(String dbName, String collName, List<DbEntry> updated,
            List<DbEntry> inserted, long pendingGeneration, boolean followOn, DirtySink sink, boolean populateCache) {
        if (populateCache) {
            cache.addEntriesToCache(dbName, collName, updated);
            cache.addEntriesToCache(dbName, collName, inserted);
        } else {
            for (final var entry : updated) {
                cache.evictEntry(dbName, collName, entry.get_id());
            }
        }
        submit(new BulkEntityEvent(dbName, collName, inserted, updated, CollectionIncarnation.current(dbName, collName),
                pendingGeneration), followOn);
        sink.markDirty(dbName, collName);
    }

    private static void clearUncommitted(String dbName, String collName, List<DbEntry> entries, List<DbEntry> committed,
            long pendingGeneration) {
        final var committedIds = new HashSet<>(idsOf(committed));
        final var uncommitted = idsOf(entries).stream().filter(id -> !committedIds.contains(id)).toList();
        pendingIndexWrites.clear(dbName, collName, uncommitted, pendingGeneration);
    }

    private static Exception withCommittedPart(Exception failure, List<DbEntry> inserted, List<DbEntry> updated) {
        if (inserted.isEmpty() && updated.isEmpty()) {
            return failure;
        }
        return new PartialBulkSaveException(
                new BulkSaveResponse("Partially saved entries", idsOf(inserted), idsOf(updated)), failure);
    }

    private static List<DbEntry> toDbEntries(List<IndexedDbEntry> entries) {
        return entries.stream().map(IndexedDbEntry::toDbEntry).toList();
    }

    private static List<String> idsOf(List<DbEntry> entries) {
        return entries.stream().map(DbEntry::get_id).toList();
    }

    private static SaveResponse relocateWithListensHeld(String dbName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, List<PkIndexEntry> primaryKeyIndex, byte[] encodedLine, boolean followOn,
            DirtySink sink) throws Exception {
        try (var batch = listenManager.openBatch(sink, List.of(Cache.getCollectionIdentifier(dbName, collName)))) {
            final var relocatedPkIndexEntry = relocateOnGrowUpdate(dbName, collName, entry, idxEntry, primaryKeyIndex,
                    encodedLine, followOn);
            batch.markDirty(dbName, collName);
            CollectionAccessHelper.recordCollectionAccess(dbName, collName);
            return new SaveResponse("Successfully saved", relocatedPkIndexEntry.getValue());
        }
    }

    public static PagePlacement placementFor(String dbName, String collName, PkIndexEntry idxEntry, int lineLength) {
        final var pageEntry = cache.getAdminPageEntry(dbName, collName, idxEntry.getPage());
        if (pageEntry == null) {
            return null;
        }
        final var liveAfter = pageEntry.getPageSize() - idxEntry.getLength() + lineLength;
        return PagePlacement.forUpdate(liveAfter, fs.pageFileLength(dbName, collName, idxEntry.getPage()), lineLength,
                configuration.getMaxPageSize());
    }

    // The caller must already hold the collection write lock.
    public static PkIndexEntry relocateOnGrowUpdate(String dbName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, List<PkIndexEntry> primaryKeyIndex) throws Exception {
        return relocateOnGrowUpdate(dbName, collName, entry, idxEntry, primaryKeyIndex, null, false);
    }

    private static void replaceRow(List<PkIndexEntry> primaryKeyIndex, PkIndexEntry row) {
        primaryKeyIndex.set(PkIndexList.positionOf(primaryKeyIndex, row.getValue()), row);
    }

    private static void insertRow(List<PkIndexEntry> primaryKeyIndex, PkIndexEntry row) {
        final var found = PkIndexList.positionOf(primaryKeyIndex, row.getValue());
        primaryKeyIndex.add(found < 0 ? -(found + 1) : found, row);
    }

    private static PkIndexEntry relocateOnGrowUpdate(String dbName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, List<PkIndexEntry> primaryKeyIndex, byte[] encodedLine, boolean followOn)
            throws Exception {
        final var oldEntry = cache.getById(dbName, collName, idxEntry);
        oldEntry.setPage(idxEntry.getPage());
        oldEntry.setVersion(idxEntry.getVersion());
        final var line = encodedLine != null ? encodedLine : entry.encodeLine();
        final var targetPage = cache.selectPageForInsert(dbName, collName, line.length);
        final var deleteGeneration = pendingIndexWrites.mark(dbName, collName, entry.get_id());
        final var createGeneration = pendingIndexWrites.mark(dbName, collName, entry.get_id());
        final PkIndexEntry relocatedPkIndexEntry;
        try {
            relocatedPkIndexEntry = pendingIndexWrites.writeWhilePending(dbName, collName, List.of(entry.get_id()),
                    deleteGeneration, () -> fs.relocate(entry, idxEntry, targetPage, line, primaryKeyIndex,
                            cache.pageOccupancy(dbName, collName)));
        } catch (Exception e) {
            pendingIndexWrites.clear(dbName, collName, entry.get_id(), createGeneration);
            throw e;
        }
        cache.updatePageSizeInMemory(dbName, collName, idxEntry.getPage(), -idxEntry.getLength(), -1);
        cache.evictEntry(dbName, collName, entry.get_id());
        submit(new EntityEvent(EventType.DELETED, dbName, collName, detachedCopy(oldEntry),
                CollectionIncarnation.current(dbName, collName), deleteGeneration), followOn);
        cache.updatePageSizeInMemory(dbName, collName, relocatedPkIndexEntry.getPage(),
                relocatedPkIndexEntry.getLength());
        replaceRow(primaryKeyIndex, relocatedPkIndexEntry);
        cache.addEntryToCache(dbName, collName, entry);
        submit(new EntityEvent(EventType.CREATED, dbName, collName, entry,
                CollectionIncarnation.current(dbName, collName), createGeneration), followOn);
        return relocatedPkIndexEntry;
    }

    private static void submit(Event event, boolean followOn) {
        if (followOn) {
            taskManager.submitFollowOn(event);
        } else {
            taskManager.submitBackgroundTask(event);
        }
    }

    private static DbEntry detachedCopy(DbEntry entry) {
        final var copy = DbEntry.fromJsonObject(entry.getDatabaseName(), entry.getCollectionName(), entry.getData());
        copy.set_id(entry.get_id());
        copy.setPage(entry.getPage());
        copy.setVersion(entry.getVersion());
        copy.setPreviousByteSize(entry.getPreviousByteSize());
        return copy;
    }
}
