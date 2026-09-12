package org.techhouse.cluster;

import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;

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
