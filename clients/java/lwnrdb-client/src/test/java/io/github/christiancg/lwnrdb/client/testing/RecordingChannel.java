package io.github.christiancg.lwnrdb.client.testing;

import io.github.christiancg.lwnrdb.client.internal.RequestChannel;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.node.ObjectNode;

public final class RecordingChannel implements RequestChannel {
    private final List<ObjectNode> requests = new ArrayList<>();
    private Function<ObjectNode, ObjectNode> replies = FakeServer::ok;

    public RecordingChannel replyWith(Function<ObjectNode, ObjectNode> replies) {
        this.replies = replies;
        return this;
    }

    @Override
    public ObjectNode send(Operation operation, ObjectNode request) {
        requests.add(request.deepCopy());
        return ResponseDecoder.check(replies.apply(request));
    }

    public List<ObjectNode> requests() {
        return requests;
    }

    public ObjectNode last() {
        if (requests.isEmpty()) {
            throw new AssertionError("no request was sent");
        }
        return requests.get(requests.size() - 1);
    }
}
