package org.techhouse.ops.tx;

import java.util.ArrayList;
import java.util.List;
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
        for (final var op : ops) {
            final var dbName = op.getTargetDb();
            final var collName = op.getTargetColl();
            // Which ids the op created rather than updated was decided when the write was buffered; by now all
            // documents exist, so an insert can no longer be told apart from an update here.
            final var inserted = transaction.insertedIdsFor(op.getSeq());
            switch (op.getOpType()) {
                case AdminTransactionEntry.OP_TYPE_SAVE -> {
                    final var id = op.getPayload().get(Globals.PK_FIELD).asJsonString().getValue();
                    TriggerHelper.afterWriteIds(dbName, collName,
                            inserted.contains(id) ? EventType.CREATED : EventType.UPDATED, List.of(id), actingUser,
                            triggerDepth);
                }
                case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> {
                    final var createdIds = new ArrayList<String>();
                    final var updatedIds = new ArrayList<String>();
                    for (final var element : op.getPayload().get(OBJECTS_FIELD).asJsonArray().asList()) {
                        final var object = element.asJsonObject();
                        if (object.has(Globals.PK_FIELD)) {
                            final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
                            (inserted.contains(id) ? createdIds : updatedIds).add(id);
                        }
                    }
                    TriggerHelper.afterWriteIds(dbName, collName, EventType.CREATED, createdIds, actingUser,
                            triggerDepth);
                    TriggerHelper.afterWriteIds(dbName, collName, EventType.UPDATED, updatedIds, actingUser,
                            triggerDepth);
                }
                // The deleted document was captured when the delete was buffered; re-reading it by id here
                // would find nothing. Absent when no DELETED trigger existed at buffer time.
                case AdminTransactionEntry.OP_TYPE_DELETE -> {
                    final var payload = op.getPayload();
                    final var id = payload.get(Globals.PK_FIELD).asJsonString().getValue();
                    if (payload.has(DELETED_DOCUMENT_FIELD) && !deleteDidNotApply(fencedIds, dbName, collName, id)) {
                        TriggerHelper
                                .afterWrite(dbName, collName, EventType.DELETED,
                                        DbEntry.fromJsonObject(dbName, collName,
                                                payload.get(DELETED_DOCUMENT_FIELD).asJsonObject()),
                                        actingUser, triggerDepth);
                    }
                }
                default -> {
                    // Markers and the trigger-run consume op are not writes and fire nothing.
                }
            }
        }
    }

    // A fenced delete provably never applied: had it, the id would be absent from pk.idx and so could not
    // have been fenced. A fenced save is the documented version-fence residual and still fires.
    private static boolean deleteDidNotApply(Set<String> fencedIds, String dbName, String collName, String id) {
        return !fencedIds.isEmpty() && fencedIds.contains(TransactionRecovery.fenceKey(dbName, collName, id));
    }
}
