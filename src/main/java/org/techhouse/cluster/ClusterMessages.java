package org.techhouse.cluster;

import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;

// Every handler answers the same way: fill in an ack, or turn whatever went wrong into an ERROR the
// caller can read. Handling it here keeps one failure shape across the whole protocol.
final class ClusterMessages {
    private ClusterMessages() {
    }

    interface AckBuilder {
        void build(ClusterMessage response) throws Exception;
    }

    static ClusterMessage reply(ClusterMessageType ackType, String failureLabel, AckBuilder ackBuilder) {
        final var response = new ClusterMessage();
        try {
            response.setType(ackType);
            ackBuilder.build(response);
        } catch (Exception e) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage(failureLabel + ": " + e.getMessage());
        }
        return response;
    }
}
