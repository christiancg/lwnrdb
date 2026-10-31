package org.techhouse.unit.ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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
import org.techhouse.ops.tx.VersionedApply.ApplyOutcome;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TriggerStagingMocks;

public class CommittedOpTriggersOutcomeTest {
    private static final String ACTING_USER = "fence-owner";
    private static final String OBJECTS_FIELD = "objects";
    private static final String DELETED_DOCUMENT_FIELD = "deletedDocument";
    private static final String OTHER_COLL = "otherColl";

    private Transaction transaction;
    private long nextSeq;

    @BeforeEach
    void reset() {
        transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        nextSeq = 0;
    }

    private static Map<String, ApplyOutcome> superseded(String collName, String id) {
        return Map.of(keyOf(collName, id), ApplyOutcome.SUPERSEDED);
    }

    private static String keyOf(String collName, String id) {
        return Cache.getCollectionIdentifier(TestGlobals.DB, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + id;
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    private static JsonObject document(String id) {
        final var object = doc(id);
        object.add("value", new JsonString("v"));
        return object;
    }

    private AdminTransactionEntry opIn(String collName, String opType, JsonObject payload, String... insertedIds) {
        final var seq = nextSeq++;
        if (insertedIds.length > 0) {
            transaction.recordInserts(seq, List.of(insertedIds));
        }
        return new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", seq, opType,
                TestGlobals.DB, collName, payload);
    }

    private AdminTransactionEntry op(String opType, JsonObject payload, String... insertedIds) {
        return opIn(TestGlobals.COLL, opType, payload, insertedIds);
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

    private void fire(List<AdminTransactionEntry> ops, Map<String, ApplyOutcome> outcomes) {
        CommittedOpTriggers.stage(ops, ACTING_USER, transaction.getTriggerDepth(), transaction, outcomes,
                transaction.getTransactionId().toString());
    }

    @Test
    public void test_a_superseded_delete_does_not_fire_its_deleted_trigger() {
        final var ops = List.of(deleteOp("fenced-del"));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, superseded(TestGlobals.COLL, "fenced-del"));

            triggers.verify(() -> TriggerHelper.stageCommitted(anyString(), anyString(), eq(EventType.DELETED),
                    anyList(), anyString(), anyInt(), anyString(), any()), never());
        }
    }

