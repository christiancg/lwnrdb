package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ops.StagedTriggerRuns;
import org.techhouse.ops.TriggerHelper;

public final class CommittedOpTriggers {
    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";

    private CommittedOpTriggers() {
    }

    public static StagedTriggerRuns stage(List<AdminTransactionEntry> ops, String actingUser, int triggerDepth,
            Transaction transaction, Map<String, ApplyOutcome> outcomes, String txId) {
        final var saveWrites = new LinkedHashMap<TargetKey, LinkedHashMap<String, Boolean>>();
        final var deletes = new LinkedHashMap<TargetKey, List<DbEntry>>();
        for (final var op : ops) {
            final var target = new TargetKey(op.getTargetDb(), op.getTargetColl());
            final var inserted = transaction.insertedIdsFor(op.getSeq());
            switch (op.getOpType()) {
                case AdminTransactionEntry.OP_TYPE_SAVE -> {
                    final var id = op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue();
                    recordSaveWrite(saveWrites, target, id, inserted.contains(id), outcomes);
                }
                case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                    for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                        final var object = element.asJsonObject();
                        if (object.has(Globals.PK_FIELD)) {
                            final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                            recordSaveWrite(saveWrites, target, id, inserted.contains(id), outcomes);
                        }
                    }
                }
                case AdminTransactionEntry.OP_TYPE_DELETE -> {
                    final var deleted = deletedEntryOf(op, target, saveWrites, outcomes);
                    if (deleted != null) {
                        deletes.computeIfAbsent(target, _ -> new ArrayList<>()).add(deleted);
                    }
                }
                default -> {
                }
            }
        }
        var staged = StagedTriggerRuns.none();
        for (final var entry : deletes.entrySet()) {
            final var target = entry.getKey();
            staged = StagedTriggerRuns.combine(staged, TriggerHelper.stageCommitted(target.dbName(), target.collName(),
                    EventType.DELETED, entry.getValue(), actingUser, triggerDepth, txId));
        }
        for (final var entry : saveWrites.entrySet()) {
            final var target = entry.getKey();
            final var createdIds = new ArrayList<String>();
            final var updatedIds = new ArrayList<String>();
            entry.getValue().forEach((id, wasInserted) -> (wasInserted ? createdIds : updatedIds).add(id));
            staged = StagedTriggerRuns.combine(staged, TriggerHelper.stageCommittedIds(target.dbName(),
                    target.collName(), EventType.CREATED, createdIds, actingUser, triggerDepth, txId));
            staged = StagedTriggerRuns.combine(staged, TriggerHelper.stageCommittedIds(target.dbName(),
                    target.collName(), EventType.UPDATED, updatedIds, actingUser, triggerDepth, txId));
        }
        return staged;
    }

    private static void recordSaveWrite(Map<TargetKey, LinkedHashMap<String, Boolean>> saveWrites, TargetKey target,
            String id, boolean wasInserted, Map<String, ApplyOutcome> outcomes) {
        if (skippedByReplay(outcomes, target, id)) {
            return;
        }
        saveWrites.computeIfAbsent(target, _ -> new LinkedHashMap<>()).merge(id, wasInserted,
                (existing, now) -> existing || now);
    }

    private static DbEntry deletedEntryOf(AdminTransactionEntry op, TargetKey target,
            Map<TargetKey, LinkedHashMap<String, Boolean>> saveWrites, Map<String, ApplyOutcome> outcomes) {
        final var payload = op.getPayload();
        final var id = payload.get(Globals.PK_FIELD).asJsonString().getValue();
        final var writesToTarget = saveWrites.get(target);
        final var createdInThisTransaction = writesToTarget != null && Boolean.TRUE.equals(writesToTarget.remove(id));
        if (createdInThisTransaction || !payload.has(DELETED_DOCUMENT_FIELD) || skippedByReplay(outcomes, target, id)) {
            return null;
        }
        return DbEntry.fromJsonObject(target.dbName(), target.collName(),
                payload.get(DELETED_DOCUMENT_FIELD).asJsonObject());
    }

    private record TargetKey(String dbName, String collName) {
    }

    private static boolean skippedByReplay(Map<String, ApplyOutcome> outcomes, TargetKey target, String id) {
        return !outcomes.getOrDefault(VersionedApply.key(target.dbName(), target.collName(), id), ApplyOutcome.APPLIED)
                .firesTriggers();
    }
}
