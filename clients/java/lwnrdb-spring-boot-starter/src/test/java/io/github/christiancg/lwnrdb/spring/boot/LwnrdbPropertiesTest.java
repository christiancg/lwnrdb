package io.github.christiancg.lwnrdb.spring.boot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.RetryPolicy;
import io.github.christiancg.lwnrdb.client.ServerAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

public class LwnrdbPropertiesTest {
    private static LwnrdbProperties.Tls noTls() {
        return new LwnrdbProperties.Tls(false, false, null, null, "PKCS12");
    }

    private static LwnrdbProperties.Retry defaultRetry() {
        return new LwnrdbProperties.Retry(null, null, null);
    }

    @Test
    public void test_every_property_reaches_the_settings() {
        final var mapper = JsonMapper.builder().build();
        final var properties = new LwnrdbProperties(List.of("db1:9000", "db2"), "alice", "pw", "shop", 3,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4),
                DataSize.ofMegabytes(1), ZoneOffset.UTC,
                new LwnrdbProperties.Tls(true, false, Path.of("t.p12"), "s", "JKS"),
                new LwnrdbProperties.Retry(2, Duration.ofMillis(5), Duration.ofMillis(9)));
        final var settings = properties.toSettings(mapper).build();
        assertEquals(List.of(new ServerAddress("db1", 9000), new ServerAddress("db2", 8989)), settings.hosts());
        assertEquals("alice", settings.username().orElseThrow());
        assertArrayEquals("pw".toCharArray(), settings.password());
        assertEquals(3, settings.poolSize());
        assertEquals(Duration.ofSeconds(1), settings.connectTimeout());
        assertEquals(Duration.ofSeconds(2), settings.requestTimeout());
        assertEquals(Duration.ofSeconds(3), settings.acquireTimeout());
        assertEquals(Duration.ofSeconds(4), settings.shutdownTimeout());
        assertEquals(DataSize.ofMegabytes(1).toBytes(), settings.maxReplyBytes());
        assertEquals(ZoneOffset.UTC, settings.temporalZone().orElseThrow());
        assertEquals("JKS", settings.tls().orElseThrow().trustStoreType());
        assertEquals(new RetryPolicy(2, Duration.ofMillis(5), Duration.ofMillis(9)), settings.retryPolicy());
        assertEquals(mapper, settings.objectMapper().orElseThrow());
    }

    @Test
    public void test_defaults_leave_the_clients_own_values() {
        final var settings = new LwnrdbProperties(List.of("localhost:8989"), null, null, null, null, null, null, null,
                null, null, null, noTls(), defaultRetry()).toSettings(null).build();
        assertTrue(settings.username().isEmpty());
        assertTrue(settings.tls().isEmpty());
        assertEquals(RetryPolicy.defaults(), settings.retryPolicy());
        assertArrayEquals(new char[0], new LwnrdbProperties(List.of("h"), "u", null, null, null, null, null, null, null,
                null, null, noTls(), defaultRetry()).toSettings(null).build().password());
    }

    @Test
    public void test_tls_modes() {
        assertTrue(new LwnrdbProperties.Tls(false, true, null, null, "PKCS12").toSettings().isInsecure());
        assertFalse(
                new LwnrdbProperties.Tls(true, false, null, null, "PKCS12").toSettings().trustStorePath().isPresent());
        assertNull(
                new LwnrdbProperties.Tls(true, false, Path.of("t"), null, "PKCS12").toSettings().trustStorePassword());
    }
}