    @Test
    public void test_an_applied_delete_still_fires_its_deleted_trigger() {
        final var ops = List.of(deleteOp("kept-del"));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(() -> TriggerHelper.stageCommitted(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.DELETED), anyList(), eq(ACTING_USER), anyInt(), anyString(), any()), times(1));
        }
    }

    @Test
    public void test_a_delete_superseded_in_another_collection_still_fires_here() {
        final var ops = List.of(deleteOp("shared-id"));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, superseded("otherCollection", "shared-id"));

            triggers.verify(() -> TriggerHelper.stageCommitted(anyString(), anyString(), eq(EventType.DELETED),
                    anyList(), anyString(), anyInt(), anyString(), any()), times(1));
        }
    }

    @Test
    public void test_a_superseded_save_fires_nothing() {
        final var ops = List.of(saveOp("fenced-save", true));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, superseded(TestGlobals.COLL, "fenced-save"));

            triggers.verify(() -> TriggerHelper.stageCommittedIds(anyString(), anyString(), eq(EventType.CREATED),
                    anyList(), anyString(), anyInt(), anyString(), any()), never());
        }
    }

    @Test
    public void test_an_already_applied_save_still_fires() {
        final var ops = List.of(saveOp("prefix-save", true));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of(keyOf(TestGlobals.COLL, "prefix-save"), ApplyOutcome.ALREADY_APPLIED));

            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("prefix-save")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_an_applied_save_fires_created_for_the_id_it_inserted() {
        final var ops = List.of(saveOp("kept-save", true));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("kept-save")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_an_applied_save_of_an_existing_id_fires_updated() {
        final var ops = List.of(saveOp("kept-update", false));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.UPDATED), eq(List.of("kept-update")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_a_partly_superseded_bulk_save_fires_only_for_the_applied_objects() {
        final var ops = List.of(bulkSaveOpInserting(List.of("bulk-fenced", "bulk-kept")));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, superseded(TestGlobals.COLL, "bulk-fenced"));

            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("bulk-kept")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_a_superseded_delete_does_not_suppress_its_applied_neighbour() {
        final var ops = List.of(deleteOp("fenced-del"), saveOp("kept-save", true));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, superseded(TestGlobals.COLL, "fenced-del"));

            triggers.verify(() -> TriggerHelper.stageCommitted(anyString(), anyString(), eq(EventType.DELETED),
                    anyList(), anyString(), anyInt(), anyString(), any()), never());
            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("kept-save")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_an_id_without_a_recorded_outcome_still_fires() {
        final var ops = List.of(deleteOp("overload-del"));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(() -> TriggerHelper.stageCommitted(anyString(), anyString(), eq(EventType.DELETED),
                    anyList(), eq(ACTING_USER), anyInt(), anyString(), any()), times(1));
        }
    }

    @Test
    public void test_two_saves_of_the_same_id_in_one_transaction_fire_updated_once() {
        final var ops = List.of(saveOp("dup", false), saveOp("dup", false));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(
                    () -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                            eq(EventType.UPDATED), eq(List.of("dup")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
            triggers.verify(
                    () -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                            eq(EventType.CREATED), eq(List.of()), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_an_insert_followed_by_an_update_of_the_same_id_in_one_transaction_fires_created_once() {
        final var ops = List.of(saveOp("dup-ins", true), saveOp("dup-ins", false));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("dup-ins")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
            triggers.verify(
                    () -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                            eq(EventType.UPDATED), eq(List.of()), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    @Test
    public void test_a_save_then_a_bulk_save_of_the_same_id_collapse_to_one_fire() {
        final var ops = List.of(saveOp("dup-mixed", true), bulkSaveOpInserting(List.of("dup-mixed", "other")));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.CREATED), eq(List.of("dup-mixed", "other")), eq(ACTING_USER), anyInt(), anyString(),
                    any()), times(1));
        }
    }

    @Test
    public void test_two_saves_of_different_ids_still_fire_separately() {
        final var ops = List.of(saveOp("a", true), saveOp("b", false));

        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            fire(ops, Map.of());

            triggers.verify(
                    () -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                            eq(EventType.CREATED), eq(List.of("a")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
            triggers.verify(
                    () -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                            eq(EventType.UPDATED), eq(List.of("b")), eq(ACTING_USER), anyInt(), anyString(), any()),
                    times(1));
        }
    }

    private AdminTransactionEntry insertOf(String id) {
        return opIn(TestGlobals.COLL, AdminTransactionEntry.OP_TYPE_SAVE, doc(id), id);
    }

    private AdminTransactionEntry bulkInsertOfAAndB() {
        final var objects = new JsonArray();
        objects.add(doc("a"));
        objects.add(doc("b"));
        final var payload = new JsonObject();
        payload.add(OBJECTS_FIELD, objects);
        return opIn(TestGlobals.COLL, AdminTransactionEntry.OP_TYPE_BULK_SAVE, payload, "a", "b");
    }

    private AdminTransactionEntry removalOf(String coll, String id) {
        final var payload = doc(id);
        payload.add(DELETED_DOCUMENT_FIELD, doc(id));
        return opIn(coll, AdminTransactionEntry.OP_TYPE_DELETE, payload);
    }

    private void commit(AdminTransactionEntry... ops) {
        fire(List.of(ops), Map.of());
    }

    private static void verifyDeletedFired(MockedStatic<TriggerHelper> triggers, String coll, int count) {
        triggers.verify(() -> TriggerHelper.stageCommitted(eq(TestGlobals.DB), eq(coll), eq(EventType.DELETED),
                anyList(), anyString(), anyInt(), anyString(), any()), times(count));
    }

    private static void verifyCreatedFor(MockedStatic<TriggerHelper> triggers, List<String> ids) {
        triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                eq(EventType.CREATED), argThat(list -> list.equals(ids)), anyString(), anyInt(), anyString(), any()));
    }

    @Test
    public void test_save_new_then_delete_fires_nothing() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(insertOf("x"), removalOf(TestGlobals.COLL, "x"));
            verifyDeletedFired(triggers, TestGlobals.COLL, 0);
            verifyCreatedFor(triggers, List.of());
        }
    }

    @Test
    public void test_save_new_then_delete_then_save_fires_one_created() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(insertOf("x2"), removalOf(TestGlobals.COLL, "x2"), insertOf("x2"));
            verifyDeletedFired(triggers, TestGlobals.COLL, 0);
            verifyCreatedFor(triggers, List.of("x2"));
        }
    }

    @Test
    public void test_save_existing_then_delete_fires_deleted() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(opIn(TestGlobals.COLL, AdminTransactionEntry.OP_TYPE_SAVE, doc("y")),
                    removalOf(TestGlobals.COLL, "y"));
            verifyDeletedFired(triggers, TestGlobals.COLL, 1);
        }
    }

    @Test
    public void test_bulk_save_new_then_delete_fires_nothing() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(bulkInsertOfAAndB(), removalOf(TestGlobals.COLL, "a"));
            verifyDeletedFired(triggers, TestGlobals.COLL, 0);
            verifyCreatedFor(triggers, List.of("b"));
        }
    }

    @Test
    public void test_create_and_delete_in_different_collections_are_independent() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(insertOf("z"), removalOf(OTHER_COLL, "z"));
            verifyDeletedFired(triggers, OTHER_COLL, 1);
            verifyCreatedFor(triggers, List.of("z"));
            triggers.verify(() -> TriggerHelper.stageCommitted(anyString(), eq(TestGlobals.COLL), eq(EventType.DELETED),
                    anyList(), anyString(), anyInt(), anyString(), any()), never());
        }
    }

    @Test
    public void test_deletes_in_one_transaction_are_staged_once_per_collection() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(removalOf(TestGlobals.COLL, "d1"), removalOf(TestGlobals.COLL, "d2"),
                    removalOf(TestGlobals.COLL, "d3"));
            verifyDeletedFired(triggers, TestGlobals.COLL, 1);
            triggers.verify(() -> TriggerHelper.stageCommitted(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.DELETED), argThat(entries -> idsOf(entries).equals(List.of("d1", "d2", "d3"))),
                    anyString(), anyInt(), anyString(), any()));
        }
    }

    @Test
    public void test_deletes_across_collections_are_staged_separately() {
        try (var triggers = TriggerStagingMocks.mockTriggerHelper()) {
            commit(removalOf(TestGlobals.COLL, "e1"), removalOf(OTHER_COLL, "e2"), removalOf(TestGlobals.COLL, "e3"));
            verifyDeletedFired(triggers, TestGlobals.COLL, 1);
            verifyDeletedFired(triggers, OTHER_COLL, 1);
            triggers.verify(() -> TriggerHelper.stageCommitted(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                    eq(EventType.DELETED), argThat(entries -> idsOf(entries).equals(List.of("e1", "e3"))), anyString(),
                    anyInt(), anyString(), any()));
        }
    }

    private static List<String> idsOf(List<DbEntry> entries) {
        return entries.stream().map(DbEntry::get_id).toList();
    }
}
