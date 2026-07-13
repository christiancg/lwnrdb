package org.techhouse.cluster;

import java.util.Objects;
import org.techhouse.config.Globals;

public final class NodeAddress {
    private final String host;
    private final int port;

    public NodeAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static NodeAddress parse(String value) {
        final var trimmed = value.trim();
        final var separator = trimmed.lastIndexOf(Globals.CLUSTER_ADDRESS_SEPARATOR);
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw new IllegalArgumentException("Invalid node address, expected host:port but was: " + value);
        }
        final var host = trimmed.substring(0, separator);
        final int port;
        try {
            port = Integer.parseInt(trimmed.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in node address: " + value, e);
        }
        return new NodeAddress(host, port);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof NodeAddress that))
            return false;
        return port == that.port && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return host + Globals.CLUSTER_ADDRESS_SEPARATOR + port;
    }
}
