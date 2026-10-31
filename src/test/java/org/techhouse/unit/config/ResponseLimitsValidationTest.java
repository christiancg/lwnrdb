package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.ConfigurationValidator;
import org.techhouse.test.ConfigFixture;

public class ResponseLimitsValidationTest {
    @Test
    public void maxUnsentResponseBytesAcceptsZeroAndSizes(@TempDir Path tempDir) {
        for (final var valid : new String[]{"0", "4Mb", "1Gb"}) {
            final var config = ConfigFixture.fullValid(tempDir);
            config.put("maxUnsentResponseBytes", valid);
            assertTrue(ConfigurationValidator.validate(config).isEmpty(), valid);
        }
    }

    @Test
    public void rejectsOutOfRangeValues(@TempDir Path tempDir) {
        assertRejected(tempDir, "maxUnsentResponseBytes", "-5");
        assertRejected(tempDir, "maxUnsentResponseBytes", "lots");
        assertRejected(tempDir, "clientWriteStallTimeoutMs", "999");
        assertRejected(tempDir, "authVerifierCacheEnabled", "maybe");
    }

    private static void assertRejected(Path tempDir, String key, String value) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put(key, value);
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(error -> error.contains(key)), key + "=" + value + " -> " + errors);
    }
}
