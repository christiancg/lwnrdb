package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;

public record ScriptFilter(String source) implements Filter {
    public ScriptFilter {
        Objects.requireNonNull(source, "source");
    }
}
