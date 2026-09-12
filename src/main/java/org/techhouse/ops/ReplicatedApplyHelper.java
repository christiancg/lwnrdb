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

// Never re-replicates: replication fires only from the owner's OperationProcessor write handlers.
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

    // The caller must already hold the collection write lock.
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
            // Tombstone with the owner's version even when absent, so anti-entropy cannot resurrect it.
            if (versions != null && i < versions.size()) {
                // Versions deserialize as a boxed Integer/Long/Double, so read them through Number.
                final Number version = versions.get(i);
                fs.appendTombstone(payload.getDbName(), payload.getCollName(), id, version.longValue());
                WriteVersion.observe(version.longValue());
            }
        }
        return true;
    }
}
