package org.techhouse.ops.resp;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonSizeEstimator;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ServerInfoResponse extends OperationResponse {
    private final JsonObject server;

    public ServerInfoResponse(String message, JsonObject server) {
        super(OperationType.SERVER_INFO, OperationStatus.OK, message);
        this.server = server;
    }

    public JsonObject getServer() {
        return server;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(server));
    }
}
