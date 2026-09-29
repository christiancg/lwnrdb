package org.techhouse.unit.ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.TriggerHelper;
import org.techhouse.ops.tx.CommittedOpTriggers;
import org.techhouse.test.TestGlobals;

public class CommittedOpTriggersFenceTest {
    private static final String ACTING_USER = "fence-owner";
    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";

    private Transaction transaction;
    private long nextSeq;

    @BeforeEach
    void reset() {
        transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        nextSeq = 0;
    }

    private static String fenceKeyOf(String collName, String id) {
        return Cache.getCollectionIdentifier(TestGlobals.DB, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + id;
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("value", new JsonString("v"));
        return object;
    }

    private AdminTransactionEntry op(String opType, JsonObject payload, String... insertedIds) {
        final var seq = nextSeq++;
        if (insertedIds.length > 0) {
            transaction.recordInserts(seq, List.of(insertedIds));
        }
        return new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", seq, opType,
                TestGlobals.DB, TestGlobals.COLL, payload);
    }

    private AdminTransactionEntry saveOp(String id, boolean inserted) {
        return inserted
                ? op(AdminTransactionEntry.OP_TYPE_SAVE, document(id), id)
                : op(AdminTransactionEntry.OP_TYPE_SAVE, document(id));
    }

    private AdminTransactionEntry deleteOp(String id) {
        final var payload = new JsonObject();
        payload.add(Globals.PK_FIELD, new JsonString(id));
        payload.add(DELETED_DOCUMENT_FIELD, document(id));
        return op(AdminTransactionEntry.OP_TYPE_DELETE, payload);
    }

    private AdminTransactionEntry bulkSaveOpInserting(List<String> ids) {
        final var objects = new JsonArray();
        ids.forEach(id -> objects.add(document(id)));
        final var payload = new JsonObject();
        payload.add(OBJECTS_FIELD, objects);
        return op(AdminTransactionEntry.OP_TYPE_BULK_SAVE, payload, ids.toArray(new String[0]));
    }

    private void fire(List<AdminTransactionEntry> ops, Set<String> fencedIds) {
        CommittedOpTriggers.fireForCommittedOps(ops, ACTING_USER, transaction.getTriggerDepth(), transaction,
                fencedIds);
    }

    @Test
    public void test_a_fenced_delete_does_not_fire_its_deleted_trigger() {
        final var ops = List.of(deleteOp("fenced-del"));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of(fenceKeyOf(TestGlobals.COLL, "fenced-del")));

            triggers.verify(() -> TriggerHelper.afterWrite(anyString(), anyString(), eq(EventType.DELETED),
                    any(DbEntry.class), anyString(), anyInt()), never());
        }
    }

    @Test
    public void test_an_unfenced_delete_still_fires_its_deleted_trigger() {
        final var ops = List.of(deleteOp("kept-del"));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWrite(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.DELETED), any(DbEntry.class), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_a_delete_fenced_in_another_collection_still_fires_here() {
        final var ops = List.of(deleteOp("shared-id"));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of(fenceKeyOf("otherCollection", "shared-id")));

            triggers.verify(() -> TriggerHelper.afterWrite(anyString(), anyString(), eq(EventType.DELETED),
                    any(DbEntry.class), anyString(), anyInt()), times(1));
        }
    }

    @Test
    public void test_a_fenced_save_still_fires_because_the_fence_cannot_prove_it_was_skipped() {
        final var ops = List.of(saveOp("fenced-save", true));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of(fenceKeyOf(TestGlobals.COLL, "fenced-save")));

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("fenced-save")), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_an_unfenced_save_fires_created_for_the_id_it_inserted() {
        final var ops = List.of(saveOp("kept-save", true));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("kept-save")), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_an_unfenced_save_of_an_existing_id_fires_updated() {
        final var ops = List.of(saveOp("kept-update", false));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.UPDATED), eq(List.of("kept-update")), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_a_partly_fenced_bulk_save_still_fires_for_every_object() {
        final var ops = List.of(bulkSaveOpInserting(List.of("bulk-fenced", "bulk-kept")));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of(fenceKeyOf(TestGlobals.COLL, "bulk-fenced")));

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("bulk-fenced", "bulk-kept")), eq(ACTING_USER), anyInt()),
                    times(1));
        }
    }

    @Test
    public void test_a_fenced_delete_does_not_suppress_its_unfenced_neighbour() {
        final var ops = List.of(deleteOp("fenced-del"), saveOp("kept-save", true));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of(fenceKeyOf(TestGlobals.COLL, "fenced-del")));

            triggers.verify(() -> TriggerHelper.afterWrite(anyString(), anyString(), eq(EventType.DELETED),
                    any(DbEntry.class), anyString(), anyInt()), never());
            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("kept-save")), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_the_no_fence_overload_skips_nothing() {
        final var ops = List.of(deleteOp("overload-del"));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            CommittedOpTriggers.fireForCommittedOps(ops, ACTING_USER, transaction.getTriggerDepth(), transaction);

            triggers.verify(() -> TriggerHelper.afterWrite(anyString(), anyString(), eq(EventType.DELETED),
                    any(DbEntry.class), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_two_saves_of_the_same_id_in_one_transaction_fire_updated_once() {
        final var ops = List.of(saveOp("dup", false), saveOp("dup", false));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.UPDATED), eq(List.of("dup")), eq(ACTING_USER), anyInt()), times(1));
            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of()), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_an_insert_followed_by_an_update_of_the_same_id_in_one_transaction_fires_created_once() {
        final var ops = List.of(saveOp("dup-ins", true), saveOp("dup-ins", false));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("dup-ins")), eq(ACTING_USER), anyInt()), times(1));
            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.UPDATED), eq(List.of()), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_a_save_then_a_bulk_save_of_the_same_id_collapse_to_one_fire() {
        final var ops = List.of(saveOp("dup-mixed", true), bulkSaveOpInserting(List.of("dup-mixed", "other")));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("dup-mixed", "other")), eq(ACTING_USER), anyInt()), times(1));
        }
    }

    @Test
    public void test_two_saves_of_different_ids_still_fire_separately() {
        final var ops = List.of(saveOp("a", true), saveOp("b", false));

        try (var triggers = mockStatic(TriggerHelper.class)) {
            fire(ops, Set.of());

            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("a")), eq(ACTING_USER), anyInt()), times(1));
            triggers.verify(() -> TriggerHelper.afterWriteIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.UPDATED), eq(List.of("b")), eq(ACTING_USER), anyInt()), times(1));
        }
    }
}
