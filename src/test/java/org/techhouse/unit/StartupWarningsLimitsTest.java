package org.techhouse.unit;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.StartupWarnings;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestUtils;

public class StartupWarningsLimitsTest {
    private static final List<ConfigKey> TOUCHED = List.of(ConfigKey.MAX_CONNECTIONS,
            ConfigKey.MAX_UNAUTHENTICATED_CONNECTIONS_PER_ADDRESS, ConfigKey.MAX_UNAUTHENTICATED_INVALID_REQUESTS,
            ConfigKey.MAX_FAILED_AUTH_ATTEMPTS, ConfigKey.AUTH_FAILURE_DELAY_BASE_MS, ConfigKey.TRANSACTION_MAX_OPS,
            ConfigKey.TRANSACTION_MAX_AGE_MS, ConfigKey.MAX_LISTENS_PER_CONNECTION, ConfigKey.MAX_LISTENS,
            ConfigKey.MAX_FILTER_OPERANDS, ConfigKey.AGGREGATE_TIMEOUT_MS, ConfigKey.MAX_CONCURRENT_SCRIPTS,
            ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_USER, ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_DATABASE,
            ConfigKey.MAX_CONCURRENT_PASSWORD_CHECKS);
    private final Configuration config = Configuration.getInstance();
    private final Map<ConfigKey, String> original = new EnumMap<>(ConfigKey.class);

    @BeforeEach
    public void setUp() throws Exception {
        for (final var key : TOUCHED) {
            original.put(key, config.stringValue(key));
            TestUtils.setPrivateField(config, key.key(), "7");
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var entry : original.entrySet()) {
            TestUtils.setPrivateField(config, entry.getKey().key(), entry.getValue());
        }
    }

    @Test
    public void test_a_zero_protective_limit_warns() throws Exception {
        TestUtils.setPrivateField(config, "maxConnections", "0");
        TestUtils.setPrivateField(config, "aggregateTimeoutMs", " 0 ");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfProtectiveLimitsAreOff();
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("SECURITY WARNING")
                    && entry.contains("maxConnections, aggregateTimeoutMs") && !entry.contains("maxListens"))));
        }
    }

    @Test
    public void test_positive_limits_stay_silent() {
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfProtectiveLimitsAreOff();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_a_zero_that_means_automatic_is_not_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "maxConcurrentPasswordChecks", "0");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfProtectiveLimitsAreOff();
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("SECURITY WARNING"))),
                    never());
        }
    }
}
