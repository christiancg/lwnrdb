package io.github.christiancg.lwnrdb.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

public class SettingsTest {
    @Test
    public void test_defaults_and_overrides() {
        final var defaults = ClientSettings.builder().host("localhost", ServerAddress.DEFAULT_PORT).build();
        assertEquals(ClientSettings.DEFAULT_POOL_SIZE, defaults.poolSize());
        assertTrue(defaults.username().isEmpty());
        assertNull(defaults.password());
        assertTrue(defaults.tls().isEmpty());
        assertTrue(defaults.objectMapper().isEmpty());
        assertTrue(defaults.temporalZone().isEmpty());
        assertEquals(RetryPolicy.defaults(), defaults.retryPolicy());
        final var mapper = JsonMapper.builder().build();
        final var custom = ClientSettings.builder().host(new ServerAddress("db", 1)).credentials("alice", "pw")
                .tls(TlsSettings.insecure()).poolSize(3).connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(2)).acquireTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ofSeconds(4)).retryPolicy(RetryPolicy.none()).maxReplyBytes(10)
                .objectMapper(mapper).temporalZone(ZoneOffset.UTC).build();
        assertEquals("db:1", custom.hosts().get(0).toString());
        assertEquals("alice", custom.username().orElseThrow());
        assertArrayEquals("pw".toCharArray(), custom.password());
        assertTrue(custom.tls().orElseThrow().isInsecure());
        assertEquals(3, custom.poolSize());
        assertEquals(Duration.ofSeconds(1), custom.connectTimeout());
        assertEquals(Duration.ofSeconds(2), custom.requestTimeout());
        assertEquals(Duration.ofSeconds(3), custom.acquireTimeout());
        assertEquals(Duration.ofSeconds(4), custom.shutdownTimeout());
        assertEquals(1, custom.retryPolicy().maxAttempts());
        assertEquals(10, custom.maxReplyBytes());
        assertEquals(mapper, custom.objectMapper().orElseThrow());
        assertEquals(ZoneOffset.UTC, custom.temporalZone().orElseThrow());
    }

    @Test
    public void test_invalid_settings_are_refused() {
        assertThrows(IllegalArgumentException.class, () -> ClientSettings.builder().build());
        final var builder = ClientSettings.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.poolSize(0));
        assertThrows(IllegalArgumentException.class, () -> builder.maxReplyBytes(0));
        assertThrows(IllegalArgumentException.class, () -> builder.requestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ServerAddress("h", 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerAddress("h", 70_000));
    }

    @Test
    public void test_retry_backoff_doubles_up_to_its_cap() {
        final var policy = new RetryPolicy(10, Duration.ofMillis(100), Duration.ofMillis(350));
        assertEquals(Duration.ofMillis(100), policy.backoffBefore(1));
        assertEquals(Duration.ofMillis(200), policy.backoffBefore(2));
        assertEquals(Duration.ofMillis(350), policy.backoffBefore(3));
        assertEquals(Duration.ofMillis(350), policy.backoffBefore(80));
        assertEquals(Duration.ofMillis(100), policy.backoffBefore(0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(1, Duration.ofSeconds(-1), Duration.ofSeconds(1)));
    }

    @Test
    public void test_tls_settings_copy_their_password() {
        final var password = "secret".toCharArray();
        final var tls = TlsSettings.trustStore(Path.of("trust.p12"), password);
        password[0] = 'x';
        assertArrayEquals("secret".toCharArray(), tls.trustStorePassword());
        assertEquals(TlsSettings.DEFAULT_TRUST_STORE_TYPE, tls.trustStoreType());
        assertEquals(Path.of("trust.p12"), tls.trustStorePath().orElseThrow());
        assertFalse(tls.isInsecure());
        assertTrue(TlsSettings.systemTrust().trustStorePath().isEmpty());
        assertNull(TlsSettings.systemTrust().trustStorePassword());
    }
}
