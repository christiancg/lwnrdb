package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.time.Duration;
import tools.jackson.databind.node.ObjectNode;

public final class PinnedChannel implements RequestChannel {
    private final WireConnection connection;
    private final Duration requestTimeout;

    public PinnedChannel(WireConnection connection, Duration requestTimeout) {
        this.connection = connection;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ObjectNode send(Operation operation, ObjectNode request) {
        return ResponseDecoder.check(connection.request(request, requestTimeout));
    }
}
