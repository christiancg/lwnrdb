package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;

public class ClusterConnectionLaneKeyTest {
    private static ReplicationPayload delete(String coll) {
        return new ReplicationPayload("mydb", coll, ReplicationOp.DELETE, null, List.of("a"), List.of("1"));
    }

    private static ClusterMessage message(ClusterMessageType type) {
        final var message = new ClusterMessage();
        message.setType(type);
        return message;
    }

    @Test
    public void test_a_replicate_runs_on_its_collection_lane() {
        final var message = message(ClusterMessageType.REPLICATE);
        message.setReplication(delete("items"));

        assertEquals("mydb|items", ClusterConnectionHandler.laneKeyOf(message));
    }

    @Test
    public void test_a_replicate_naming_an_invalid_collection_uses_the_control_lane() {
        final var message = message(ClusterMessageType.REPLICATE);
        message.setReplication(delete("../etc"));

        assertNull(ClusterConnectionHandler.laneKeyOf(message));
        assertNull(ClusterConnectionHandler.laneKeyOf(message(ClusterMessageType.REPLICATE)));
    }

    @Test
    public void test_a_single_collection_batch_runs_on_that_collection_lane() {
        final var message = message(ClusterMessageType.REPLICATE_TX);
        message.setTxReplication(new TxReplicationPayload(List.of(delete("items"), delete("items"))));

        assertEquals("mydb|items", ClusterConnectionHandler.laneKeyOf(message));
    }

    @Test
    public void test_a_multi_collection_batch_uses_the_control_lane() {
        final var message = message(ClusterMessageType.REPLICATE_TX);
        message.setTxReplication(new TxReplicationPayload(List.of(delete("items"), delete("other"))));

        assertNull(ClusterConnectionHandler.laneKeyOf(message));
        assertNull(ClusterConnectionHandler.laneKeyOf(message(ClusterMessageType.REPLICATE_TX)));
    }

    @Test
    public void test_other_messages_use_the_control_lane() {
        assertNull(ClusterConnectionHandler.laneKeyOf(message(ClusterMessageType.REPLICATE_ADMIN)));
        assertNull(ClusterConnectionHandler.laneKeyOf(message(ClusterMessageType.PREPARE_TX)));
    }
}
