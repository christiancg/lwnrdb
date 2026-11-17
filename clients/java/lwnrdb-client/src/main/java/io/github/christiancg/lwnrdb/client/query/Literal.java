package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;

public record Literal(String text) {
    public static final String WIRE_PREFIX = "-";

    public Literal {
        Objects.requireNonNull(text, "text");
    }
}
