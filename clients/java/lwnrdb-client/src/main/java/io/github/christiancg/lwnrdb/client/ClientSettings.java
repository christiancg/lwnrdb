package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.internal.Json;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

public final class ClientSettings {
    public static final int DEFAULT_POOL_SIZE = 8;
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(330);
    public static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final List<ServerAddress> hosts;
    private final String username;
    private final char[] password;
    private final TlsSettings tls;
    private final int poolSize;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration acquireTimeout;
    private final Duration shutdownTimeout;
    private final RetryPolicy retryPolicy;
    private final long maxReplyBytes;
    private final JsonMapper objectMapper;
    private final ZoneId temporalZone;

    private ClientSettings(Builder builder) {
        if (builder.hosts.isEmpty()) {
            throw new IllegalArgumentException("at least one host is required");
        }
        this.hosts = List.copyOf(builder.hosts);
        this.username = builder.username;
        this.password = builder.password == null ? null : builder.password.clone();
        this.tls = builder.tls;
        this.poolSize = builder.poolSize;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.acquireTimeout = builder.acquireTimeout;
        this.shutdownTimeout = builder.shutdownTimeout;
        this.retryPolicy = builder.retryPolicy;
        this.maxReplyBytes = builder.maxReplyBytes;
        this.objectMapper = builder.objectMapper;
        this.temporalZone = builder.temporalZone;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ServerAddress> hosts() {
        return hosts;
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    public char[] password() {
        return password == null ? null : password.clone();
    }

    public Optional<TlsSettings> tls() {
        return Optional.ofNullable(tls);
    }

    public int poolSize() {
        return poolSize;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public long maxReplyBytes() {
        return maxReplyBytes;
    }

    public Optional<JsonMapper> objectMapper() {
        return Optional.ofNullable(objectMapper);
    }

    public Optional<ZoneId> temporalZone() {
        return Optional.ofNullable(temporalZone);
    }

    public static final class Builder {
        private final List<ServerAddress> hosts = new ArrayList<>();
        private String username;
        private char[] password;
        private TlsSettings tls;
        private int poolSize = DEFAULT_POOL_SIZE;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration acquireTimeout = DEFAULT_ACQUIRE_TIMEOUT;
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private long maxReplyBytes = Json.DEFAULT_MAX_REPLY_BYTES;
        private JsonMapper objectMapper;
        private ZoneId temporalZone;

        private Builder() {
        }

        public Builder host(String host, int port) {
            hosts.add(new ServerAddress(host, port));
            return this;
        }

        public Builder host(ServerAddress address) {
            hosts.add(Objects.requireNonNull(address, "address"));
            return this;
        }

        public Builder credentials(String username, char[] password) {
            this.username = Objects.requireNonNull(username, "username");
            this.password = Objects.requireNonNull(password, "password").clone();
            return this;
        }

        public Builder credentials(String username, String password) {
            return credentials(username, Objects.requireNonNull(password, "password").toCharArray());
        }

        public Builder tls(TlsSettings tls) {
            this.tls = Objects.requireNonNull(tls, "tls");
            return this;
        }

        public Builder poolSize(int poolSize) {
            if (poolSize < 1) {
                throw new IllegalArgumentException("poolSize must be at least 1: " + poolSize);
            }
            this.poolSize = poolSize;
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = positive(timeout, "connectTimeout");
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = positive(timeout, "requestTimeout");
            return this;
        }

        public Builder acquireTimeout(Duration timeout) {
            this.acquireTimeout = positive(timeout, "acquireTimeout");
            return this;
        }

        public Builder shutdownTimeout(Duration timeout) {
            this.shutdownTimeout = positive(timeout, "shutdownTimeout");
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        public Builder maxReplyBytes(long maxReplyBytes) {
            if (maxReplyBytes < 1) {
                throw new IllegalArgumentException("maxReplyBytes must be positive: " + maxReplyBytes);
            }
            this.maxReplyBytes = maxReplyBytes;
            return this;
        }

        public Builder objectMapper(JsonMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
            return this;
        }

        public Builder temporalZone(ZoneId zone) {
            this.temporalZone = Objects.requireNonNull(zone, "zone");
            return this;
        }

        public ClientSettings build() {
            return new ClientSettings(this);
        }

        private static Duration positive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive: " + duration);
            }
            return duration;
        }
    }
}
