package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.protocol.Operation;
import tools.jackson.databind.node.ObjectNode;

public interface RequestChannel {
    ObjectNode send(Operation operation, ObjectNode request);
}
