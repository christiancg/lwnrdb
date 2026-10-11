package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.test.ClusterTestHarness;

public class ClusterMessageTest {

    @Test
    public void test_members_default_to_null_when_unset() {
        assertNull(new ClusterMessage().getMembers());
    }

    @Test
    public void test_setters_and_getters() {
        final var message = new ClusterMessage();
        final var sender = ClusterTestHarness.node("n", 9990);
        message.setCorrelationId("corr");
        message.setType(ClusterMessageType.JOIN_REQUEST);
        message.setSecret("secret");
        message.setSender(sender);
        message.setMembers(List.of(sender));
        message.setErrorMessage("err");
        assertEquals("corr", message.getCorrelationId());
        assertEquals(ClusterMessageType.JOIN_REQUEST, message.getType());
        assertEquals("secret", message.getSecret());
        assertEquals(sender, message.getSender());
        assertEquals(1, message.getMembers().size());
        assertEquals("err", message.getErrorMessage());
    }
}
