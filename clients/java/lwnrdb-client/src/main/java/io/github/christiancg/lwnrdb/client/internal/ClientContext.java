package io.github.christiancg.lwnrdb.client.internal;

import java.time.Duration;
import java.util.Optional;

public record ClientContext(RequestChannel channel, RequestEncoder encoder, ScriptRequestEncoder scripts, Json json,
        Listens listens, Optional<String> username, Duration requestTimeout) {

    public ClientContext withChannel(RequestChannel pinned) {
        return new ClientContext(pinned, encoder, scripts, json, listens, username, requestTimeout);
    }
}
