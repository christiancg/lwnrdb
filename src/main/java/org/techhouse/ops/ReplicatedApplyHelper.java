package org.techhouse.ops;

import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.WriteVersion;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.resp.BulkSaveResponse;

/**
 * Applies a replicated write received from a collection's owner onto this (replica) node. Reuses the same
 * execute* helpers as the normal write path under the collection write lock; it never re-replicates because
 * replication is triggered only from the owner's OperationProcessor write handlers, not from the helpers.
 */
public final class ReplicatedApplyHelper {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Logger logger = Logger.logFor(ReplicatedApplyHelper.class);

    private ReplicatedApplyHelper() {
    }

    public static boolean apply(ReplicationPayload payload) {
        if (payload == null || payload.getOp() == null) {
            return false;
        }
        final var dbName = payload.getDbName();
        final var collName = payload.getCollName();
        try {
            locks.lock(dbName, collName);
            return applyLocked(payload);
        } catch (Exception e) {
            logger.error("Failed to apply replicated write to " + dbName + "|" + collName, e);
            return false;
        } finally {
            locks.release(dbName, collName);
        }
    }

    // Applies a single replication entry assuming the caller already holds the collection write lock. Used by
    // the transaction-batch apply path, which locks every involved collection up front for one atomic window.
    static boolean applyLocked(ReplicationPayload payload) throws Exception {
        return switch (payload.getOp()) {
            case UPSERT -> applyUpsert(payload);
            case DELETE -> applyDelete(payload);
        };
    }

    private static boolean applyUpsert(ReplicationPayload payload) throws Exception {
        final var request = new BulkSaveRequest(payload.getDbName(), payload.getCollName());
        request.setObjects(payload.getDocuments());
        return SaveOperationHelper.executeBulkSave(request, payload.getVersions()) instanceof BulkSaveResponse;
    }

    private static boolean applyDelete(ReplicationPayload payload) throws Exception {
        final var ids = payload.getIds();
        final var versions = payload.getVersions();
        for (var i = 0; i < ids.size(); i++) {
            final var id = ids.get(i);
            final var request = new DeleteRequest(payload.getDbName(), payload.getCollName());
            request.set_id(id);
            // A missing id (ENTRY_NOT_FOUND) is treated as already-applied: the end state (absent) matches.
            DeleteOperationHelper.executeDelete(request);
            // Record the tombstone with the owner's version even when the doc was absent, so this replica
            // will not resurrect it during anti-entropy and stays convergent on the delete.
            if (versions != null && i < versions.size()) {
                // Versions arriving over the wire deserialize as a boxed Integer/Long/Double; the Number
                // supertype reads the value as a long regardless of the concrete boxed type.
                final Number version = versions.get(i);
                fs.appendTombstone(payload.getDbName(), payload.getCollName(), id, version.longValue());
                WriteVersion.observe(version.longValue());
            }
        }
        return true;
    }
}
