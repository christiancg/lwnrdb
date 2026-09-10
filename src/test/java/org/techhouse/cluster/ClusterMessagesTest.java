package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessageType;

// Lives in org.techhouse.cluster rather than unit/cluster because ClusterMessages is package-private.
public class ClusterMessagesTest {

    @Test
    public void test_reply_returns_the_ack_the_builder_filled_in() {
        final var reply = ClusterMessages.reply(ClusterMessageType.ADMIN_SNAPSHOT_ACK, "Failed",
                response -> response.setAdminEpoch(7L));

        assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, reply.getType());
        assertEquals(7L, reply.getAdminEpoch());
    }

    // Every handler funnels its failures through here, so a thrown builder must come back as a
    // readable ERROR rather than propagating and killing the connection.
    @Test
    public void test_reply_wraps_a_failing_builder_as_an_error_ack() {
        final var reply = ClusterMessages.reply(ClusterMessageType.ADMIN_SNAPSHOT_ACK, "Snapshot failed", _ -> {
            throw new IllegalStateException("disk gone");
        });

        assertEquals(ClusterMessageType.ERROR, reply.getType());
        assertEquals("Snapshot failed: disk gone", reply.getErrorMessage());
    }

    // The ack type is set before the builder runs, so a builder that fails must not leave it behind.
    @Test
    public void test_a_failure_replaces_the_ack_type_it_had_already_set() {
        final var reply = ClusterMessages.reply(ClusterMessageType.REPLICATE_USER_ACK, "Replication failed", _ -> {
            throw new IllegalStateException("nope");
        });

        assertTrue(reply.getErrorMessage().startsWith("Replication failed"));
        assertEquals(ClusterMessageType.ERROR, reply.getType());
    }
}
