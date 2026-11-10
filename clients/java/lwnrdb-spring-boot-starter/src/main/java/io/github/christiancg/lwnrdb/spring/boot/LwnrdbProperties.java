package io.github.christiancg.lwnrdb.spring.boot;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.RetryPolicy;
import io.github.christiancg.lwnrdb.client.ServerAddress;
import io.github.christiancg.lwnrdb.client.TlsSettings;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

@ConfigurationProperties("lwnrdb")
public record LwnrdbProperties(@DefaultValue("localhost:8989") List<String> hosts, String username, String password,
        String database, Integer poolSize, Duration connectTimeout, Duration requestTimeout, Duration acquireTimeout,
        Duration shutdownTimeout, DataSize maxReplyBytes, ZoneId temporalZone, @DefaultValue Tls tls,
        @DefaultValue Retry retry) {
    private static final char PORT_SEPARATOR = ':';

    public record Tls(boolean enabled, boolean insecure, Path trustStore, String trustStorePassword,
            @DefaultValue(TlsSettings.DEFAULT_TRUST_STORE_TYPE) String trustStoreType) {
        TlsSettings toSettings() {
            if (insecure) {
                return TlsSettings.insecure();
            }
            if (trustStore() != null) {
                return TlsSettings.trustStore(trustStore,
                        trustStorePassword == null ? null : trustStorePassword.toCharArray(), trustStoreType);
            }
            return TlsSettings.systemTrust();
        }
    }

    public record Retry(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        RetryPolicy toPolicy() {
            return new RetryPolicy(maxAttempts == null ? RetryPolicy.DEFAULT_MAX_ATTEMPTS : maxAttempts,
                    initialBackoff == null ? RetryPolicy.DEFAULT_INITIAL_BACKOFF : initialBackoff,
                    maxBackoff == null ? RetryPolicy.DEFAULT_MAX_BACKOFF : maxBackoff);
        }
    }

    public ClientSettings.Builder toSettings(@Nullable JsonMapper objectMapper) {
        final var builder = ClientSettings.builder();
        hosts.forEach(host -> builder.host(address(host)));
        if (username() != null) {
            builder.credentials(username, password == null ? "" : password);
        }
        if (tls.enabled() || tls.insecure()) {
            builder.tls(tls.toSettings());
        }
        Optional.ofNullable(poolSize).ifPresent(builder::poolSize);
        Optional.ofNullable(connectTimeout).ifPresent(builder::connectTimeout);
        Optional.ofNullable(requestTimeout).ifPresent(builder::requestTimeout);
        Optional.ofNullable(acquireTimeout).ifPresent(builder::acquireTimeout);
        Optional.ofNullable(shutdownTimeout).ifPresent(builder::shutdownTimeout);
        Optional.ofNullable(maxReplyBytes).ifPresent(size -> builder.maxReplyBytes(size.toBytes()));
        Optional.ofNullable(temporalZone).ifPresent(builder::temporalZone);
        if (objectMapper != null) {
            builder.objectMapper(objectMapper);
        }
        return builder.retryPolicy(retry.toPolicy());
    }

    static ServerAddress address(String host) {
        final var separator = host.lastIndexOf(PORT_SEPARATOR);
        if (separator < 0) {
            return new ServerAddress(host.strip(), ServerAddress.DEFAULT_PORT);
        }
        return new ServerAddress(host.substring(0, separator).strip(),
                Integer.parseInt(host.substring(separator + 1).strip()));
    }
}
