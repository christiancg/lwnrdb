package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.TriggerHelper;

public final class CommittedOpTriggers {
    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";

    private CommittedOpTriggers() {
    }

    public static void fireForCommittedOps(List<AdminTransactionEntry> ops, String actingUser, int triggerDepth,
            Transaction transaction) {
        fireForCommittedOps(ops, actingUser, triggerDepth, transaction, Set.of());
    }

    public static void fireForCommittedOps(List<AdminTransactionEntry> ops, String actingUser, int triggerDepth,
            Transaction transaction, Set<String> fencedIds) {
        final var saveWrites = new LinkedHashMap<TargetKey, LinkedHashMap<String, Boolean>>();
        for (final var op : ops) {
            final var target = new TargetKey(op.getTargetDb(), op.getTargetColl());
            final var inserted = transaction.insertedIdsFor(op.getSeq());
            switch (op.getOpType()) {
                case AdminTransactionEntry.OP_TYPE_SAVE -> {
                    final var id = op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue();
                    recordSaveWrite(saveWrites, target, id, inserted.contains(id));
                }
                case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                    for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                        final var object = element.asJsonObject();
                        if (object.has(Globals.PK_FIELD)) {
                            final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                            recordSaveWrite(saveWrites, target, id, inserted.contains(id));
                        }
                    }
                }
                case AdminTransactionEntry.OP_TYPE_DELETE ->
                    fireForDelete(op, target, saveWrites, actingUser, triggerDepth, fencedIds);
                default -> {
                }
            }
        }
        for (final var entry : saveWrites.entrySet()) {
            final var target = entry.getKey();
            final var createdIds = new ArrayList<String>();
            final var updatedIds = new ArrayList<String>();
            entry.getValue().forEach((id, wasInserted) -> (wasInserted ? createdIds : updatedIds).add(id));
            TriggerHelper.afterWriteIds(target.dbName(), target.collName(), EventType.CREATED, createdIds, actingUser,
                    triggerDepth);
            TriggerHelper.afterWriteIds(target.dbName(), target.collName(), EventType.UPDATED, updatedIds, actingUser,
                    triggerDepth);
        }
    }

    private static void recordSaveWrite(Map<TargetKey, LinkedHashMap<String, Boolean>> saveWrites, TargetKey target,
            String id, boolean wasInserted) {
        saveWrites.computeIfAbsent(target, _ -> new LinkedHashMap<>()).merge(id, wasInserted,
                (existing, now) -> existing || now);
    }

    private static void fireForDelete(AdminTransactionEntry op, TargetKey target,
            Map<TargetKey, LinkedHashMap<String, Boolean>> saveWrites, String actingUser, int triggerDepth,
            Set<String> fencedIds) {
        final var payload = op.getPayload();
        final var id = payload.get(Globals.PK_FIELD).asJsonString().getValue();
        final var writesToTarget = saveWrites.get(target);
        final var createdInThisTransaction = writesToTarget != null && Boolean.TRUE.equals(writesToTarget.remove(id));
        if (createdInThisTransaction || !payload.has(DELETED_DOCUMENT_FIELD)
                || deleteDidNotApply(fencedIds, target.dbName(), target.collName(), id)) {
            return;
        }
        TriggerHelper.afterWrite(
                target.dbName(), target.collName(), EventType.DELETED, DbEntry.fromJsonObject(target.dbName(),
                        target.collName(), payload.get(DELETED_DOCUMENT_FIELD).asJsonObject()),
                actingUser, triggerDepth);
    }

    private record TargetKey(String dbName, String collName) {
    }

    // A fenced delete provably never applied: had it, the id would be absent from pk.idx and so could not
    // have been fenced. A fenced save is the documented version-fence residual and still fires.
    private static boolean deleteDidNotApply(Set<String> fencedIds, String dbName, String collName, String id) {
        return !fencedIds.isEmpty() && fencedIds.contains(TransactionRecovery.fenceKey(dbName, collName, id));
    }
}
