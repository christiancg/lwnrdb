package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;

public record ScriptOperation(String source) implements MidOperation {
    public ScriptOperation {
        Objects.requireNonNull(source, "source");
    }
}
