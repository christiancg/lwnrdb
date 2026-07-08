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
}
