package org.techhouse.unit;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.StartupWarnings;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.log.LogWriter;

public class StartupWarningsMetricsTest {
    private final Configuration config = Configuration.getInstance();

    @AfterEach
    void tearDown() {
        for (final var key : new ConfigKey[]{ConfigKey.METRICS_PORT, ConfigKey.METRICS_BIND_ADDRESS,
                ConfigKey.METRICS_TOKEN}) {
            config.override(key, key.defaultValue());
        }
    }

    private void metrics(String port, String bindAddress, String token) {
        config.override(ConfigKey.METRICS_PORT, port);
        config.override(ConfigKey.METRICS_BIND_ADDRESS, bindAddress);
        config.override(ConfigKey.METRICS_TOKEN, token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "", "10.1.2.3", "metrics.internal"})
    public void test_warns_when_reachable_beyond_loopback_without_a_token(String bindAddress) {
        metrics("9100", bindAddress, "");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfMetricsExposedWithoutToken();
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("SECURITY WARNING")
                    && entry.contains("metricsToken") && entry.contains("port 9100"))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.0.1", "::1", "[::1]", "localhost", "LocalHost"})
    public void test_silent_on_a_loopback_address(String bindAddress) {
        metrics("9100", bindAddress, "");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfMetricsExposedWithoutToken();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_silent_with_a_token() {
        metrics("9100", "0.0.0.0", "s3cret");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfMetricsExposedWithoutToken();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_silent_when_the_endpoint_is_off() {
        metrics("0", "0.0.0.0", "");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfMetricsExposedWithoutToken();
            logWriter.verifyNoInteractions();
        }
    }
}
