package org.techhouse.ops.tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.TransactionOpFailedException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;

public final class VersionedApply {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final String OBJECTS_FIELD = "objects";

    private final Map<String, Map<String, Long>> tombstonesByCollection = new HashMap<>();
    private final Map<String, ApplyOutcome> outcomes = new HashMap<>();

    public Map<String, ApplyOutcome> outcomes() {
        return outcomes;
    }

    public static String key(String dbName, String collName, String id) {
        return Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + id;
    }

    public void apply(AdminTransactionEntry op) throws Exception {
        final var dbName = op.getTargetDb();
        final var collName = op.getTargetColl();
        switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE -> applySave(dbName, collName, op.getPayload(), op.versionAt(0));
            case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> applyBulkSave(op);
            case AdminTransactionEntry.OP_TYPE_DELETE ->
                applyDelete(dbName, collName, idOf(op.getPayload()), op.versionAt(0));
            case AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN ->
                AdminOperationHelper.deleteTriggerRuns(TriggerRunLog.recordIdsFor(op.consumedTriggerRunId()));
            default -> throw new IllegalStateException("Unknown transaction op type: " + op.getOpType());
        }
    }

    private void applySave(String dbName, String collName, JsonObject object, long version) throws Exception {
        final var id = idOf(object);
        if (record(dbName, collName, id, saveOutcome(dbName, collName, id, version)) != ApplyOutcome.APPLIED) {
            return;
        }
        final var request = new SaveRequest(dbName, collName);
        request.setObject(object);
        request.set_id(id);
        requireApplied(AdminTransactionEntry.OP_TYPE_SAVE, SaveOperationHelper.executeSave(request, version));
    }

    private void applyBulkSave(AdminTransactionEntry op) throws Exception {
        final var dbName = op.getTargetDb();
        final var collName = op.getTargetColl();
        final var objects = new ArrayList<JsonObject>();
        final var versions = new ArrayList<Long>();
        final var elements = op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList();
        for (var i = 0; i < elements.size(); i++) {
            final var object = elements.get(i).asJsonObject();
            final var id = idOf(object);
            final var version = op.versionAt(i);
            if (record(dbName, collName, id, saveOutcome(dbName, collName, id, version)) == ApplyOutcome.APPLIED) {
                objects.add(object);
                versions.add(version);
            }
        }
        if (objects.isEmpty()) {
            return;
        }
        final var request = new BulkSaveRequest(dbName, collName);
        request.setObjects(objects);
        requireApplied(AdminTransactionEntry.OP_TYPE_BULK_SAVE, SaveOperationHelper.executeBulkSave(request, versions));
    }

    private void applyDelete(String dbName, String collName, String id, long version) throws Exception {
        final var stored = storedEntry(dbName, collName, id);
        final var outcome = ApplyOutcome.forDelete(stored == null ? 0L : stored.getVersion(), stored != null, version);
        if (record(dbName, collName, id, outcome) != ApplyOutcome.APPLIED) {
            return;
        }
        final var request = new DeleteRequest(dbName, collName);
        request.set_id(id);
        DeleteOperationHelper.executeDelete(request);
    }

    private ApplyOutcome record(String dbName, String collName, String id, ApplyOutcome outcome) {
        outcomes.put(key(dbName, collName, id), outcome);
        return outcome;
    }

    private ApplyOutcome saveOutcome(String dbName, String collName, String id, long version) throws IOException {
        final var stored = storedEntry(dbName, collName, id);
        if (stored != null) {
            return ApplyOutcome.forSave(stored.getVersion(), 0L, version);
        }
        return ApplyOutcome.forSave(0L, tombstoneVersion(dbName, collName, id), version);
    }

    private long tombstoneVersion(String dbName, String collName, String id) throws IOException {
        final var collId = Cache.getCollectionIdentifier(dbName, collName);
        var tombstones = tombstonesByCollection.get(collId);
        if (tombstones == null) {
            tombstones = fs.tombstones().read(dbName, collName);
            tombstonesByCollection.put(collId, tombstones);
        }
        return tombstones.getOrDefault(id, 0L);
    }

    public static PkIndexEntry storedEntry(String dbName, String collName, String id) throws IOException {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final var position = Collections.binarySearch(primaryKeyIndex, id);
        return position >= 0 ? primaryKeyIndex.get(position) : null;
    }

    private static String idOf(JsonObject object) {
        return object.get(Globals.PK_FIELD).asJsonString().getValue();
    }

    private static void requireApplied(String opType, OperationResponse response) {
        if (response == null || response.getStatus() == OperationStatus.OK) {
            return;
        }
        throw new TransactionOpFailedException(opType, response.getErrorCode(), response.getMessage());
    }
}
