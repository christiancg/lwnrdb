package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class ReplicationPayloadTest {
    private final EJson eJson = new EJson();

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    @Test
    public void test_upsert_payload_round_trips_on_a_cluster_message() {
        final var payload = new ReplicationPayload("db", "coll", ReplicationOp.UPSERT, List.of(doc("a"), doc("b")),
                null);
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE, "s", null, null);
        message.setReplication(payload);

        final var parsed = eJson.fromJson(eJson.toJson(message), ClusterMessage.class);
        final var parsedPayload = parsed.getReplication();

        assertEquals(ClusterMessageType.REPLICATE, parsed.getType());
        assertEquals("db", parsedPayload.getDbName());
        assertEquals("coll", parsedPayload.getCollName());
        assertEquals(ReplicationOp.UPSERT, parsedPayload.getOp());
        assertEquals(2, parsedPayload.getDocuments().size());
        assertEquals("a", parsedPayload.getDocuments().getFirst().get("_id").asJsonString().getValue());
        assertNull(parsedPayload.getIds());
    }

    @Test
    public void test_delete_payload_round_trips() {
        final var payload = new ReplicationPayload("db", "coll", ReplicationOp.DELETE, null, List.of("x", "y"));
        final var parsed = eJson.fromJson(eJson.toJson(payload), ReplicationPayload.class);
        assertEquals(ReplicationOp.DELETE, parsed.getOp());
        assertEquals(List.of("x", "y"), parsed.getIds());
        assertNull(parsed.getDocuments());
    }

    @Test
    public void test_setters() {
        final var payload = new ReplicationPayload();
        payload.setDbName("d");
        payload.setCollName("c");
        payload.setOp(ReplicationOp.UPSERT);
        payload.setDocuments(List.of(doc("1")));
        payload.setIds(List.of("1"));
        assertEquals("d", payload.getDbName());
        assertEquals("c", payload.getCollName());
        assertEquals(ReplicationOp.UPSERT, payload.getOp());
        assertEquals(1, payload.getDocuments().size());
        assertEquals(List.of("1"), payload.getIds());
    }
}
