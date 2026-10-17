package org.techhouse.cluster;

import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;

final class ClusterMessages {
    private static final String INVALID_PAYLOAD = "Rejected replicated payload: invalid collection or document id";

    private ClusterMessages() {
    }

    static ClusterMessage invalidPayload() {
        final var response = new ClusterMessage();
        response.setType(ClusterMessageType.ERROR);
        response.setErrorMessage(INVALID_PAYLOAD);
        return response;
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
