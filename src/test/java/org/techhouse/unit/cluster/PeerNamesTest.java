package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.PeerNames;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.ejson.elements.JsonObject;

public class PeerNamesTest {
    private static JsonObject document(String id) {
        final var document = new JsonObject();
        document.addProperty("_id", id);
        return document;
    }

    private static ReplicationPayload upsertIntoOrders(String... ids) {
        final var documents = new ArrayList<JsonObject>();
        for (final var id : ids) {
            documents.add(document(id));
        }
        return new ReplicationPayload("shop", "orders", ReplicationOp.UPSERT, documents, null);
    }

    private static ReplicationPayload delete(String db, String coll, List<String> ids) {
        return new ReplicationPayload(db, coll, ReplicationOp.DELETE, null, ids);
    }

    @Test
    public void acceptsOrdinaryNames() {
        assertTrue(PeerNames.collection("myDb", "my-coll_1"));
        assertTrue(PeerNames.collection("abc", "a".repeat(64)));
        assertTrue(PeerNames.collection("shop", "script_runs"));
        assertTrue(PeerNames.payload(upsertIntoOrders("o-1", "3f2a9c1e-7b1d-4c1a-9a5e-0c5b1f9d2e11")));
        assertTrue(PeerNames.payload(delete("shop", "orders", List.of("o-1"))));
        assertTrue(PeerNames.antiEntropy(new AntiEntropyPayload("shop", "orders")));
    }

    @Test
    public void rejectsTraversalNames() {
        for (final var bad : List.of("../x", "..", "a/b", "a\\b", "", "ab", "a".repeat(65), "a.b", "a|b")) {
            assertFalse(PeerNames.collection(bad, "orders"), bad);
            assertFalse(PeerNames.collection("shop", bad), bad);
        }
        assertFalse(PeerNames.collection(null, "orders"));
        assertFalse(PeerNames.collection("shop", null));
        assertFalse(PeerNames.antiEntropy(new AntiEntropyPayload("../x", "orders")));
        assertFalse(PeerNames.antiEntropy(null));
    }

    @Test
    public void rejectsReservedDatabases() {
        for (final var reserved : List.of("admin", "admin_pages", "cluster", "ADMIN", "Admin_Pages", "CLUSTER")) {
            assertFalse(PeerNames.collection(reserved, "orders"), reserved);
        }
    }

    @Test
    public void rejectsABadDocumentId() {
        assertFalse(PeerNames.payload(upsertIntoOrders("../x")));
        assertFalse(PeerNames.payload(upsertIntoOrders("")));
        assertFalse(PeerNames.payload(upsertIntoOrders("a".repeat(65))));
        assertFalse(PeerNames.payload(delete("shop", "orders", List.of("ok", "a/b"))));
        final var numericId = new JsonObject();
        numericId.addProperty("_id", 5);
        assertFalse(PeerNames
                .payload(new ReplicationPayload("shop", "orders", ReplicationOp.UPSERT, List.of(numericId), null)));
        assertFalse(PeerNames.payload(
                new ReplicationPayload("shop", "orders", ReplicationOp.UPSERT, List.of(new JsonObject()), null)));
    }

    @Test
    public void rejectsADeleteWithoutIds() {
        assertFalse(PeerNames.payload(delete("shop", "orders", null)));
    }

    @Test
    public void rejectsAnUpsertWithoutDocuments() {
        assertFalse(PeerNames.payload(new ReplicationPayload("shop", "orders", ReplicationOp.UPSERT, null, null)));
    }

    @Test
    public void rejectsAPayloadWithoutAnOperation() {
        assertFalse(PeerNames.payload(new ReplicationPayload("shop", "orders", null, List.of(), List.of())));
        assertFalse(PeerNames.payload(null));
    }

    @Test
    public void validatesEveryEntryOfATxPayload() {
        assertTrue(PeerNames.txPayload(
                new TxReplicationPayload(List.of(upsertIntoOrders("a1"), delete("shop", "items", List.of("b1"))))));
        assertFalse(PeerNames.txPayload(
                new TxReplicationPayload(List.of(upsertIntoOrders("a1"), delete("../x", "items", List.of("b1"))))));
        assertFalse(PeerNames.txPayload(new TxReplicationPayload(List.of())));
        assertFalse(PeerNames.txPayload(null));
    }
}
