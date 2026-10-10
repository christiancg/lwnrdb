package org.techhouse.cluster;

import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;

final class PeerShutdownGate {
    private static final EJson eJson = IocContainer.get(EJson.class);

    private PeerShutdownGate() {
    }

    static boolean carriesWrite(ClusterMessage request) {
        return switch (request.getType()) {
            case REPLICATE, REPLICATE_TX, REPLICATE_ADMIN, REINDEX_BROADCAST, FORWARD_REQUEST, PREPARE_TX, COMMIT_TX ->
                true;
            case FORWARD_TX_REQUEST -> forwardedType(request) != OperationType.ROLLBACK_TRANSACTION;
            default -> false;
        };
    }

    static ClusterMessage refusal(ClusterMessage request) {
        final var response = new ClusterMessage();
        final var forwardedType = isForward(request.getType()) ? forwardedType(request) : null;
        if (forwardedType == null) {
            response.setType(ClusterMessageType.ERROR);
            response.setErrorMessage("Refused " + request.getType() + ": this node is shutting down");
            return response;
        }
        response.setType(ClusterMessageType.FORWARD_RESPONSE);
        response.setForwardBody(
                ForwardBody.encode(eJson.toJson(new OperationResponse(forwardedType, ErrorCode.SERVER_SHUTTING_DOWN))));
        return response;
    }

    private static boolean isForward(ClusterMessageType type) {
        return type == ClusterMessageType.FORWARD_REQUEST || type == ClusterMessageType.FORWARD_TX_REQUEST;
    }

    private static OperationType forwardedType(ClusterMessage request) {
        try {
            return RequestParser.parseRequest(ForwardBody.decode(request.getForwardBody())).getType();
        } catch (RuntimeException unparseable) {
            return null;
        }
    }
}
