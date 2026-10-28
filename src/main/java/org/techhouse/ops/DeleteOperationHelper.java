package org.techhouse.ops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EntityRef;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.PkIndexList;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.DirtySink;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class DeleteOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private DeleteOperationHelper() {
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeDelete(DeleteRequest deleteRequest) throws Exception {
        return executeDelete(deleteRequest, listenManager);
    }

    public static OperationResponse executeDelete(DeleteRequest deleteRequest, DirtySink sink) throws Exception {
        final var dbName = deleteRequest.getDatabaseName();
        final var collName = deleteRequest.getCollectionName();
        final var id = deleteRequest.get_id();
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var found = PkIndexList.positionOf(primaryKeyIndex, id);
        if (found < 0) {
            return new OperationResponse(OperationType.DELETE, "Entry with id " + id + " not found",
                    ErrorCode.ENTRY_NOT_FOUND);
        }
        final var idxEntry = primaryKeyIndex.get(found);
        final var entryToBeDeleted = cache.getById(dbName, collName, idxEntry);
        entryToBeDeleted.setPage(idxEntry.getPage());
        final var pendingGeneration = pendingIndexWrites.mark(dbName, collName, entryToBeDeleted.get_id());
        pendingIndexWrites.writeWhilePending(dbName, collName, List.of(entryToBeDeleted.get_id()), pendingGeneration,
                () -> {
                    fs.deleteFromCollection(idxEntry, primaryKeyIndex, cache.pageOccupancy(dbName, collName));
                    return null;
                });
        cache.updatePageSizeInMemory(dbName, collName, idxEntry.getPage(), -idxEntry.getLength(), -1);
        primaryKeyIndex.remove(PkIndexList.positionOf(primaryKeyIndex, id));
        cache.evictEntry(dbName, collName, entryToBeDeleted.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, entryToBeDeleted,
                CollectionIncarnation.current(dbName, collName), pendingGeneration));
        sink.markDirty(dbName, collName);
        CollectionAccessHelper.recordCollectionAccess(dbName, collName);
        return new DeleteResponse("Entry with id " + id + " deleted successfully");
    }

    public static List<String> executeBulkDelete(String dbName, String collName, List<String> ids, DirtySink sink,
            boolean populateCache) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var rows = rowsOf(primaryKeyIndex, ids);
        if (rows.isEmpty()) {
            return List.of();
        }
        final var deletedIds = new ArrayList<String>(rows.size());
        for (final var row : rows) {
            deletedIds.add(row.getValue());
        }
        final var pendingGeneration = pendingIndexWrites.mark(dbName, collName, deletedIds);
        pendingIndexWrites.writeWhilePending(dbName, collName, deletedIds, pendingGeneration,
                () -> fs.writeBatch(dbName, collName, List.of(), DbEntry::getPage, _ -> null, rows));
        final var freed = new TreeMap<Long, long[]>();
        for (final var row : rows) {
            final var page = freed.computeIfAbsent(row.getPage(), _ -> new long[2]);
            page[0] += row.getLength();
            page[1]++;
        }
        freed.forEach((page, bytesAndRows) -> cache.updatePageSizeInMemory(dbName, collName, page, -bytesAndRows[0],
                (int) -bytesAndRows[1]));
        PkIndexEdits.removeAll(primaryKeyIndex, deletedIds);
        for (final var id : deletedIds) {
            cache.evictEntry(dbName, collName, id);
        }
        fs.vacuumPagesAfterDelete(dbName, collName, freed.keySet(), primaryKeyIndex,
                cache.pageOccupancy(dbName, collName));
        final var incarnation = CollectionIncarnation.current(dbName, collName);
        for (final var row : rows) {
            taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName,
                    new EntityRef(row.getValue(), row.getPage(), row.getLength(), 0L), incarnation, pendingGeneration));
        }
        sink.markDirty(dbName, collName);
        if (populateCache) {
            CollectionAccessHelper.recordCollectionAccess(dbName, collName);
        }
        return deletedIds;
    }

    private static List<PkIndexEntry> rowsOf(List<PkIndexEntry> primaryKeyIndex, List<String> ids) {
        final var rows = new ArrayList<PkIndexEntry>(ids.size());
        for (final var id : new LinkedHashSet<>(ids)) {
            final var row = PkIndexList.rowOf(primaryKeyIndex, id);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }
}
