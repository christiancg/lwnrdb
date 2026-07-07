package org.techhouse.unit.data.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class AdminTransactionEntryTest {

    private JsonObject payload(String value) {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("doc-1"));
        obj.add("name", new JsonString(value));
        return obj;
    }

    @Test
    public void test_build_id_joins_transaction_and_seq() {
        assertEquals("txn-1" + Globals.COLL_IDENTIFIER_SEPARATOR + "3", AdminTransactionEntry.buildId("txn-1", 3));
    }

    @Test
    public void test_constructor_sets_fields_and_id() {
        final var entry = new AdminTransactionEntry("txn-1", "client-1", 2, AdminTransactionEntry.OP_TYPE_SAVE, "myDb",
                "myColl", payload("alice"));
        assertEquals(AdminTransactionEntry.buildId("txn-1", 2), entry.get_id());
        assertEquals("txn-1", entry.getTransactionId());
        assertEquals(2, entry.getSeq());
        assertEquals(AdminTransactionEntry.OP_TYPE_SAVE, entry.getOpType());
        assertEquals("myDb", entry.getTargetDb());
        assertEquals("myColl", entry.getTargetColl());
        assertEquals("alice", entry.getPayload().get("name").asJsonString().getValue());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, entry.getCollectionName());
    }

    @Test
    public void test_from_json_object_round_trip() {
        final var original = new AdminTransactionEntry("txn-2", "client-2", 5, AdminTransactionEntry.OP_TYPE_DELETE,
                "db2", "coll2", payload("bob"));
        // The persisted record carries its _id in the data (added when read back from the collection).
        original.getData().add(Globals.PK_FIELD, new JsonString(original.get_id()));
        final var restored = AdminTransactionEntry.fromJsonObject(original.getData());
        assertEquals(original.get_id(), restored.get_id());
        assertEquals(original.getTransactionId(), restored.getTransactionId());
        assertEquals(original.getSeq(), restored.getSeq());
        assertEquals(original.getOpType(), restored.getOpType());
        assertEquals(original.getTargetDb(), restored.getTargetDb());
        assertEquals(original.getTargetColl(), restored.getTargetColl());
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
    }

    @Test
    public void test_equals_and_hashcode_distinguish_entries() {
        final var a = new AdminTransactionEntry("txn-3", "c", 1, AdminTransactionEntry.OP_TYPE_SAVE, "db", "coll",
                payload("x"));
        final var b = new AdminTransactionEntry("txn-3", "c", 2, AdminTransactionEntry.OP_TYPE_SAVE, "db", "coll",
                payload("x"));
        assertNotEquals(a, b);
        assertNotEquals(null, a);
        assertNotEquals("not-an-entry", a);
    }

    @Test
    public void test_to_string_contains_key_fields() {
        final var entry = new AdminTransactionEntry("txn-4", "c", 0, AdminTransactionEntry.OP_TYPE_BULK_SAVE, "db",
                "coll", payload("y"));
        final var text = entry.toString();
        assertTrue(text.contains("txn-4"));
        assertTrue(text.contains(AdminTransactionEntry.OP_TYPE_BULK_SAVE));
    }
}
