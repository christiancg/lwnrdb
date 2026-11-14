package io.github.christiancg.lwnrdb.testsupport;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import tools.jackson.databind.node.ObjectNode;

public final class CannedReplies implements FakeServer.Responder {
    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private final Map<String, Queue<String>> onceReplies = new ConcurrentHashMap<>();

    public CannedReplies reply(String type, String frame) {
        replies.put(type, frame);
        return this;
    }

    public void replyOnce(String type, String frame) {
        onceReplies.computeIfAbsent(type, ignored -> new ConcurrentLinkedQueue<>()).add(frame);
    }

    public CannedReplies forget(String type) {
        replies.remove(type);
        return this;
    }

    @Override
    public void respond(ObjectNode request, FakeServer.Session session) throws IOException {
        final var type = request.path("type").asString();
        final var queued = onceReplies.get(type);
        final var once = queued == null ? null : queued.poll();
        final var frame = once != null ? once : replies.get(type);
        if (frame == null) {
            session.send(FakeServer.ok(request));
        } else {
            session.sendRaw(frame + "\n");
        }
    }
}
