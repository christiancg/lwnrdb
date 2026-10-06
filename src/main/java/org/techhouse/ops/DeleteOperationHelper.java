package org.techhouse.ops;

import java.util.Comparator;
import java.util.List;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.admin.CollectionIncarnation;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.OperationResponse;

public final class DeleteOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private DeleteOperationHelper() {
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeDelete(DeleteRequest deleteRequest) throws Exception {
        final var dbName = deleteRequest.getDatabaseName();
        final var collName = deleteRequest.getCollectionName();
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var foundIndexEntry = primaryKeyIndex.stream()
                .filter(pkIndexEntry -> pkIndexEntry.getValue().equals(deleteRequest.get_id())).findFirst();
        if (foundIndexEntry.isPresent()) {
            final var idxEntry = foundIndexEntry.get();
            final var entryToBeDeleted = cache.getById(dbName, collName, idxEntry);
            entryToBeDeleted.setPage(idxEntry.getPage());
            final var pendingGeneration = pendingIndexWrites.mark(dbName, collName, entryToBeDeleted.get_id());
            final var compaction = pendingIndexWrites.writeWhilePending(dbName, collName,
                    List.of(entryToBeDeleted.get_id()), pendingGeneration, () -> fs.deleteFromCollection(idxEntry));
            cache.shiftPkPositionsAfterCompaction(compaction);
            primaryKeyIndex.remove(idxEntry);
            primaryKeyIndex.sort(Comparator.comparing(PkIndexEntry::getValue));
            cache.evictEntry(dbName, collName, entryToBeDeleted.get_id());
            taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, dbName, collName, entryToBeDeleted,
                    CollectionIncarnation.current(dbName, collName), pendingGeneration));
            listenManager.markDirty(dbName, collName);
            CollectionAccessHelper.recordCollectionAccess(dbName, collName);
            return new DeleteResponse("Entry with id " + deleteRequest.get_id() + " deleted successfully");
        } else {
            return new OperationResponse(OperationType.DELETE, "Entry with id " + deleteRequest.get_id() + " not found",
                    ErrorCode.ENTRY_NOT_FOUND);
        }
    }
}
