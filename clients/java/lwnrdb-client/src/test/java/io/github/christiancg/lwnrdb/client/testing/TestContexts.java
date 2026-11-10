package io.github.christiancg.lwnrdb.client.testing;

import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.Json;
import io.github.christiancg.lwnrdb.client.internal.RequestEncoder;
import io.github.christiancg.lwnrdb.client.internal.ScriptRequestEncoder;
import java.time.Duration;
import java.util.Optional;

public final class TestContexts {
    private TestContexts() {
    }

    public static ClientContext recording(RecordingChannel channel) {
        final var json = Json.defaults();
        final var encoder = new RequestEncoder(json);
        return new ClientContext(channel, encoder, new ScriptRequestEncoder(encoder.pipeline()), json, null,
                Optional.of("alice"), Duration.ofSeconds(5));
    }
}
