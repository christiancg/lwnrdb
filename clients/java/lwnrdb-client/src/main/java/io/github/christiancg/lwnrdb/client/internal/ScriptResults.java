package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.ScriptResult;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

public final class ScriptResults {
    private ScriptResults() {
    }

    public static ScriptResult of(Json json, ObjectNode reply) {
        final var result = reply.get("result");
        return new ScriptResult(result == null ? null : json.toPlain(result),
                ResponseDecoder.strings(reply.get("logs")), reply.path("logsTruncated").asBoolean(false),
                Optional.ofNullable(ResponseDecoder.textOrNull(reply.get("runId"))));
    }
}
