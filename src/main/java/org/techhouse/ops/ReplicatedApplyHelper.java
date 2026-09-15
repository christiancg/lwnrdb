package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Collections;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
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
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
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
        final var documents = payload.getDocuments();
        final var versions = payload.getVersions();
        final var acceptedDocuments = new ArrayList<JsonObject>();
        final var acceptedVersions = new ArrayList<Long>();
        for (var i = 0; i < documents.size(); i++) {
            final var document = documents.get(i);
            Number version = null;
            if (versions != null && i < versions.size()) {
                version = versions.get(i);
            }
            if (version == null) {
                acceptedDocuments.add(document);
                acceptedVersions.add(null);
                continue;
            }
            hybridClock.observe(version.longValue());
            if (isSupersededLocally(payload.getDbName(), payload.getCollName(), document, version.longValue())) {
                continue;
            }
            acceptedDocuments.add(document);
            acceptedVersions.add(version.longValue());
        }
        if (acceptedDocuments.isEmpty()) {
            return true;
        }
        final var request = new BulkSaveRequest(payload.getDbName(), payload.getCollName());
        request.setObjects(acceptedDocuments);
        return SaveOperationHelper.executeBulkSave(request, acceptedVersions) instanceof BulkSaveResponse;
    }

    private static boolean isSupersededLocally(String dbName, String collName, JsonObject document, long version)
            throws java.io.IOException {
        final var idElement = document.get(Globals.PK_FIELD);
        if (!(idElement instanceof JsonString jsonString)) {
            return false;
        }
        final var stored = storedVersionOf(dbName, collName, jsonString.getValue());
        return stored != null && stored > version;
    }

    private static Long storedVersionOf(String dbName, String collName, String id) throws java.io.IOException {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var found = Collections.binarySearch(primaryKeyIndex, id);
        return found >= 0 ? primaryKeyIndex.get(found).getVersion() : null;
    }

    private static boolean applyDelete(ReplicationPayload payload) throws Exception {
        final var ids = payload.getIds();
        final var versions = payload.getVersions();
        for (var i = 0; i < ids.size(); i++) {
            final var id = ids.get(i);
            // Versions deserialize as a boxed Integer/Long/Double, so read them through Number.
            Number version = null;
            if (versions != null && i < versions.size()) {
                version = versions.get(i);
            }
            // Tombstone with the owner's version even when absent, so anti-entropy cannot resurrect it.
            if (version != null) {
                fs.appendTombstone(payload.getDbName(), payload.getCollName(), id, version.longValue());
                hybridClock.observe(version.longValue());
            }
            final var stored = storedVersionOf(payload.getDbName(), payload.getCollName(), id);
            if (version != null && stored != null && stored > version.longValue()) {
                continue;
            }
            final var request = new DeleteRequest(payload.getDbName(), payload.getCollName());
            request.set_id(id);
            // A missing id (ENTRY_NOT_FOUND) is treated as already-applied: the end state (absent) matches.
            DeleteOperationHelper.executeDelete(request);
        }
        return true;
    }
}
