package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ejson.EJson;

public class ClusterMessageSerializationTest {
    private final EJson eJson = new EJson();

    @Test
    public void test_round_trip_with_sender_and_members() {
        final var sender = new NodeInfo("a", "127.0.0.1", 9990, NodeState.ALIVE, 10L, 3L);
        final var members = List.of(sender, new NodeInfo("b", "127.0.0.1", 9991, NodeState.SUSPECT, 11L, 2L));
        final var message = new ClusterMessage("corr-1", ClusterMessageType.GOSSIP, "secret", sender, members);

        final var json = eJson.toJson(message);
        final var parsed = eJson.fromJson(json, ClusterMessage.class);

        assertEquals("corr-1", parsed.getCorrelationId());
        assertEquals(ClusterMessageType.GOSSIP, parsed.getType());
        assertEquals("secret", parsed.getSecret());
        assertEquals("a", parsed.getSender().getNodeId());
        assertEquals(NodeState.ALIVE, parsed.getSender().getState());
        assertEquals(2, parsed.getMembers().size());
        assertEquals("b", parsed.getMembers().get(1).getNodeId());
        assertEquals(NodeState.SUSPECT, parsed.getMembers().get(1).getState());
    }

    @Test
    public void test_round_trip_error_message() {
        final var message = new ClusterMessage();
        message.setType(ClusterMessageType.ERROR);
        message.setErrorMessage("Invalid cluster secret");

        final var parsed = eJson.fromJson(eJson.toJson(message), ClusterMessage.class);
        assertEquals(ClusterMessageType.ERROR, parsed.getType());
        assertEquals("Invalid cluster secret", parsed.getErrorMessage());
        assertNull(parsed.getSender());
    }

    @Test
    public void test_a_forward_body_with_quotes_newlines_and_surrogates_round_trips() {
        final var body = "{\"type\":\"SAVE\",\"object\":{\"_id\":\"q\\\"x\",\"t\":\"line\\nbreak \u0001 \\ud83d\"}}"
                + "\n\t\u0000 \ud83d\ude00 \udc00 \"\\";
        final var message = new ClusterMessage();
        message.setType(ClusterMessageType.FORWARD_REQUEST);
        message.setForwardBody(body);

        final var line = eJson.toJson(message);
        final var parsed = eJson.fromJson(line, ClusterMessage.class);

        assertEquals(body, parsed.getForwardBody());
        assertEquals(-1, line.indexOf('\n'), "the frame must stay one line");
    }

    @Test
    public void test_a_frame_carries_only_the_fields_it_sets() {
        final var message = new ClusterMessage();
        message.setType(ClusterMessageType.FORWARD_REQUEST);
        message.setCorrelationId("c-9");
        message.setForwardBody("{}");

        final var json = eJson.toJson(message);
        final var parsed = eJson.fromJson(json, ClusterMessage.class);

        assertEquals(-1, json.indexOf("null"), json);
        assertEquals(-1, json.indexOf("replication"), json);
        assertEquals("c-9", parsed.getCorrelationId());
        assertEquals("{}", parsed.getForwardBody());
        assertNull(parsed.getReplication());
        assertNull(parsed.getAntiEntropy());
    }
}
