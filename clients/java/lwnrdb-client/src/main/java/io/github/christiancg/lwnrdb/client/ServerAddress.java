package io.github.christiancg.lwnrdb.client;

import java.util.Objects;

public record ServerAddress(String host, int port) {
    public static final int DEFAULT_PORT = 8989;
    private static final int MAX_PORT = 65_535;

    public ServerAddress {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
