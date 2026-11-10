package io.github.christiancg.lwnrdb.testsupport;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.node.ObjectNode;

public final class CannedReplies implements FakeServer.Responder {
    private final Map<String, String> replies = new ConcurrentHashMap<>();

    public CannedReplies reply(String type, String frame) {
        replies.put(type, frame);
        return this;
    }

    public CannedReplies forget(String type) {
        replies.remove(type);
        return this;
    }

    @Override
    public void respond(ObjectNode request, FakeServer.Session session) throws IOException {
        final var frame = replies.get(request.path("type").asString());
        if (frame == null) {
            session.send(FakeServer.ok(request));
        } else {
            session.sendRaw(frame + "\n");
        }
    }
}
