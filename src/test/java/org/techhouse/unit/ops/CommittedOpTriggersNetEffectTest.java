package org.techhouse.unit.ops;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.bckg_ops.events.EventType;
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
import org.techhouse.test.TriggerStagingMocks;

public class CommittedOpTriggersNetEffectTest {
    private static final String OTHER_COLL = "otherColl";

    private Transaction transaction;
    private long seq;

    @BeforeEach
    void freshTransaction() {
        transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        seq = 0;
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    private AdminTransactionEntry entry(String coll, String type, JsonObject payload, List<String> inserted) {
        final var current = seq++;
        transaction.recordInserts(current, inserted);
        return new AdminTransactionEntry(transaction.getTransactionId().toString(), "client", current, type,
                TestGlobals.DB, coll, payload);
    }

    private AdminTransactionEntry insertOf(String id) {
        return entry(TestGlobals.COLL, AdminTransactionEntry.OP_TYPE_SAVE, doc(id), List.of(id));
    }

    private AdminTransactionEntry bulkInsertOfAAndB() {
        final var objects = new JsonArray();
        objects.add(doc("a"));
        objects.add(doc("b"));
        final var payload = new JsonObject();
        payload.add("objects", objects);
        return entry(TestGlobals.COLL, AdminTransactionEntry.OP_TYPE_BULK_SAVE, payload, List.of("a", "b"));
    }

    private AdminTransactionEntry removalOf(String coll, String id) {
        final var payload = doc(id);
        payload.add("deletedDocument", doc(id));
        return entry(coll, AdminTransactionEntry.OP_TYPE_DELETE, payload, List.of());
    }

    private void commit(AdminTransactionEntry... ops) {
        CommittedOpTriggers.stage(List.of(ops), "user", 0, transaction, java.util.Map.of(),
                transaction.getTransactionId().toString());
    }

    private static void verifyDeletedFired(MockedStatic<TriggerHelper> triggers, String coll, int count) {
        triggers.verify(() -> TriggerHelper.stageCommitted(eq(TestGlobals.DB), eq(coll), eq(EventType.DELETED),
                anyList(), anyString(), anyInt(), anyString()), times(count));
    }

    private static void verifyCreatedFor(MockedStatic<TriggerHelper> triggers, List<String> ids) {
        triggers.verify(() -> TriggerHelper.stageCommittedIds(eq(TestGlobals.DB), eq(TestGlobals.COLL),
                eq(EventType.CREATED), argThat(list -> list.equals(ids)), anyString(), anyInt(), anyString()));
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
            commit(entry(TestGlobals.COLL, AdminTransactionEntry.OP_TYPE_SAVE, doc("y"), List.of()),
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
                    anyList(), anyString(), anyInt(), anyString()), never());
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
                    anyString(), anyInt(), anyString()));
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
                    anyInt(), anyString()));
        }
    }

    private static List<String> idsOf(List<DbEntry> entries) {
        return entries.stream().map(DbEntry::get_id).toList();
    }
}
